package app.pitstop.video.core

import kotlinx.coroutines.CancellationException

/**
 * A followed streamer's room right now. [item] plays the room (title, cover and viewers as the
 * platform reports them); null when the platform told us nothing about it. [fromAccount]: followed in
 * the platform's own account, not in the app, so the app can't unfollow it.
 */
data class FollowedRoom(
    val creator: Creator,
    val item: VideoItem?,
    val live: Boolean,
    val fromAccount: Boolean = false,
)

/**
 * Following streamers on the live platforms. Follows are [Creator]s kept in the app's [CreatorStore]
 * (no platform account needed); the platform is only asked whether their rooms are live.
 */
object LiveFollow {
    /** The feed that lists followed streamers (first chip of every live platform). */
    const val FEED = "following_live"

    /** Live items carry "name · 1.2k watching" as their author line; this is the name alone. */
    fun streamerName(author: String?): String = author?.substringBefore(" · ")?.trim().orEmpty()

    /** The streamer behind a live [item], as something to follow; null for anything that isn't live. */
    fun creatorOf(item: VideoItem): Creator? {
        if (!item.source.live) return null
        return Creator(item.source, item.authorId ?: item.id, streamerName(item.author).ifEmpty { item.title })
    }

    /** Streamers of starred live rooms that aren't followed yet (stars were the only way to keep a room). */
    fun fromStarred(starred: List<VideoItem>, followed: List<Creator>): List<Creator> {
        val have = followed.map { it.source to it.id }.toMutableSet()
        return starred.mapNotNull(::creatorOf).filter { have.add(it.source to it.id) }
    }

    /**
     * One list from the app's follows and the account's: a streamer in both counts as followed in the
     * app (so it can be unfollowed there). Live rooms first, each group in its given order.
     */
    fun merge(local: List<FollowedRoom>, account: List<FollowedRoom>): List<FollowedRoom> {
        val ids = local.map { it.creator.id }.toSet()
        val all = local + account.filter { it.creator.id !in ids }.distinctBy { it.creator.id }
        return all.filter { it.live } + all.filterNot { it.live }
    }

    /** Rooms for [creators] in their order; a streamer the platform didn't report is listed as offline. */
    fun inOrder(creators: List<Creator>, known: Map<String, FollowedRoom>): List<FollowedRoom> =
        creators.map { known[it.id] ?: FollowedRoom(it, null, live = false) }
}

/** The streamers followed on this platform, in the app and (when signed in) in its account, live ones first. */
suspend fun VideoSource.following(store: CreatorStore): List<FollowedRoom> {
    val mine = store.forSource(id)
    val local = if (mine.isEmpty()) emptyList() else followedRooms(mine)
    // The account's list is a bonus: when it fails, the app's own follows still show.
    val account = try {
        accountFollowing()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
    return LiveFollow.merge(local, account)
}
