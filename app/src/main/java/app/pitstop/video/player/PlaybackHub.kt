package app.pitstop.video.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import android.net.Uri
import app.pitstop.video.core.StreamPolicy
import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.I18n
import app.pitstop.video.core.UserFacingException
import app.pitstop.video.core.QualityLadder
import app.pitstop.video.core.StallTracker
import app.pitstop.video.core.isNetworkErrorCode
import app.pitstop.video.core.retryDelayMs
import kotlinx.coroutines.flow.first
import app.pitstop.video.core.Settings
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.VideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

enum class DisplayMode { FULL, SPLIT }

/** What happens when a track ends: stop after the queue, loop the queue, or loop the track. */
enum class RepeatMode { OFF, ALL, ONE }

data class HubState(
    val queue: List<VideoItem> = emptyList(),
    val index: Int = -1,
    val loading: Boolean = false,
    val error: String? = null,
    val isPlaying: Boolean = false,
    /** User intent (play pressed); stays true while buffering, so icons don't flicker. */
    val wantsToPlay: Boolean = false,
    val buffering: Boolean = false,
    /** True while the Android Auto surface owns the video output. */
    val carActive: Boolean = false,
    /** This device itself is a car screen (Android Automotive OS or a head unit the user marked). */
    val inCarDevice: Boolean = false,
    val mode: DisplayMode = DisplayMode.FULL,
    /** Car is moving and the car screen is in use: video paused and hidden. */
    val drivingLocked: Boolean = false,
    /** Subtitle languages available for the current video, and the one shown (null = off). */
    val subtitleOptions: List<String> = emptyList(),
    val subtitle: String? = null,
    /** A list / browse panel is open on the car screen: the video shrinks beside it. */
    val panelOpen: Boolean = false,
    /** Driving with audio-only mode on: picture hidden, sound keeps playing. */
    val audioOnly: Boolean = false,
    /** Position being previewed while the user drags on the car screen; null when not seeking. */
    val scrubMs: Long? = null,
    /** Upcoming tracks are in random order; turning it off restores the original order. */
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Data saver "audio only": the current video was fetched without its picture. */
    val noPicture: Boolean = false,
) {
    val current: VideoItem? get() = queue.getOrNull(index)
    /**
     * Music (or a video fetched as sound only) has no picture, so the parked-only video lock never
     * pauses it and the player shows the cover instead.
     */
    val isMusic: Boolean get() = current?.source?.music == true || noPicture
    val upNext: List<VideoItem> get() = if (index < 0) emptyList() else queue.drop(index + 1)
}

/**
 * The single player shared by the phone and the car. Whoever is showing video (phone PlayerView or
 * the car presentation) attaches its surface; the car wins while connected.
 */
@OptIn(UnstableApi::class)
class PlaybackHub(
    private val context: Context,
    private val sources: Map<SourceId, VideoSource>,
    private val http: OkHttpClient,
    driving: DrivingMonitor,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(HubState())
    val state: StateFlow<HubState> = _state.asStateFlow()
    private var resolveJob: Job? = null

    // Decoder fallback: if a device's preferred (often hardware) decoder fails to start, try the next
    // one – helps cheap head units and older phones with flaky codecs.
    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true))
        .setLoadControl(
            // Keep 30-120 s ahead (streaming-app style) so short network dips don't stall; start fast,
            // but after a stall wait for 4 s of data so it doesn't stutter straight away again.
            // On mobile data it stops at StreamPolicy.maxBufferAheadMs, so a skipped video wastes little.
            MeteredLoadControl()
        )
        .build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_NETWORK)
        addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                _state.update { it.copy(wantsToPlay = playWhenReady) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                _state.update { it.copy(buffering = playbackState == Player.STATE_BUFFERING) }
                if (playbackState == Player.STATE_READY) {
                    reconnectAttempts = 0
                    if (_state.value.error == I18n.s.reconnecting) _state.update { it.copy(error = null) }
                    prefetchNext()
                }
                // A stall after playback had started (not the initial load / a seek).
                if (playbackState == Player.STATE_BUFFERING && lastState == Player.STATE_READY && player.playWhenReady &&
                    android.os.SystemClock.elapsedRealtime() - lastSeekAt > 3_000
                ) onStall()
                lastState = playbackState
                if (playbackState == Player.STATE_ENDED) {
                    if (_state.value.repeat == RepeatMode.ONE) { player.seekTo(0); player.play() } else next()
                }
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) lastSeekAt = android.os.SystemClock.elapsedRealtime()
            }

            override fun onTracksChanged(tracks: Tracks) {
                val text = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
                _state.update { st ->
                    st.copy(
                        subtitleOptions = text.map { label(it) },
                        subtitle = text.firstOrNull { it.isSelected }?.let(::label),
                    )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Older phones / emulators / head units: retry once at a quality every device decodes.
                val decoderProblem = error.errorCode in listOf(
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                )
                val s = _state.value
                val cur = s.current
                val key = cur?.let { "${it.source}:${it.id}:${it.part}" }
                if (isNetworkErrorCode(error.errorCode) && cur != null && reconnectAttempts < 6) {
                    reconnect()
                    return
                }
                if (decoderProblem && cur != null && cur.source != SourceId.LOCAL && safeRetriedFor != key) {
                    safeRetriedFor = key
                    safeNext = true
                    resumeAtMs = player.currentPosition
                    playAt(s.queue, s.index)
                    return
                }
                _state.update { it.copy(error = "Playback error: ${error.errorCodeName}") }
            }
        })
    }

    init {
        scope.launch {
            combine(
                driving.moving,
                _state.map { it.carActive || it.inCarDevice }.distinctUntilChanged(),
            ) { moving, onCarScreen -> moving && onCarScreen }
                .distinctUntilChanged()
                .collect { locked ->
                    val audioOnly = locked && Settings.audioOnlyWhileDriving.value
                    _state.update { it.copy(drivingLocked = locked, audioOnly = audioOnly) }
                    // Audio-only: stop decoding / downloading video, keep the sound. Otherwise pause.
                    applyVideoTrack()
                    if (locked && !audioOnly && !_state.value.isMusic) player.pause()
                }
        }
        // Subtitle preferences: on/off by default and the preferred language.
        scope.launch {
            Settings.subtitles.map { it.showByDefault to Settings.subtitleLanguage() }.distinctUntilChanged()
                .collect { (show, lang) ->
                    player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !show)
                        .setPreferredTextLanguage(lang)
                        .build()
                }
        }
    }

    fun play(item: VideoItem, queue: List<VideoItem> = listOf(item)) {
        val q = if (item in queue) queue else listOf(item) + queue
        playAt(q, q.indexOf(item))
    }

    /** The queue in the order it was given, so shuffle can be turned off again. */
    private var unshuffled: List<VideoItem> = emptyList()

    /** Plays [queue] starting at [index]; earlier items stay reachable with previous(). */
    fun playQueue(queue: List<VideoItem>, index: Int = 0) {
        if (index !in queue.indices) return
        unshuffled = queue
        if (_state.value.shuffle) {
            val first = queue[index]
            playAt(listOf(first) + (queue - first).shuffled(), 0)
        } else playAt(queue, index)
    }

    /** Jumps to [index] in the current queue (keeps the shuffled order). */
    fun jumpTo(index: Int) {
        val s = _state.value
        if (index in s.queue.indices && index != s.index) playAt(s.queue, index)
    }

    /** Plays [queue] in random order, with shuffle switched on. */
    fun playShuffled(queue: List<VideoItem>) {
        if (queue.isEmpty()) return
        _state.update { it.copy(shuffle = true) }
        playQueue(queue, queue.indices.random())
    }

    /** Shuffles or restores the tracks after the current one, without interrupting it. */
    fun toggleShuffle() {
        val s = _state.value
        val on = !s.shuffle
        val cur = s.current
        if (cur == null) { _state.update { it.copy(shuffle = on) }; return }
        if (on) {
            unshuffled = s.queue
            val rest = s.queue.drop(s.index + 1).shuffled()
            _state.update { it.copy(shuffle = true, queue = s.queue.take(s.index + 1) + rest) }
        } else {
            val original = unshuffled.ifEmpty { s.queue }
            val at = original.indexOf(cur)
            _state.update {
                if (at < 0) it.copy(shuffle = false) else it.copy(shuffle = false, queue = original, index = at)
            }
        }
        prefetched = null
        prefetchNext()
    }

    /** Off → repeat all → repeat one → off. */
    fun cycleRepeat() {
        _state.update {
            it.copy(repeat = when (it.repeat) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF })
        }
        prefetchNext()
    }

    /** Position to resume from after a safe-quality retry. */
    private var resumeAtMs = 0L
    /** Next resolve uses safe quality; each video gets at most one safe retry. */
    private var safeNext = false
    private var safeRetriedFor: String? = null

    private fun playAt(queue: List<VideoItem>, index: Int, keepError: Boolean = false) {
        resolveJob?.cancel()
        if (!keepError) {
            reconnectJob?.cancel()
            reconnectAttempts = 0
            stalls.reset()
        }
        player.stop()
        resolvedQuality = null
        val sameItem = _state.value.current?.let(::keyOf) == queue.getOrNull(index)?.let(::keyOf)
        _state.update {
            it.copy(
                queue = queue, index = index, loading = true, error = if (keepError) it.error else null,
                noPicture = sameItem && it.noPicture,
            )
        }
        pokeInfo()
        resolveJob = scope.launch {
            try {
                var q = queue
                val i = index
                val source = sources.getValue(q[i].source)
                // Multi-part video (Bilibili 分P): replace it in the queue by its parts and play part 1.
                // A collection (playlist / folder) must expand; never try to play the folder itself.
                val parts = if (q[i].id.startsWith(COLLECTION_PREFIX)) {
                    withContext(Dispatchers.IO) { source.expand(q[i]) }
                        .takeUnless { it.isNullOrEmpty() } ?: throw UserFacingException(I18n.s.noVideos)
                } else runCatching { withContext(Dispatchers.IO) { source.expand(q[i]) } }.getOrNull()
                if (!parts.isNullOrEmpty()) {
                    q = q.take(i) + parts + q.drop(i + 1)
                    _state.update { it.copy(queue = q, index = i) }
                }
                val item = q[i]
                applyNetworkPolicy()
                StreamPolicy.safe = safeNext
                val useSafe = safeNext
                safeNext = false
                val ready = prefetched?.takeIf { (k, at, _) ->
                    !useSafe && k == prefetchKey(item) && android.os.SystemClock.elapsedRealtime() - at < 20 * 60_000
                }?.third
                prefetched = null
                val stream = try {
                    ready ?: withContext(Dispatchers.IO) { source.resolve(item) }
                } finally {
                    StreamPolicy.safe = false
                }
                resolvedQuality = if (item.source.music || item.source == SourceId.LOCAL) null else StreamPolicy.quality
                _state.update { it.copy(noPicture = stream.audioOnly) }
                applyVideoTrack()
                player.setMediaSource(buildMediaSource(stream, item))
                player.prepare()
                if (resumeAtMs > 0) player.seekTo(resumeAtMs)
                resumeAtMs = 0
                player.playWhenReady = playAllowed()
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Resolving failed while reconnecting (still offline / server hiccup): keep trying.
                if (keepError && reconnectAttempts < 6 && e is java.io.IOException) {
                    _state.update { it.copy(loading = false) }
                    reconnect()
                } else {
                    _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }

    private fun keyOf(item: VideoItem) = "${item.source}:${item.id}:${item.part}"

    /** A prefetched stream is only reused when it was resolved with the same quality rules. */
    private fun prefetchKey(item: VideoItem) = "${keyOf(item)}:${StreamPolicy.maxHeight}:${StreamPolicy.audioOnly}"

    /** Copies the current network type and the matching quality choice into [StreamPolicy] before resolving. */
    private fun applyNetworkPolicy() {
        StreamPolicy.metered = metered.value
        StreamPolicy.quality = Settings.quality(metered.value)
    }

    /** Quality the current video was fetched with; null for music / local files (quality doesn't apply). */
    private var resolvedQuality: VideoQuality? = null

    // --- Streaming resilience ------------------------------------------------------------------

    private var lastState = Player.STATE_IDLE
    private var lastSeekAt = 0L
    private var reconnectAttempts = 0
    private var reconnectJob: Job? = null
    private val stalls = StallTracker()
    private var prefetched: Triple<String, Long, PlayableStream>? = null
    private var prefetchJob: Job? = null

    private val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
    private val online = MutableStateFlow(true)
    /** Mobile data / hotspot (pay-per-byte) vs. Wi-Fi: picks which quality setting applies. */
    private val metered = MutableStateFlow(connectivity?.isActiveNetworkMetered ?: false)
        .also { StreamPolicy.metered = it.value }
    val onMeteredNetwork: StateFlow<Boolean> = metered.asStateFlow()

    init {
        // Watch the network: wake a pending reconnect as soon as it's back; a new network
        // (e.g. phone hotspot -> Wi-Fi) gets a fresh chance at full quality.
        runCatching {
            connectivity?.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
                private var current: android.net.Network? = null
                override fun onAvailable(network: android.net.Network) {
                    if (current != null && current != network) {
                        StreamPolicy.capHeight = QualityLadder.steps.first()
                        stalls.reset()
                    }
                    current = network
                    online.value = true
                }
                override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) {
                    metered.value = !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                    StreamPolicy.metered = metered.value
                }
                override fun onLost(network: android.net.Network) {
                    if (network == current) online.value = false
                }
            })
        }
    }

    init {
        // Quality setting changed, or Wi-Fi <-> mobile data: refetch the current video at the same second.
        scope.launch {
            combine(Settings.qualityWifi, Settings.qualityMobile, metered) { wifi, mobile, m -> if (m) mobile else wifi }
                .distinctUntilChanged()
                .collect { q ->
                    val s = _state.value
                    val was = resolvedQuality ?: return@collect
                    if (was == q || s.loading || s.current == null) return@collect
                    prefetched = null
                    resumeAtMs = player.currentPosition
                    playAt(s.queue, s.index)
                }
        }
    }

    /** Lost connection or an expired stream link: wait for the network, get a fresh link, resume at the same second. */
    private fun reconnect() {
        val s = _state.value
        if (s.current == null) return
        val position = player.currentPosition
        val attempt = reconnectAttempts++
        _state.update { it.copy(error = I18n.s.reconnecting) }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            kotlinx.coroutines.delay(retryDelayMs(attempt))
            // Offline: wait (up to 2 min) for the network instead of burning attempts.
            kotlinx.coroutines.withTimeoutOrNull(120_000) { online.first { it } }
            val now = _state.value
            if (now.current?.let(::keyOf) != s.current?.let(::keyOf)) return@launch
            resumeAtMs = position
            playAt(now.queue, now.index, keepError = true)
        }
    }

    /** Several stalls in a short time: the connection can't keep up, so drop one quality step. */
    private fun onStall() {
        val s = _state.value
        val cur = s.current ?: return
        if (s.isMusic || cur.source == SourceId.LOCAL) return
        if (!stalls.onStall(android.os.SystemClock.elapsedRealtime())) return
        applyNetworkPolicy()
        val lower = QualityLadder.lower(StreamPolicy.maxHeight) ?: return
        StreamPolicy.capHeight = lower
        resumeAtMs = player.currentPosition
        playAt(s.queue, s.index)
        _state.update { it.copy(error = I18n.s.qualityLowered(lower)) }
        scope.launch {
            kotlinx.coroutines.delay(5_000)
            _state.update { if (it.error == I18n.s.qualityLowered(lower)) it.copy(error = null) else it }
        }
    }

    /** Resolve the next item's stream while this one plays, so "next" starts almost instantly. */
    private fun prefetchNext() {
        val s = _state.value
        val next = s.queue.getOrNull(s.index + 1) ?: return
        if (next.id.startsWith(COLLECTION_PREFIX) || next.source == SourceId.LOCAL || next.source == SourceId.LOCAL_MUSIC) return
        applyNetworkPolicy()
        // Data saver: on mobile data, look the next stream up only when it is actually played.
        if (StreamPolicy.metered) return
        val key = prefetchKey(next)
        if (prefetched?.first == key || prefetchJob?.isActive == true) return
        prefetchJob = scope.launch {
            val source = sources[next.source] ?: return@launch
            // Multi-part videos get expanded at play time; only single items are prefetched.
            if (next.source == SourceId.BILIBILI || next.source == SourceId.BILI_MUSIC) {
                if (next.part == null) return@launch
            }
            val stream = runCatching { withContext(Dispatchers.IO) { source.resolve(next) } }.getOrNull() ?: return@launch
            prefetched = Triple(key, android.os.SystemClock.elapsedRealtime(), stream)
        }
    }

    /** Subtitles on (preferred language, else the first track) / off. */
    fun toggleSubtitles() {
        pokeInfo()
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        val showing = groups.any { it.isSelected }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            clearOverridesOfType(C.TRACK_TYPE_TEXT)
            if (showing || groups.isEmpty()) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            } else {
                val lang = Settings.subtitleLanguage()
                val match = groups.firstOrNull { g ->
                    g.getTrackFormat(0).language?.let { it.startsWith(lang.substringBefore('-'), ignoreCase = true) } == true
                } ?: groups.first()
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setOverrideForType(TrackSelectionOverride(match.mediaTrackGroup, 0))
            }
        }.build()
    }

    /** Off -> each available language -> off. The language choice sticks for following videos. */
    fun cycleSubtitles() {
        pokeInfo()
        val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
        if (groups.isEmpty()) return
        val current = groups.indexOfFirst { it.isSelected }
        val next = groups.getOrNull(current + 1)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().apply {
            clearOverridesOfType(C.TRACK_TYPE_TEXT)
            if (next == null) {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                setPreferredTextLanguage(null)
            } else {
                setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                setPreferredTextLanguage(next.getTrackFormat(0).language)
                setOverrideForType(TrackSelectionOverride(next.mediaTrackGroup, 0))
            }
        }.build()
    }

    private fun label(group: Tracks.Group): String {
        val f = group.getTrackFormat(0)
        return f.label ?: f.language ?: "Subtitles"
    }

    fun next() {
        val s = _state.value
        when {
            s.index + 1 < s.queue.size -> playAt(s.queue, s.index + 1)
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> playAt(s.queue, 0)
        }
    }

    fun previous() {
        val s = _state.value
        if (player.currentPosition > 5_000 || s.index <= 0) player.seekTo(0) else playAt(s.queue, s.index - 1)
    }

    fun togglePause() {
        pokeInfo()
        if (player.playWhenReady) {
            player.pause()
            return
        }
        if (!playAllowed()) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun seekBy(deltaMs: Long) {
        pokeInfo()
        val dur = player.duration
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur != C.TIME_UNSET && dur > 0) target = target.coerceAtMost(dur)
        player.seekTo(target)
    }

    fun toggleMode() {
        pokeInfo()
        _state.update { it.copy(mode = if (it.mode == DisplayMode.FULL) DisplayMode.SPLIT else DisplayMode.FULL) }
    }

    private val _infoPokedAt = MutableStateFlow(0L)
    /** Last time the car screen was touched / a control used; the title overlay shows for a while after. */
    val infoPokedAt: StateFlow<Long> = _infoPokedAt.asStateFlow()

    fun pokeInfo() {
        _infoPokedAt.value = android.os.SystemClock.elapsedRealtime()
    }

    fun setPanelOpen(open: Boolean) = _state.update { it.copy(panelOpen = open) }

    private var scrubJob: Job? = null

    /** Drag on the car screen: move the preview by [fraction] of the whole video, seek when the drag pauses. */
    fun scrubBy(fraction: Float) {
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        pokeInfo()
        val base = _state.value.scrubMs ?: player.currentPosition
        val target = (base + fraction * dur).toLong().coerceIn(0, dur)
        _state.update { it.copy(scrubMs = target) }
        scrubJob?.cancel()
        scrubJob = scope.launch {
            kotlinx.coroutines.delay(400)
            player.seekTo(target)
            kotlinx.coroutines.delay(600)
            _state.update { it.copy(scrubMs = null) }
        }
    }

    /** Tap on the progress bar: jump to that point. */
    fun seekToFraction(fraction: Float) {
        val dur = player.duration
        if (dur == C.TIME_UNSET || dur <= 0) return
        pokeInfo()
        player.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong())
    }

    fun setCarActive(active: Boolean) {
        _state.update { it.copy(carActive = active) }
        applyVideoTrack()
    }

    /** The phone app is out of sight (screen off / another app) and no car screen shows the video. */
    private var phoneBackground = false

    /**
     * Background playback on the phone: keep the sound, stop downloading and decoding the picture.
     * The picture comes back (after a short rebuffer) when the app is visible again.
     */
    fun setPhoneBackground(background: Boolean) {
        phoneBackground = background
        applyVideoTrack()
    }

    private fun applyVideoTrack() {
        val s = _state.value
        // Audio-only data saver: never pick a video track (matters for live HLS, which carries both).
        val disabled = s.drivingLocked || (phoneBackground && !s.carActive) || resolvedQuality == VideoQuality.AUDIO
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO) == disabled) return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, disabled).build()
    }

    /** Play is refused only while driving with audio-only off (the parked-only lock). */
    fun playAllowed(): Boolean = _state.value.let { !it.drivingLocked || it.audioOnly || it.isMusic }

    fun setInCarDevice(value: Boolean) = _state.update { it.copy(inCarDevice = value) }

    private fun buildMediaSource(stream: PlayableStream, item: VideoItem): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(http)
            .setUserAgent(stream.headers["User-Agent"] ?: DESKTOP_UA)
            .setDefaultRequestProperties(stream.headers - "User-Agent")
        val dataSource = DefaultDataSource.Factory(context, httpFactory)
        val subtitles = stream.subtitles.map {
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.uri))
                .setMimeType(MimeTypes.TEXT_VTT)
                .setLanguage(it.language)
                .setLabel(it.label)
                .build()
        }
        val mediaItem = MediaItem.Builder()
            .setUri(stream.videoUri)
            .setMediaId(item.id)
            .setMimeType(
                when (stream.kind) {
                    PlayableStream.Kind.HLS -> MimeTypes.APPLICATION_M3U8
                    PlayableStream.Kind.DASH -> MimeTypes.APPLICATION_MPD
                    PlayableStream.Kind.PROGRESSIVE -> null
                }
            )
            .setSubtitleConfigurations(subtitles)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(item.title).setArtist(item.author)
                    // Notification / lock screen / car media artwork.
                    .setArtworkUri(item.thumbnailUrl?.let(Uri::parse))
                    .setMediaType(if (item.source.music) MediaMetadata.MEDIA_TYPE_MUSIC else MediaMetadata.MEDIA_TYPE_VIDEO)
                    .build()
            )
            .build()
        // DefaultMediaSourceFactory handles HLS / DASH / progressive and side-loaded subtitles.
        val factory = DefaultMediaSourceFactory(dataSource)
            // Retry failed chunk downloads a few times before surfacing an error.
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6))
        val video = factory.createMediaSource(mediaItem)
        val audioUri = stream.audioUri ?: return video
        return MergingMediaSource(true, video, factory.createMediaSource(MediaItem.fromUri(audioUri)))
    }
}
