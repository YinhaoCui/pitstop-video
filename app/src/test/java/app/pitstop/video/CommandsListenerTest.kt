package app.pitstop.video

import androidx.media3.common.Player
import app.pitstop.video.player.CommandsListener
import org.junit.Assert.assertEquals
import org.junit.Test

class CommandsListenerTest {
    /** Every listener method must be passed on, or the media session stops hearing about playback. */
    @Test fun forwardsEveryListenerMethod() {
        val missing = Player.Listener::class.java.methods.filter { m ->
            runCatching { CommandsListener::class.java.getDeclaredMethod(m.name, *m.parameterTypes) }.isFailure
        }.map { it.name }
        assertEquals(emptyList<String>(), missing)
    }
}
