package app.pitstop.video.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.ListView
import app.pitstop.video.core.S
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import kotlinx.coroutines.CancellationException

/**
 * Opens folder rows in place: the folder's subfolders and tracks replace the list, with a back
 * row naming the folder. Tapping a track plays the folder's tracks from there.
 */
@Composable
fun FolderPage(
    graph: AppGraph,
    source: VideoSource,
    folder: VideoItem,
    onOpen: (VideoItem) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    var view by remember(folder.id) { mutableStateOf(ListView()) }
    var showSortFilter by remember { mutableStateOf(false) }
    if (showSortFilter) SortFilterDialog(
        null, emptyList(), emptyMap(), view, onApply = { _, v -> view = v }, onDismiss = { showSortFilter = false },
    )
    val result by produceState<Result<List<VideoItem>>?>(null, source, folder.id, reload) {
        value = null
        value = try {
            Result.success(source.browse(folder))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), S.back) }
            Text(folder.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SortFilterChip(active = !view.isDefault) { showSortFilter = true }
        }
        val r = result
        when {
            r == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            r.isFailure -> Column(Modifier.padding(24.dp)) {
                Text(r.exceptionOrNull()?.message ?: "", color = MaterialTheme.colorScheme.error)
                Button(onClick = { reload++ }, Modifier.padding(top = 8.dp)) { Text(S.retry) }
            }
            else -> {
                val items = view.apply(r.getOrDefault(emptyList()), source::isFolder)
                val tracks = items.filterNot(source::isFolder)
                if (items.isEmpty()) Text(S.noVideos, Modifier.padding(24.dp))
                else LazyColumn(Modifier.fillMaxSize()) {
                    if (tracks.isNotEmpty()) item(key = "actions") {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { graph.hub.playQueue(tracks) }) {
                                Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.size(6.dp)); Text(S.playAll)
                            }
                            OutlinedButton(onClick = { graph.hub.playShuffled(tracks) }, enabled = tracks.size > 1) {
                                Icon(painterResource(R.drawable.ic_shuffle), null); Spacer(Modifier.size(6.dp)); Text(S.shuffle)
                            }
                        }
                    }
                    itemsIndexed(items, key = { i, it -> "$i:${it.id}" }) { _, item ->
                        val isFolder = source.isFolder(item)
                        VideoRow(
                            graph, item,
                            onClick = { if (isFolder) onOpen(item) else graph.hub.playQueue(tracks, tracks.indexOf(item)) },
                            onLongClick = { adding = item },
                        ) {
                            if (!isFolder) IconButton(onClick = { adding = item }) {
                                Icon(painterResource(R.drawable.ic_playlist_add), S.addToPlaylist)
                            }
                        }
                    }
                }
            }
        }
    }
}
