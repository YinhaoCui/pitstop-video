package app.pitstop.video.bilibili

/**
 * Picks which of the servers Bilibili offers for a stream (baseUrl + backupUrls) to use.
 * Bilibili's first answer is often a peer-to-peer node (PCDN / "mcdn"), which stalls badly,
 * especially outside mainland China. URLs are never rewritten: each is signed for its own host.
 */
object BiliCdn {
    private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')

    private fun isPcdn(url: String): Boolean {
        val authority = url.substringAfter("://").substringBefore('/')
        val h = host(url)
        return "mcdn" in h || "szbdyd" in h || h.firstOrNull()?.isDigit() == true || ':' in authority
    }

    /** Lower is better. */
    private fun rank(url: String, overseas: Boolean): Int {
        val h = host(url)
        return when {
            isPcdn(url) -> 3
            "akamaized" in h -> if (overseas) 0 else 2
            "upos" in h -> 1
            else -> 2
        }
    }

    /**
     * @param urls the track's baseUrl followed by its backupUrls
     * @param overseas true when the user is outside mainland China
     */
    fun choose(urls: List<String>, overseas: Boolean): String? =
        urls.filter { it.startsWith("http") }
            .withIndex()
            .minWithOrNull(compareBy<IndexedValue<String>> { rank(it.value, overseas) }.thenBy { it.index })
            ?.value
}
