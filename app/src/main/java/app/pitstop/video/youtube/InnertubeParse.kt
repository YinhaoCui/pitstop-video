package app.pitstop.video.youtube

import app.pitstop.video.core.COLLECTION_PREFIX
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Parsing for YouTube's own web API (`youtubei/v1/browse`, the WEB client) as used by the signed-in
 * feeds, plus the channel RSS feed used as the Subscriptions fallback. Pure JVM, unit-tested.
 *
 * YouTube reshapes these responses often, so nothing here follows a fixed path from the root: the
 * walker finds every known item renderer anywhere in the tree (old `videoRenderer` family and the
 * newer `lockupViewModel`), in document order.
 */
object InnertubeParse {

    /** Videos anywhere in a browse / continuation response, de-duplicated, in order. */
    fun videos(root: JSONObject, source: SourceId): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        walk(root) { key, o ->
            val item = when (key) {
                "videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "playlistVideoRenderer" -> classicVideo(o, source)
                "lockupViewModel" -> if (o.optString("contentType") == "LOCKUP_CONTENT_TYPE_VIDEO") lockupVideo(o, source) else null
                else -> null
            }
            if (item != null) out.putIfAbsent(item.id, item)
            item != null
        }
        return out.values.toList()
    }

    /** Playlists (as collection rows) anywhere in a response, e.g. the Playlists page. */
    fun playlists(root: JSONObject, source: SourceId, countLabel: (Int) -> String): List<VideoItem> {
        val out = LinkedHashMap<String, VideoItem>()
        walk(root) { key, o ->
            val item = when (key) {
                "gridPlaylistRenderer", "playlistRenderer" -> {
                    val id = o.optString("playlistId")
                    if (id.isEmpty()) null else VideoItem(
                        source = source,
                        id = COLLECTION_PREFIX + id,
                        title = text(o.optJSONObject("title")),
                        author = (o.optString("videoCount").toIntOrNull()
                            ?: digits(text(o.optJSONObject("videoCountText")))
                            ?: digits(text(o.optJSONObject("videoCountShortText"))))?.let(countLabel),
                        thumbnailUrl = bestThumb(o.optJSONObject("thumbnail"))
                            ?: o.optJSONArray("thumbnails")?.optJSONObject(0)?.let(::bestThumb),
                    )
                }
                "lockupViewModel" -> if (o.optString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") lockupPlaylist(o, source, countLabel) else null
                else -> null
            }
            if (item != null) out.putIfAbsent(item.id, item)
            item != null
        }
        return out.values.toList()
    }

    /** Channels (id to name) on the subscriptions management page (`FEchannels`). */
    fun channels(root: JSONObject): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        walk(root) { key, o ->
            if (key != "channelRenderer" && key != "gridChannelRenderer") return@walk false
            val id = o.optString("channelId")
            if (id.startsWith("UC")) out.putIfAbsent(id, text(o.optJSONObject("title")))
            true
        }
        return out.map { it.key to it.value }
    }

    /** The token for the next page, from the list's trailing `continuationItemRenderer`. */
    fun continuation(root: JSONObject): String? {
        var token: String? = null
        walk(root) { key, o ->
            if (token != null) return@walk true
            if (key != "continuationItemRenderer") return@walk false
            token = findToken(o)
            true
        }
        return token
    }

    /** True when YouTube answered as if signed out (cookies expired or revoked). */
    fun loggedOut(root: JSONObject): Boolean =
        root.optJSONObject("responseContext")?.optJSONObject("mainAppWebResponseContext")?.optBoolean("loggedOut", false) == true

    /**
     * The `Authorization` value YouTube's web player sends with signed-in API calls:
     * `SAPISIDHASH <time>_<sha1("<time> <SAPISID> <origin>")>`.
     */
    fun sapisidHash(sapisid: String, origin: String, epochSeconds: Long): String {
        val digest = MessageDigest.getInstance("SHA-1").digest("$epochSeconds $sapisid $origin".toByteArray())
        return "SAPISIDHASH ${epochSeconds}_" + digest.joinToString("") { "%02x".format(it) }
    }

    /** One entry of a channel's public RSS feed: [published] is ISO-8601 (sorts as text). */
    data class RssEntry(val published: String, val item: VideoItem)

    /** Entries of `https://www.youtube.com/feeds/videos.xml?channel_id=...` (latest ~15 uploads). */
    fun rss(xml: String, source: SourceId): List<RssEntry> =
        ENTRY.findAll(xml).mapNotNull { m ->
            val e = m.groupValues[1]
            val id = tag(e, "yt:videoId") ?: return@mapNotNull null
            val channel = tag(e, "yt:channelId")
            RssEntry(
                published = tag(e, "published").orEmpty(),
                item = VideoItem(
                    source = source,
                    id = watchUrl(id),
                    title = unescape(tag(e, "title").orEmpty()),
                    author = tag(AUTHOR.find(e)?.groupValues?.get(1).orEmpty(), "name")?.let(::unescape),
                    thumbnailUrl = "https://i.ytimg.com/vi/$id/mqdefault.jpg",
                    authorId = channel?.let(::channelUrl),
                ),
            )
        }.toList()

    // ---- renderers ----

    private fun classicVideo(o: JSONObject, source: SourceId): VideoItem? {
        val id = o.optString("videoId").ifEmpty { return null }
        // Unplayable rows in a playlist (deleted / private) have no length and isPlayable=false.
        if (o.has("isPlayable") && !o.optBoolean("isPlayable", true)) return null
        val byline = o.optJSONObject("ownerText") ?: o.optJSONObject("shortBylineText") ?: o.optJSONObject("longBylineText")
        return VideoItem(
            source = source,
            id = watchUrl(id),
            title = text(o.optJSONObject("title")),
            author = byline?.let(::text)?.ifEmpty { null }?.removeSuffix(" - Topic"),
            thumbnailUrl = bestThumb(o.optJSONObject("thumbnail")),
            durationSec = o.optString("lengthSeconds").toLongOrNull() ?: clock(text(o.optJSONObject("lengthText"))),
            authorId = byline?.let(::firstChannelId)?.let(::channelUrl),
        )
    }

    private fun lockupVideo(o: JSONObject, source: SourceId): VideoItem? {
        val id = o.optString("contentId").ifEmpty { return null }
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val parts = metadataParts(meta)
        val image = o.optJSONObject("contentImage")?.optJSONObject("thumbnailViewModel")
        return VideoItem(
            source = source,
            id = watchUrl(id),
            title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
            author = parts.firstOrNull()?.removeSuffix(" - Topic"),
            thumbnailUrl = image?.let(::viewModelThumb),
            durationSec = image?.let { badgeTexts(it).firstNotNullOfOrNull(::clock) },
            authorId = meta?.let(::firstChannelId)?.let(::channelUrl),
        )
    }

    private fun lockupPlaylist(o: JSONObject, source: SourceId, countLabel: (Int) -> String): VideoItem? {
        val id = o.optString("contentId").ifEmpty { return null }
        val meta = o.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
        val image = o.optJSONObject("contentImage")?.let {
            it.optJSONObject("collectionThumbnailViewModel")?.optJSONObject("primaryThumbnail")?.optJSONObject("thumbnailViewModel")
                ?: it.optJSONObject("thumbnailViewModel")
        }
        return VideoItem(
            source = source,
            id = COLLECTION_PREFIX + id,
            title = meta?.optJSONObject("title")?.optString("content").orEmpty(),
            author = image?.let { badgeTexts(it).firstNotNullOfOrNull(::digits) }?.let(countLabel),
            thumbnailUrl = image?.let(::viewModelThumb),
        )
    }

    // ---- helpers ----

    /**
     * Depth-first walk calling [visit] for every (key, object) pair; when it returns true the object
     * was consumed and its children are not walked.
     */
    private fun walk(node: Any?, visit: (String, JSONObject) -> Boolean) {
        when (node) {
            is JSONObject -> for (key in node.keys()) {
                val child = node.opt(key)
                if (child is JSONObject && visit(key, child)) continue
                walk(child, visit)
            }
            is JSONArray -> for (i in 0 until node.length()) walk(node.opt(i), visit)
        }
    }

    private fun findToken(node: Any?): String? = when (node) {
        is JSONObject -> node.optJSONObject("continuationCommand")?.optString("token")?.ifEmpty { null }
            ?: node.keys().asSequence().firstNotNullOfOrNull { findToken(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findToken(node.opt(it)) }
        else -> null
    }

    /** First `browseId` that is a channel (UC...) anywhere under [node]. */
    private fun firstChannelId(node: Any?): String? = when (node) {
        is JSONObject -> node.optString("browseId").takeIf { it.startsWith("UC") }
            ?: node.keys().asSequence().firstNotNullOfOrNull { firstChannelId(node.opt(it)) }
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { firstChannelId(node.opt(it)) }
        else -> null
    }

    /** `{simpleText}`, `{runs:[{text}]}` or a view model's `{content}`. */
    fun text(o: JSONObject?): String {
        if (o == null) return ""
        o.optString("simpleText").ifEmpty { null }?.let { return it }
        o.optString("content").ifEmpty { null }?.let { return it }
        val runs = o.optJSONArray("runs") ?: return ""
        return (0 until runs.length()).joinToString("") { runs.optJSONObject(it)?.optString("text").orEmpty() }
    }

    private fun metadataParts(meta: JSONObject?): List<String> {
        val rows = meta?.optJSONObject("metadata")?.optJSONObject("contentMetadataViewModel")?.optJSONArray("metadataRows")
            ?: return emptyList()
        return (0 until rows.length()).flatMap { r ->
            val parts = rows.optJSONObject(r)?.optJSONArray("metadataParts") ?: return@flatMap emptyList()
            (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optJSONObject("text")?.optString("content")?.ifEmpty { null } }
        }
    }

    private fun badgeTexts(thumbnailViewModel: JSONObject): List<String> {
        val out = mutableListOf<String>()
        fun collect(node: Any?) {
            when (node) {
                is JSONObject -> {
                    node.optJSONObject("thumbnailBadgeViewModel")?.optString("text")?.ifEmpty { null }?.let(out::add)
                    node.keys().forEach { collect(node.opt(it)) }
                }
                is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i))
            }
        }
        collect(thumbnailViewModel.opt("overlays"))
        return out
    }

    private fun viewModelThumb(thumbnailViewModel: JSONObject): String? =
        thumbnailViewModel.optJSONObject("image")?.optJSONArray("sources")?.let(::largest)

    private fun bestThumb(thumbnail: JSONObject?): String? = thumbnail?.optJSONArray("thumbnails")?.let(::largest)

    /** Widest image of a `[{url,width,height}]` list (last when no widths), made absolute. */
    private fun largest(list: JSONArray): String? {
        val all = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optString("url").isNotEmpty() }
        val best = all.maxByOrNull { it.optInt("width") } ?: return null
        val url = best.optString("url")
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** "1:02:03" / "4:05" -> seconds; null when it isn't a clock. */
    fun clock(s: String): Long? {
        val parts = s.trim().split(':')
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.toLongOrNull() ?: return null }
        return nums.fold(0L) { acc, n -> acc * 60 + n }
    }

    /** First number in a label like "1,234 videos" / "12 个视频". */
    private fun digits(s: String): Int? = Regex("""\d[\d,.\s]*""").find(s)?.value?.filter(Char::isDigit)?.toIntOrNull()

    fun watchUrl(videoId: String) = "https://www.youtube.com/watch?v=$videoId"
    fun channelUrl(channelId: String) = "https://www.youtube.com/channel/$channelId"

    private val ENTRY = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)
    private val AUTHOR = Regex("<author>(.*?)</author>", RegexOption.DOT_MATCHES_ALL)
    private fun tag(xml: String, name: String): String? =
        Regex("<${Regex.escape(name)}>(.*?)</${Regex.escape(name)}>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")
}
