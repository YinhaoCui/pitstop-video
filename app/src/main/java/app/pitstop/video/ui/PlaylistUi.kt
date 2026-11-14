package app.pitstop.video.ui

import app.pitstop.video.core.S
import app.pitstop.video.core.I18n
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import app.pitstop.video.core.folderTracks
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.ListSort
import app.pitstop.video.core.ListView
import app.pitstop.video.core.Playlist
import app.pitstop.video.core.PlaylistOps
import app.pitstop.video.core.VideoItem

@Composable
fun PlaylistsTab(graph: AppGraph) {
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var exportAll by remember { mutableStateOf(false) }
    if (importing) ImportDialog(graph, onDismiss = { importing = false }, onSaved = { importing = false; openId = it })

    val open = playlists.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        PlaylistDetail(graph, open, onBack = { openId = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S.playlistCount(playlists.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            // Import from other platforms / files; export everything as a Pitstop file.
            OutlinedButton(onClick = { importing = true }) { Text(S.importPlaylist) }
            Spacer(Modifier.size(8.dp))
            Button(onClick = { creating = true }) { Text(S.newPlaylist) }
            Box {
                IconButton(onClick = { exportAll = true }, enabled = playlists.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_share), S.exportAll)
                }
                ExportMenu(graph, null, exportAll) { exportAll = false }
            }
        }
        if (playlists.isEmpty()) {
            Text(
                S.noPlaylists,
                Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(playlists, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().clickable { openId = p.id }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.ic_playlist_play), null)
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(S.videoCount(p.items.size), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { graph.hub.playQueue(p.items) }, enabled = p.items.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_play), S.playAll)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (creating) NameDialog(S.newPlaylist, "", onDismiss = { creating = false }) {
        openId = graph.playlists.create(it).id
        creating = false
    }
}

@Composable
private fun PlaylistDetail(graph: AppGraph, p: Playlist, onBack: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var view by remember(p.id) { mutableStateOf(ListView()) }
    var menuFor by remember { mutableStateOf<VideoItem?>(null) }
    menuFor?.let { AddToPlaylistDialog(graph, it) { menuFor = null } }
    var showSortFilter by remember { mutableStateOf(false) }
    if (showSortFilter) SortFilterDialog(
        null, emptyList(), emptyMap(), view, platforms = p.items.map { it.source }.distinct(),
        onApply = { _, v -> view = v }, onDismiss = { showSortFilter = false },
    )
    // Shown items with their place in the playlist (move / remove act on that place).
    val shown = remember(p.items, view) {
        val indexed = p.items.withIndex().toList()
        val order = view.apply(p.items)
        val used = BooleanArray(indexed.size)
        order.map { item -> indexed.first { (i, it) -> !used[i] && it == item }.also { used[it.index] = true } }
    }
    val shownItems = shown.map { it.value }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), S.back) }
            Text(
                p.name, Modifier.weight(1f).clickable { renaming = true },
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { exporting = true }) { Icon(painterResource(R.drawable.ic_share), S.exportPlaylist) }
                ExportMenu(graph, p, exporting) { exporting = false }
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), S.deletePlaylist) }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { graph.hub.playQueue(shownItems) }, enabled = shownItems.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.size(6.dp)); Text(S.playAll)
            }
            OutlinedButton(onClick = { graph.hub.playShuffled(shownItems) }, enabled = shownItems.size > 1) {
                Icon(painterResource(R.drawable.ic_shuffle), null); Spacer(Modifier.size(6.dp)); Text(S.shuffle)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SortFilterChip(active = !view.isDefault) { showSortFilter = true }
            // Sorted but nothing filtered out: the new order can be kept.
            if (view.sort != ListSort.DEFAULT && shownItems.size == p.items.size) {
                TextButton(onClick = { graph.playlists.reorder(p.id, shownItems); view = view.copy(sort = ListSort.DEFAULT) }) { Text(S.keepThisOrder) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (p.items.isEmpty()) Text(S.playlistEmpty, Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            if (shownItems.size < p.items.size) item(key = "hidden") {
                Text(
                    S.hiddenByFilters(p.items.size - shownItems.size), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(shown, key = { _, it -> "${it.index}:${it.value.source}:${it.value.id}" }) { pos, entry ->
                val i = entry.index
                val item = entry.value
                VideoRow(graph, item, onClick = { graph.hub.playQueue(shownItems, pos) }, onLongClick = { menuFor = item }) {
                    // Playlists mix platforms: show where each item comes from.
                    PlatformBadge(S.sourceName(item.source))
                    // Moving only makes sense in the playlist's own order.
                    if (view.isDefault) Column {
                        IconButton(onClick = { graph.playlists.move(p.id, i, i - 1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_up), S.moveUp)
                        }
                        IconButton(onClick = { graph.playlists.move(p.id, i, i + 1) }, enabled = i < p.items.lastIndex, modifier = Modifier.size(32.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_down), S.moveDown)
                        }
                    }
                    IconButton(onClick = { graph.playlists.removeAt(p.id, i) }) {
                        Icon(painterResource(R.drawable.ic_delete), S.removeFromPlaylist)
                    }
                }
            }
        }
    }
    if (renaming) NameDialog(S.renamePlaylist, p.name, onDismiss = { renaming = false }) {
        graph.playlists.rename(p.id, it)
        renaming = false
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(S.deleteConfirm(p.name)) },
        text = { Text(S.deleteExplain) },
        confirmButton = { TextButton(onClick = { graph.playlists.delete(p.id); confirmDelete = false; onBack() }) { Text(S.delete) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(S.cancel) } },
    )
}

/** What's queued (including the parts of a multi-part Bilibili video); tap one to jump to it. */
@Composable
fun QueueDialog(graph: AppGraph, onDismiss: () -> Unit) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (s.queue.isEmpty()) S.queue else S.queueCount(s.index + 1, s.queue.size)) },
        text = {
            if (s.queue.isEmpty()) {
                Text(S.nothingQueuedPhone)
            } else {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    item(key = "head") { QueueHeader(graph, s.index, s.queue.size) }
                    itemsIndexed(s.queue, key = { i, it -> "$i:${it.id}:${it.part}" }) { i, item ->
                        QueueRow(graph, item, i, s.index, s.queue.size, onPlay = { graph.hub.jumpTo(i); onDismiss() })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(S.close) } },
    )
}

/**
 * Pick a playlist for [item], or create a new one containing it. A folder row adds the songs
 * inside it (subfolders too), never the folder itself.
 */
@Composable
fun AddToPlaylistDialog(graph: AppGraph, item: VideoItem, onDismiss: () -> Unit) {
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val source = remember(item.source) { graph.source(item.source) }
    val isFolder = source.isFolder(item)
    // null while a folder's songs are still loading.
    val tracks by produceState<Result<List<VideoItem>>?>(if (isFolder) null else Result.success(listOf(item)), item) {
        if (isFolder) value = runCatching { source.folderTracks(item) }
    }
    val songs = tracks?.getOrNull().orEmpty()
    val ready = songs.isNotEmpty()
    var downloading by remember { mutableStateOf(false) }
    if (downloading) {
        DownloadDialog(graph, item, onDismiss = onDismiss)
        return
    }

    if (creating) {
        NameDialog(S.newPlaylist, if (isFolder) item.title else "", onDismiss = onDismiss) {
            graph.playlists.create(it, songs)
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // The item's menu: queue, download, then the playlists to add it to.
        title = { Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                item.author?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                if (isFolder) {
                    val t = tracks
                    Text(
                        when {
                            t == null -> S.loading
                            t.isFailure -> t.exceptionOrNull()?.message ?: S.noVideos
                            songs.isEmpty() -> S.noVideos
                            else -> S.songsInFolder(songs.size)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (t?.isFailure == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                // Queue: a folder queues the songs inside it.
                val context = LocalContext.current
                listOf(
                    Triple(R.drawable.ic_skip_next, S.playNext) { graph.hub.playNext(songs) },
                    Triple(R.drawable.ic_playlist_play, S.addToQueue) { graph.hub.addToQueue(songs) },
                ).forEach { (icon, label, action) ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ready) {
                            action()
                            android.widget.Toast.makeText(context, I18n.s.addedToQueue(songs.size), android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(icon), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(label, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (!isFolder && graph.downloads.canDownload(item)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { downloading = true }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(R.drawable.ic_download), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(S.download, color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider()
                }
                Spacer(Modifier.height(8.dp))
                Text(S.addToPlaylist, style = MaterialTheme.typography.titleSmall)
                playlists.forEach { p ->
                    val already = ready && songs.all { PlaylistOps.contains(p, it) }
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ready && !already) { graph.playlists.addAll(p.id, songs); onDismiss() }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(p.name, Modifier.weight(1f))
                        Text(if (already) S.added else "${p.items.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }, enabled = ready) { Text(S.newPlaylist) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text(S.name) }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(S.ok) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}
