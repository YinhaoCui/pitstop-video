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

    override fun onCreate() {
        super.onCreate()
        val hub = graph.hub
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, HubPlayer(hub))
            .setSessionActivity(open)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // Only the session is ours; the player belongs to PlaybackHub.
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
     * Android Auto's media card (and some notifications) show -10 s / +10 s instead of previous / next
     * whenever seeking back / forward is offered, so it isn't: those buttons switch songs and videos.
     * Seeking within an item stays on the phone and car player screens.
     */
    private val hidden = Player.Commands.Builder().addAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build()

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().addAll(extra)
            .removeAll(Player.COMMAND_SEEK_BACK, Player.COMMAND_SEEK_FORWARD).build()

    override fun isCommandAvailable(command: Int): Boolean =
        !hidden.contains(command) && (extra.contains(command) || super.isCommandAvailable(command))

    /**
     * The session learns about command changes from player events, and ForwardingPlayer passes on the
     * inner player's commands there (seek ±10 s, no next / previous for a single item), which made the
     * car's media card show ±10 s anyway. Listeners get this player's commands instead.
     */
    private val wrapped = HashMap<Player.Listener, Player.Listener>()

    override fun addListener(listener: Player.Listener) {
        val w = object : Player.Listener by listener {
            override fun onAvailableCommandsChanged(availableCommands: Player.Commands) =
                listener.onAvailableCommandsChanged(this@HubPlayer.availableCommands)
        }
        wrapped[listener] = w
        super.addListener(w)
    }

    override fun removeListener(listener: Player.Listener) {
        wrapped.remove(listener)?.let { super.removeListener(it) }
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
