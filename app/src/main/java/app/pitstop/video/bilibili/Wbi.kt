package app.pitstop.video.bilibili

import java.security.MessageDigest

/** Bilibili "WBI" request signing (w_rid + wts), required by search / playurl / recommend endpoints. */
object Wbi {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
        33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
        61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
        36, 20, 34, 44, 52,
    )

    /** img_key / sub_key are the file names (without extension) of wbi_img.img_url / sub_url from /nav. */
    fun mixinKey(imgKey: String, subKey: String): String {
        val raw = imgKey + subKey
        return MIXIN_KEY_ENC_TAB.map { raw[it] }.joinToString("").take(32)
    }

    fun keyFromUrl(url: String): String = url.substringAfterLast('/').substringBefore('.')

    /** Returns the full signed query string (already percent-encoded), including wts and w_rid. */
    fun sign(params: Map<String, String>, mixinKey: String, wts: Long): String {
        val all = params + ("wts" to wts.toString())
        val query = all.toSortedMap().entries.joinToString("&") { (k, v) ->
            encode(k) + "=" + encode(v.filterNot { it in "!'()*" })
        }
        return "$query&w_rid=${md5(query + mixinKey)}"
    }

    /** Same as JavaScript encodeURIComponent for the characters that survive the filter above. */
    internal fun encode(s: String): String = buildString {
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.~") append(ch)
            else append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

object BiliText {
    private val TAG = Regex("<[^>]+>")

    /** Search titles contain <em class="keyword"> highlights and HTML entities. */
    fun clean(s: String): String = s.replace(TAG, "")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&amp;", "&")

    /** Covers come as "//i0.hdslb.com/..." or http; request a small https thumbnail. */
    fun thumb(url: String?, width: Int = 320): String? {
        if (url.isNullOrBlank()) return null
        val abs = when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http://") -> "https://" + url.removePrefix("http://")
            else -> url
        }
        return if ("hdslb.com" in abs && '@' !in abs) "$abs@${width}w_${width * 9 / 16}h_1c.jpg" else abs
    }
}
