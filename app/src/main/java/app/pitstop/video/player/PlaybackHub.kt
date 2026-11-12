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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
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
    val mode: DisplayMode = DisplayMode.FULL,
    /** Car is moving and the car screen is in use: video paused and hidden. */
    val drivingLocked: Boolean = false,
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

    val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
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

            override fun onPlayerError(error: PlaybackException) {
                _state.update { it.copy(error = "Playback error: ${error.errorCodeName}") }
            }
        })
    }

    init {
        scope.launch {
            combine(driving.moving, _state.map { it.carActive }.distinctUntilChanged()) { moving, car -> moving && car }
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

    private fun playAt(queue: List<VideoItem>, index: Int) {
        val item = queue[index]
        resolveJob?.cancel()
        player.stop()
        _state.update { it.copy(queue = queue, index = index, loading = true, error = null) }
        resolveJob = scope.launch {
            try {
                val stream = withContext(Dispatchers.IO) { sources.getValue(item.source).resolve(item) }
                player.setMediaSource(buildMediaSource(stream, item))
                player.prepare()
                player.playWhenReady = !_state.value.drivingLocked
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
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
        if (player.playWhenReady) {
            player.pause()
            return
        }
        if (_state.value.drivingLocked) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun seekBy(deltaMs: Long) {
        val dur = player.duration
        var target = (player.currentPosition + deltaMs).coerceAtLeast(0)
        if (dur != C.TIME_UNSET && dur > 0) target = target.coerceAtMost(dur)
        player.seekTo(target)
    }

    fun toggleMode() = _state.update {
        it.copy(mode = if (it.mode == DisplayMode.FULL) DisplayMode.SPLIT else DisplayMode.FULL)
    }

    fun setCarActive(active: Boolean) = _state.update { it.copy(carActive = active) }

    private fun buildMediaSource(stream: PlayableStream, item: VideoItem): MediaSource {
        val httpFactory = OkHttpDataSource.Factory(http)
            .setUserAgent(stream.headers["User-Agent"] ?: DESKTOP_UA)
            .setDefaultRequestProperties(stream.headers - "User-Agent")
        val dataSource = DefaultDataSource.Factory(context, httpFactory)
        val mediaItem = MediaItem.Builder()
            .setUri(stream.videoUri)
            .setMediaId(item.id)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.author).build())
            .build()
        return when (stream.kind) {
            PlayableStream.Kind.HLS -> HlsMediaSource.Factory(dataSource).createMediaSource(mediaItem)
            PlayableStream.Kind.DASH -> DashMediaSource.Factory(dataSource).createMediaSource(mediaItem)
            PlayableStream.Kind.PROGRESSIVE -> {
                val factory = ProgressiveMediaSource.Factory(dataSource)
                val video = factory.createMediaSource(mediaItem)
                val audioUri = stream.audioUri ?: return video
                MergingMediaSource(true, video, factory.createMediaSource(MediaItem.fromUri(audioUri)))
            }
        }
    }
}
