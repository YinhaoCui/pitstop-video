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
import app.pitstop.video.core.DESKTOP_UA
import app.pitstop.video.core.PlayableStream
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
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
) {
    val current: VideoItem? get() = queue.getOrNull(index)
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
    val player: ExoPlayer = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setEnableDecoderFallback(true)).build().apply {
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
                if (playbackState == Player.STATE_ENDED) next()
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
                    _state.update { it.copy(drivingLocked = locked) }
                    if (locked) player.pause()
                }
        }
    }

    fun play(item: VideoItem, queue: List<VideoItem> = listOf(item)) {
        val q = if (item in queue) queue else listOf(item) + queue
        playAt(q, q.indexOf(item))
    }

    /** Plays [queue] starting at [index]; earlier items stay reachable with previous(). */
    fun playQueue(queue: List<VideoItem>, index: Int = 0) {
        if (index in queue.indices) playAt(queue, index)
    }

    /** Position to resume from after a safe-quality retry. */
    private var resumeAtMs = 0L
    /** Next resolve uses safe quality; each video gets at most one safe retry. */
    private var safeNext = false
    private var safeRetriedFor: String? = null

    private fun playAt(queue: List<VideoItem>, index: Int) {
        resolveJob?.cancel()
        player.stop()
        _state.update { it.copy(queue = queue, index = index, loading = true, error = null) }
        pokeInfo()
        resolveJob = scope.launch {
            try {
                var q = queue
                val i = index
                val source = sources.getValue(q[i].source)
                // Multi-part video (Bilibili 分P): replace it in the queue by its parts and play part 1.
                val parts = runCatching { withContext(Dispatchers.IO) { source.expand(q[i]) } }.getOrNull()
                if (!parts.isNullOrEmpty()) {
                    q = q.take(i) + parts + q.drop(i + 1)
                    _state.update { it.copy(queue = q, index = i) }
                }
                val item = q[i]
                StreamPolicy.safe = safeNext
                safeNext = false
                val stream = try {
                    withContext(Dispatchers.IO) { source.resolve(item) }
                } finally {
                    StreamPolicy.safe = false
                }
                player.setMediaSource(buildMediaSource(stream, item))
                player.prepare()
                if (resumeAtMs > 0) player.seekTo(resumeAtMs)
                resumeAtMs = 0
                player.playWhenReady = !_state.value.drivingLocked
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
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
        if (s.index + 1 < s.queue.size) playAt(s.queue, s.index + 1)
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
        if (_state.value.drivingLocked) return
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

    fun setCarActive(active: Boolean) = _state.update { it.copy(carActive = active) }

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
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.author).build())
            .build()
        // DefaultMediaSourceFactory handles HLS / DASH / progressive and side-loaded subtitles.
        val factory = DefaultMediaSourceFactory(dataSource)
        val video = factory.createMediaSource(mediaItem)
        val audioUri = stream.audioUri ?: return video
        return MergingMediaSource(true, video, factory.createMediaSource(MediaItem.fromUri(audioUri)))
    }
}
