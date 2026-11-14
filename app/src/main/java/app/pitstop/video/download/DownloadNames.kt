package app.pitstop.video.download

/** Pure helpers for downloads (file names, types, ranges), kept free of Android types for unit tests. */
object DownloadNames {
    /** "Artist - Title" (or just the title), without characters file systems reject, at most 120 chars. */
    fun baseName(title: String, author: String?): String {
        val raw = if (!author.isNullOrBlank() && !title.contains(author, ignoreCase = true)) "$author - $title" else title
        val clean = raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_")
            .replace(Regex("""\s+"""), " ").trim().trim('.')
        return clean.take(120).trim().ifEmpty { "Pitstop" }
    }

    /** File extension for a Content-Type (parameters ignored); null when unknown. */
    fun extensionFor(contentType: String?): String? = when (contentType?.substringBefore(';')?.trim()?.lowercase()) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/ogg", "application/ogg" -> "ogg"
        "audio/opus" -> "opus"
        "audio/webm" -> "weba"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        "video/x-flv" -> "flv"
        "video/quicktime" -> "mov"
        else -> null
    }

    /** Extension from a URL path ("…/song.flac?x=1" -> "flac"), only for short known-looking ones. */
    fun extensionFromUrl(url: String): String? {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext.takeIf { it in KNOWN }
    }

    /** MIME type for a saved file's extension. */
    fun mimeFor(ext: String): String = when (ext) {
        "mp3" -> "audio/mpeg"; "m4a" -> "audio/mp4"; "flac" -> "audio/flac"; "ogg" -> "audio/ogg"
        "opus" -> "audio/opus"; "aac" -> "audio/aac"; "weba" -> "audio/webm"; "wav" -> "audio/wav"
        "mp4" -> "video/mp4"; "webm" -> "video/webm"; "flv" -> "video/x-flv"; "mov" -> "video/quicktime"
        else -> "application/octet-stream"
    }

    fun isAudio(ext: String) = mimeFor(ext).startsWith("audio/")

    /** Total size from a `Content-Range: bytes 0-99/1234` header; null when absent or "*". */
    fun totalFromContentRange(header: String?): Long? =
        header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()

    private val KNOWN = setOf("mp3", "m4a", "flac", "ogg", "opus", "wav", "aac", "mp4", "webm", "flv", "mov")
}
