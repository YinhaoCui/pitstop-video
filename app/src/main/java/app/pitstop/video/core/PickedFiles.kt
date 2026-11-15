package app.pitstop.video.core

/**
 * Files added with the system file picker ("Add from files") are read through a persisted URI grant
 * each. Android keeps at most [LIMIT] of them per app and silently drops the oldest when more are
 * taken, so a long list of picked songs ends up with some that can no longer be opened (the player
 * then fails with a SecurityException). Pure, so it's unit-tested.
 */
object PickedFiles {
    /** Persisted URI grants Android keeps per app (Android 11+). */
    const val LIMIT = 512

    /** The picked [uris] (in list order) that have no read grant among [granted]. */
    fun withoutAccess(uris: List<String>, granted: Set<String>): List<String> = uris.filter { it !in granted }
}

/** True when [error] (or anything it wraps) is Android refusing to open a content:// file. */
fun isPermissionLoss(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(10).any { it is SecurityException }
