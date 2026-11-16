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
    fun choose(urls: List<String>, overseas: Boolean): String? = ranked(urls, overseas).firstOrNull()

    /** Every usable URL, best guess first (ties keep Bilibili's order). */
    fun ranked(urls: List<String>, overseas: Boolean): List<String> =
        urls.filter { it.startsWith("http") }
            .withIndex()
            .sortedWith(compareBy<IndexedValue<String>> { rank(it.value, overseas) }.thenBy { it.index })
            .map { it.value }

    /**
     * The servers worth a speed test, best guess first: one URL per host, peer-to-peer nodes left out,
     * at most [max]. Which CDN is fast depends on the file (one that isn't cached yet at a CDN's edge can
     * come at a fraction of the video's bitrate there, seen Nov 2026 on Akamai), so no fixed order fits.
     */
    fun speedTestCandidates(urls: List<String>, overseas: Boolean, max: Int = 3): List<String> =
        ranked(urls, overseas).filterNot(::isPcdn).distinctBy(::host).take(max)
}
