package app.pitstop.video.core

/**
 * Counts mid-playback stalls (rebuffering after the video was already playing). Several stalls in
 * a short window mean the connection can't sustain the current quality, so [onStall] says "step down".
 */
class StallTracker(private val windowMs: Long = 90_000, private val limit: Int = 3) {
    private val stalls = ArrayDeque<Long>()

    /** Records a stall at [nowMs]; true when quality should drop (the history is then cleared). */
    fun onStall(nowMs: Long): Boolean {
        stalls.addLast(nowMs)
        while (stalls.isNotEmpty() && nowMs - stalls.first() > windowMs) stalls.removeFirst()
        if (stalls.size < limit) return false
        stalls.clear()
        return true
    }

    fun reset() = stalls.clear()
}

object QualityLadder {
    val steps = listOf(1080, 720, 480, 360)

    /** The next lower height cap, or null when already at the bottom. */
    fun lower(current: Int): Int? = steps.firstOrNull { it < current }
}

/** Exponential backoff for reconnect attempts: 1 s, 2 s, 4 s, 8 s, then 16 s. */
fun retryDelayMs(attempt: Int): Long = (1_000L shl attempt.coerceIn(0, 4))

/** Network-type failures worth a reconnect (rather than a hard error): ExoPlayer error codes 2000-2008. */
fun isNetworkErrorCode(code: Int): Boolean = code in 2000..2008
