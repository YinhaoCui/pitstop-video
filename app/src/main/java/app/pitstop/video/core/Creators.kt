package app.pitstop.video.core

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    /** Adds the [list] entries that aren't saved yet. */
    fun addAll(list: List<Creator>) {
        val fresh = list.filterNot { isSaved(it.source, it.id) }.distinctBy { it.source to it.id }
        if (fresh.isEmpty()) return
        _creators.value = _creators.value + fresh
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

/** The feed that mixes the latest uploads of every followed creator of a platform. */
const val FOLLOWED_WORKS_FEED = "followed_works"

/**
 * The feeds shown after Categories: what the app follows first, then the platform's own feeds.
 * Live platforms: "Following" ([LiveFollow.FEED]). Platforms with creators: the followed creators'
 * latest uploads ([FOLLOWED_WORKS_FEED]) and the followed creators themselves ([SAVED_CREATORS_FEED]).
 */
fun VideoSource.feedsWith(
    store: CreatorStore,
    title: String = I18n.s.savedCreators,
    followingTitle: String = I18n.s.followingStreamers,
    worksTitle: String = if (id.music) I18n.s.followedMusic else I18n.s.followedVideos,
): List<Feed> = when {
    supportsFollowing -> listOf(Feed(LiveFollow.FEED, followingTitle))
    supportsCreators -> listOf(Feed(FOLLOWED_WORKS_FEED, worksTitle), Feed(SAVED_CREATORS_FEED, title))
    else -> emptyList()
} + feeds()

/** Feeds a platform opens on: its own, not the follow lists (they ask the platform once per creator). */
fun isFollowFeed(key: String?) = key == FOLLOWED_WORKS_FEED || key == SAVED_CREATORS_FEED || key == LiveFollow.FEED

/**
 * Who following [item] follows: a live room's streamer, or a video's / song's creator on platforms
 * that list creators. Null for anything without one (local files, radio, Drive).
 */
fun VideoSource.followTarget(item: VideoItem): Creator? = when {
    supportsFollowing -> LiveFollow.creatorOf(item)
    supportsCreators && item.authorId != null -> Creator(item.source, item.authorId, item.author?.trim().orEmpty().ifEmpty { item.title })
    else -> null
}

/** Follows for the creators of [items] that aren't followed yet (stars used to be the way to keep things). */
fun creatorsOf(items: List<VideoItem>, followed: List<Creator>, target: (VideoItem) -> Creator?): List<Creator> {
    val have = followed.map { it.source to it.id }.toMutableSet()
    return items.mapNotNull(target).filter { have.add(it.source to it.id) }
}

object FollowedWorks {
    /** Uploads taken from each creator, and the most shown. */
    const val PER_CREATOR = 15
    const val MAX = 200

    /**
     * One list from several creators' newest-first uploads: the newest of each, then the second of
     * each, ... (sources give no common date to sort by). Repeats are dropped.
     */
    fun interleave(lists: List<List<VideoItem>>, max: Int = MAX): List<VideoItem> {
        val out = mutableListOf<VideoItem>()
        val seen = mutableSetOf<Pair<SourceId, String>>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) {
            val item = l.getOrNull(i) ?: continue
            if (out.size < max && seen.add(item.source to item.id)) out += item
        }
        return out
    }
}

/** The latest uploads of everyone followed on this platform, a few creators at a time. */
suspend fun VideoSource.followedWorks(store: CreatorStore): List<VideoItem> = coroutineScope {
    val gate = Semaphore(4)
    val lists = store.forSource(id).map { c ->
        async {
            gate.withPermit {
                try {
                    creatorPage(c.id, null).items.take(FollowedWorks.PER_CREATOR)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }
    }.awaitAll()
    FollowedWorks.interleave(lists)
}

/** The saved creators of [source] as feeds (one per creator), for lists that open each one. */
fun CreatorStore.feedsFor(source: SourceId): List<Feed> = forSource(source).map { Feed(CREATOR_FEED_PREFIX + it.id, it.name) }

/** One page of a feed (saved-creator feeds are a single page). */
suspend fun VideoSource.loadFeedPage(feed: Feed, cursor: String?): FeedPage =
    if (feed.key.startsWith(CREATOR_FEED_PREFIX)) creatorPage(feed.key.removePrefix(CREATOR_FEED_PREFIX), cursor)
    else loadPage(feed, cursor)

/** Loads a feed, including saved-creator feeds. */
suspend fun VideoSource.loadFeed(feed: Feed): List<VideoItem> =
    if (feed.key.startsWith(CREATOR_FEED_PREFIX)) creatorVideos(feed.key.removePrefix(CREATOR_FEED_PREFIX)) else load(feed)
