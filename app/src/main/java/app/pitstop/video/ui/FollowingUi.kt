package app.pitstop.video.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.FollowedRoom
import app.pitstop.video.core.I18n
import app.pitstop.video.core.LiveFollow
import app.pitstop.video.core.PlaylistOps
import app.pitstop.video.core.S
import app.pitstop.video.core.Settings
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.following
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Before follows existed, starring a room was the only way to keep a streamer: the first time a
 * Following list opens, the streamers of starred live rooms become follows (the stars stay).
 */
private fun importStarredRooms(graph: AppGraph) {
    if (!Settings.takeStarredLiveImport()) return
    val starred = graph.playlists.get(PlaylistOps.STARRED)?.items.orEmpty()
    graph.creators.addAll(LiveFollow.fromStarred(starred, graph.creators.creators.value))
}

/**
 * A live platform's "Following": every followed streamer, live rooms first (tap to watch), then the
 * ones that are off air (greyed). The platform is asked when the list opens and on pull-to-refresh;
 * nothing is checked in the background.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowingList(graph: AppGraph, source: VideoSource, reloadKey: Any?) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { importStarredRooms(graph) }
    val saved by graph.creators.creators.collectAsStateWithLifecycle()
    val mine = saved.filter { it.source == source.id }
    var reload by remember { mutableIntStateOf(0) }
    var refreshing by remember { mutableStateOf(false) }
    // Keeps showing the last answer while a newer one loads (after a refresh or an unfollow).
    val rooms by produceState<Result<List<FollowedRoom>>?>(null, source.id, mine, reloadKey, reload) {
        value = try {
            Result.success(source.following(graph.creators))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        refreshing = false
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = { refreshing = true; reload++ }, modifier = Modifier.fillMaxSize()) {
        val r = rooms
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.isFailure -> LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(24.dp)) {
                        Text(r.exceptionOrNull()?.message ?: S.nothingHere, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { reload++ }) { Text(S.retry) }
                    }
                }
            }
            r.getOrNull().isNullOrEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                item { Text(S.noFollowing, Modifier.padding(24.dp)) }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(r.getOrDefault(emptyList()), key = { it.creator.id }) { room ->
                    val item = room.item
                    val unfollow: @Composable () -> Unit = {
                        // Account follows are changed on the platform, not here.
                        if (!room.fromAccount) FilledTonalButton(onClick = { graph.creators.toggle(room.creator) }) { Text(S.followed) }
                    }
                    if (room.live && item != null) {
                        VideoRow(graph, item, onClick = { graph.hub.playQueue(listOf(item)) }, trailing = unfollow)
                    } else {
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { Toast.makeText(context, I18n.s.streamOffline, Toast.LENGTH_SHORT).show() }
                                .padding(start = 20.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val grey = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            Icon(painterResource(R.drawable.ic_person), null, tint = grey)
                            Column(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                                Text(
                                    room.creator.name.ifBlank { room.creator.id }, color = grey, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    if (room.fromAccount) "${S.notLive} · ${S.followedInAccount}" else S.notLive,
                                    color = grey, style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            unfollow()
                        }
                    }
                }
            }
        }
    }
}

/** Opened from a streamer's name in a live list: follow or unfollow them. */
@Composable
fun FollowDialog(graph: AppGraph, item: VideoItem, onDismiss: () -> Unit) {
    val creator = LiveFollow.creatorOf(item)
    if (creator == null) {
        onDismiss()
        return
    }
    val saved by graph.creators.creators.collectAsStateWithLifecycle()
    val isFollowed = saved.any { it.source == creator.source && it.id == creator.id }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(creator.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = { Text("${S.sourceName(item.source)} · ${if (isFollowed) S.followed else S.followStreamer}") },
        confirmButton = {
            TextButton(onClick = { graph.creators.toggle(creator); onDismiss() }) {
                Text(if (isFollowed) S.unfollowStreamer else S.followStreamer)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

/**
 * Followed streamers who are live right now, on every live platform, without the room being watched
 * ([current]). Shown under a stream in place of the queue; asked once per stream and whenever the
 * follows change.
 */
@Composable
fun rememberFollowedLive(graph: AppGraph, current: VideoItem?): State<List<VideoItem>> {
    val saved by graph.creators.creators.collectAsStateWithLifecycle()
    return produceState(emptyList(), saved, current?.source, current?.id) {
        val sources = graph.liveSources.sortedBy { if (it.id == current?.source) 0 else 1 }
        value = coroutineScope {
            sources.map { s -> async { runCatching { s.following(graph.creators) }.getOrDefault(emptyList()) } }.awaitAll()
        }.flatten().filter { it.live }.mapNotNull { it.item }
            .filterNot { it.source == current?.source && it.id == current.id }
    }
}
