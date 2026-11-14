package app.pitstop.video.core

/**
 * Whether a cloud file is a song. Drive often labels audio by container rather than content
 * (`.m4a` as `video/mp4`, `.ogg` as `application/ogg`, `.opus` as `application/octet-stream`),
 * so the file extension counts as well as the MIME type. Pure JVM, unit-tested.
 */
object AudioFiles {
    private val EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "aiff", "aif", "alac", "ape", "mka", "amr")

    fun isAudio(name: String, mimeType: String): Boolean =
        mimeType.startsWith("audio/") || name.substringAfterLast('.', "").lowercase() in EXTENSIONS
}
