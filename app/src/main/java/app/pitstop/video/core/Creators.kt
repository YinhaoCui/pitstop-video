package app.pitstop.video.core

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** A channel / uploader the user follows inside the app (no platform account needed). */
data class Creator(val source: SourceId, val id: String, val name: String)

/** Feeds keyed like this list one saved creator's videos. */
const val CREATOR_FEED_PREFIX = "creator:"

/** The feed that lists the saved creators themselves (each one opens their videos). */
const val SAVED_CREATORS_FEED = "saved_creators"

/** Saved creators, persisted as JSON in app storage. Shared by the phone UI and the car screens. */
class CreatorStore(private val file: File) {
    private val _creators = MutableStateFlow(read())
    val creators: StateFlow<List<Creator>> = _creators.asStateFlow()

    fun isSaved(source: SourceId, id: String) = _creators.value.any { it.source == source && it.id == id }

    fun toggle(c: Creator) {
        val now = _creators.value
        _creators.value = if (isSaved(c.source, c.id)) now.filterNot { it.source == c.source && it.id == c.id } else now + c
        write(_creators.value)
    }

    fun forSource(source: SourceId) = _creators.value.filter { it.source == source }

    private fun read(): List<Creator> = runCatching { CreatorCodec.decode(file.readText()) }.getOrDefault(emptyList())

    private fun write(list: List<Creator>) {
        runCatching { file.writeText(CreatorCodec.encode(list)) }
    }
}

object CreatorCodec {
    fun encode(list: List<Creator>): String = JSONArray(list.map {
        JSONObject().put("source", it.source.name).put("id", it.id).put("name", it.name)
    }).toString()

    fun decode(text: String): List<Creator> {
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val source = runCatching { SourceId.valueOf(o.getString("source")) }.getOrNull() ?: return@mapNotNull null
            Creator(source, o.getString("id"), o.optString("name"))
        }
    }
}

/**
 * The source's own feeds, plus one "Saved creators" feed when any of its creators are saved (only for
 * sources that list creators). The creators are a category of their own, not a feed each.
 */
fun VideoSource.feedsWith(store: CreatorStore, title: String = I18n.s.savedCreators): List<Feed> =
    feeds() + if (supportsCreators && store.forSource(id).isNotEmpty()) listOf(Feed(SAVED_CREATORS_FEED, title)) else emptyList()

/** The saved creators of [source] as feeds (one per creator), for lists that open each one. */
fun CreatorStore.feedsFor(source: SourceId): List<Feed> = forSource(source).map { Feed(CREATOR_FEED_PREFIX + it.id, it.name) }

/** One page of a feed (saved-creator feeds are a single page). */
suspend fun VideoSource.loadFeedPage(feed: Feed, cursor: String?): FeedPage =
    if (feed.key.startsWith(CREATOR_FEED_PREFIX)) creatorPage(feed.key.removePrefix(CREATOR_FEED_PREFIX), cursor)
    else loadPage(feed, cursor)

/** Loads a feed, including saved-creator feeds. */
suspend fun VideoSource.loadFeed(feed: Feed): List<VideoItem> =
    if (feed.key.startsWith(CREATOR_FEED_PREFIX)) creatorVideos(feed.key.removePrefix(CREATOR_FEED_PREFIX)) else load(feed)
