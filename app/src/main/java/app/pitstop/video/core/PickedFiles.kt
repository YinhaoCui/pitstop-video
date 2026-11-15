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

    // A folder added with the system folder picker ("Add a folder"): one grant on the folder (a document
    // tree) covers every file below it, so there is no per-file limit. A folder row's id names the tree
    // and the folder's document inside it.
    private const val TREE = "tree:"
    private const val SEP = "|"

    fun treeFolderId(treeUri: String, documentId: String) = COLLECTION_PREFIX + TREE + treeUri + SEP + documentId

    /** (tree uri, document id) of a folder row made by [treeFolderId], else null. */
    fun parseTreeFolder(id: String): Pair<String, String>? {
        val head = COLLECTION_PREFIX + TREE
        if (!id.startsWith(head)) return null
        val rest = id.substring(head.length)
        val at = rest.indexOf(SEP).takeIf { it > 0 } ?: return null
        return rest.substring(0, at) to rest.substring(at + SEP.length).ifEmpty { return null }
    }
}

/** True when [error] (or anything it wraps) is Android refusing to open a content:// file. */
fun isPermissionLoss(error: Throwable?): Boolean =
    generateSequence(error) { it.cause }.take(10).any { it is SecurityException }
