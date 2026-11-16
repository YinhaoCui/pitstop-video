package app.pitstop.video.player

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import app.pitstop.video.graph
import app.pitstop.video.ui.MainActivity

/**
 * Keeps playback alive when the phone screen is off or the user switches apps. Media3 turns this
 * into a foreground service with a media notification while something plays, which also gives
 * lock-screen, Bluetooth / headset button and picture-in-picture controls.
 *
 * The session wraps [PlaybackHub]'s single player; the hub keeps owning (and never releases) it.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val hub = graph.hub
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val player = HubPlayer(hub)
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .build()
        // Song <-> video changes which buttons are offered (see HubPlayer).
        scope.launch {
            hub.state.map { it.isMusic }.distinctUntilChanged().drop(1).collect { player.commandsChanged() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // Only the session is ours; the player belongs to PlaybackHub.
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    companion object {
        /** Start (or keep) the service; harmless if already running or if the OS refuses. */
        fun start(context: Context) {
            runCatching { context.startService(Intent(context, PlaybackService::class.java)) }
        }
    }
}

/**
 * The hub plays one item at a time and keeps the queue itself, so next / previous from the
 * notification, lock screen or headset are routed to the hub. Play respects the driving lock.
 */
@OptIn(UnstableApi::class)
private class HubPlayer(private val hub: PlaybackHub) : ForwardingPlayer(hub.player) {
    private val extra = Player.Commands.Builder()
        .addAll(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        ).build()

    /**
     * Android Auto's media card (and some notifications) show ±10 s buttons instead of previous / next
     * whenever seeking back / forward is offered. For songs, skipping tracks is what those buttons should
     * do, so the 10 s seeks are offered only for videos.
     */
    private fun hidden(command: Int) = hub.state.value.isMusic &&
        (command == Player.COMMAND_SEEK_BACK || command == Player.COMMAND_SEEK_FORWARD)

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(extra).apply {
            if (hub.state.value.isMusic) removeAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD)
        }.build()

    override fun isCommandAvailable(command: Int): Boolean =
        !hidden(command) && (extra.contains(command) || super.isCommandAvailable(command))

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        listeners += listener
        super.addListener(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners -= listener
        super.removeListener(listener)
    }

    /** Tells the session (and so Android Auto / the notification) to read the buttons again. */
    fun commandsChanged() {
        val commands = availableCommands
        listeners.forEach { it.onAvailableCommandsChanged(commands) }
    }

    override fun hasNextMediaItem(): Boolean = hub.state.value.upNext.isNotEmpty()
    override fun hasPreviousMediaItem(): Boolean = true

    override fun seekToNext() = hub.next()
    override fun seekToNextMediaItem() = hub.next()
    override fun seekToPrevious() = hub.previous()
    override fun seekToPreviousMediaItem() = hub.previous()

    override fun play() {
        if (hub.playAllowed()) super.play()
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (!playWhenReady || hub.playAllowed()) super.setPlayWhenReady(playWhenReady)
    }
}
