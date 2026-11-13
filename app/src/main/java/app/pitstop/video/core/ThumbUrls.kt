package app.pitstop.video.core

/**
 * Rewrites thumbnail URLs to a smaller size the image server already offers (data saver on mobile
 * data). Unknown hosts are returned unchanged. Pure string logic so it is unit-testable.
 */
object ThumbUrls {
    private val ytimg = Regex("""^(https?://i\d?\.ytimg\.com/vi(?:_webp)?/[^/]+/)(?:hq720|maxresdefault|sddefault|hqdefault)\.(jpg|webp)(?:\?.*)?$""")
    private val googleSize = Regex("""=w\d+-h\d+""")

    fun smaller(url: String, width: Int): String {
        val w = width.coerceAtLeast(64)
        // YouTube video thumbnails: mqdefault is 320x180 (hqdefault is 480x360, maxres 1280x720).
        if (w <= 320) ytimg.matchEntire(url)?.let { m -> return "${m.groupValues[1]}mqdefault.${m.groupValues[2]}" }
        // YouTube Music artwork (googleusercontent): the size is part of the URL.
        if (url.contains("googleusercontent.com") && googleSize.containsMatchIn(url)) {
            return googleSize.replace(url, "=w$w-h$w")
        }
        // Bilibili covers (hdslb.com) take an "@<width>w" resize suffix.
        if (url.contains("hdslb.com/") && !url.contains('@') && !url.contains('?')) return "$url@${w}w.jpg"
        // NetEase covers (music.126.net) take "?param=<w>y<h>".
        if (url.contains("music.126.net/") && !url.contains('?')) return "$url?param=${w}y$w"
        return url
    }
}
