package app.pitstop.video.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import app.pitstop.video.core.S
import app.pitstop.video.core.I18n
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Checkbox
import app.pitstop.video.core.Selection
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
    // Multi-select of playlists (long-press one) for bulk delete.
    var selected by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (importing) ImportDialog(graph, onDismiss = { importing = false }, onSaved = { importing = false; openId = it })

    val open = playlists.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        PlaylistDetail(graph, open, onBack = { openId = null })
        return
    }

    // Starred can't be deleted, so it is never part of a selection.
    val ids = playlists.map { it.id }.filter { it != PlaylistOps.STARRED }
    val sel = selected?.let { Selection.retain(it.toSet(), ids) }
    BackHandler(enabled = sel != null) { selected = null }
    Column(Modifier.fillMaxSize()) {
        if (sel != null) SelectionBar(
            count = sel.size,
            allSelected = Selection.allSelected(sel, ids),
            onSelectAll = { selected = ids },
            onInvert = { selected = Selection.invert(sel, ids).toList() },
            onCancel = { selected = null },
        ) {
            IconButton(onClick = { confirmDelete = true }, enabled = sel.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_delete), S.delete)
            }
        } else Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                val toggle = { if (p.id != PlaylistOps.STARRED) sel?.let { selected = Selection.toggle(it, p.id).toList() }; Unit }
                Row(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { if (sel != null) toggle() else { openId = p.id } },
                        onLongClick = { if (sel != null) toggle() else if (p.id != PlaylistOps.STARRED) { selected = listOf(p.id) } },
                    ).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val starred = p.id == PlaylistOps.STARRED
                    if (sel != null && !starred) Checkbox(checked = p.id in sel, onCheckedChange = { toggle() }, Modifier.size(24.dp))
                    else Icon(
                        painterResource(if (starred) R.drawable.ic_star else R.drawable.ic_playlist_play), null,
                        tint = if (starred) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified,
                    )
                    Spacer(Modifier.size(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Text(S.itemCount(p.items), style = MaterialTheme.typography.bodySmall)
                    }
                    if (sel == null) IconButton(onClick = { graph.hub.playQueue(p.items) }, enabled = p.items.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_play), S.playAll)
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (confirmDelete && sel != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(S.deletePlaylistsConfirm(sel.size)) },
        text = { Text(S.deleteExplain) },
        confirmButton = { TextButton(onClick = { graph.playlists.deleteAll(sel); selected = null; confirmDelete = false }) { Text(S.delete) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(S.cancel) } },
    )
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
    // Multi-select by place in the playlist; any change to the playlist ends it, so a place never
    // points at a different item than the one that was ticked.
    val selection = rememberItemSelection(p.id, p.items)
    // The built-in Starred playlist can't be renamed or deleted.
    val builtIn = p.id == PlaylistOps.STARRED
    var confirmRemove by remember { mutableStateOf(false) }
    var showDuplicates by remember { mutableStateOf(false) }
    if (showDuplicates) DuplicatesDialog(graph, p) { showDuplicates = false }
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
    val selecting = selection.active
    val pickedPlaces = selection.keys.orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() }

    Column(Modifier.fillMaxSize()) {
        if (selecting) {
            // Play / Play next / Add to queue / Add to playlist (keeps the selection, so "copy, then
            // remove" moves items), plus Remove from this playlist.
            ItemSelectionHeader(graph, selection, shown.map { "${it.index}" to it.value }) { picked ->
                OutlinedButton(onClick = { confirmRemove = true }, enabled = picked.isNotEmpty()) {
                    Icon(painterResource(R.drawable.ic_delete), null); Spacer(Modifier.size(6.dp)); Text(S.remove)
                }
            }
        } else Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), S.back) }
            Text(
                p.name, Modifier.weight(1f).clickable(enabled = !builtIn) { renaming = true },
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Box {
                IconButton(onClick = { exporting = true }) { Icon(painterResource(R.drawable.ic_share), S.exportPlaylist) }
                ExportMenu(graph, p, exporting) { exporting = false }
            }
            if (!builtIn) IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), S.deletePlaylist) }
        }
        if (!selecting) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { graph.hub.playQueue(shownItems) }, enabled = shownItems.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.size(6.dp)); Text(S.playAll)
            }
            OutlinedButton(onClick = { graph.hub.playShuffled(shownItems) }, enabled = shownItems.size > 1) {
                Icon(painterResource(R.drawable.ic_shuffle), null); Spacer(Modifier.size(6.dp)); Text(S.shuffle)
            }
        }
        if (!selecting) Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SortFilterChip(active = !view.isDefault) { showSortFilter = true }
            TextButton(onClick = { selection.set(emptySet()) }, enabled = shownItems.isNotEmpty()) { Text(S.select) }
            TextButton(onClick = { showDuplicates = true }, enabled = p.items.size > 1) { Text(S.findDuplicates) }
            // Sorted but nothing filtered out: the new order can be kept.
            if (view.sort != ListSort.DEFAULT && shownItems.size == p.items.size) {
                TextButton(onClick = { graph.playlists.reorder(p.id, shownItems); view = view.copy(sort = ListSort.DEFAULT) }) { Text(S.keepThisOrder) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (p.items.isEmpty()) Text(S.playlistEmpty, Modifier.padding(24.dp))
        val listState = rememberLazyListState()
        val reorder = rememberReorderState(listState)
        // Dragging works in the playlist's own order only (no sort / filter), so rows map 1:1 to places.
        reorder.bind(first = 0, count = p.items.size) { from, to -> graph.playlists.move(p.id, from, to) }
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            if (shownItems.size < p.items.size) item(key = "hidden") {
                Text(
                    S.hiddenByFilters(p.items.size - shownItems.size), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            itemsIndexed(shown, key = { _, it -> "${it.index}:${it.value.source}:${it.value.id}" }) { pos, entry ->
                val i = entry.index
                val item = entry.value
                if (selecting) {
                    val toggle = { selection.toggle("$i") }
                    VideoRow(graph, item, onClick = toggle, onLongClick = toggle, selected = selection.isSelected("$i")) {
                        PlatformBadge(S.sourceName(item.source))
                    }
                    return@itemsIndexed
                }
                VideoRow(
                    // Long-press starts multi-select; its bar has the item menu (Add to playlist) too.
                    graph, item, onClick = { graph.hub.playQueue(shownItems, pos) }, onLongClick = { selection.start("$i") },
                    modifier = if (view.isDefault) Modifier.reorderable(reorder, i, MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier,
                    // Moving only makes sense in the playlist's own order.
                    leading = if (view.isDefault) { { DragHandle(reorder, i, p.items.size) } } else null,
                ) {
                    // Playlists mix platforms: show where each item comes from.
                    PlatformBadge(S.sourceName(item.source))
                    IconButton(onClick = { graph.playlists.removeAt(p.id, i) }) {
                        Icon(painterResource(R.drawable.ic_delete), S.removeFromPlaylist)
                    }
                }
            }
        }
    }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text(S.removeSelectedConfirm(pickedPlaces.size)) },
        confirmButton = {
            TextButton(onClick = { graph.playlists.removeAll(p.id, pickedPlaces); selection.clear(); confirmRemove = false }) { Text(S.remove) }
        },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(S.cancel) } },
    )
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
                val listState = rememberLazyListState()
                val reorder = rememberReorderState(listState)
                reorder.bind(first = 1, count = s.queue.size, onMove = graph.hub::moveInQueue)
                // Keys are queue places: a queue change (parts expanding, a removal) ends the selection.
                val selection = rememberItemSelection(s.queue)
                Column {
                    QueueSelectionHeader(graph, selection, s.queue)
                    LazyColumn(Modifier.heightIn(max = 480.dp), state = listState) {
                        item(key = "head") { if (!selection.active) QueueHeader(graph, s.index, s.queue.size) }
                        itemsIndexed(s.queue, key = { i, it -> "$i:${it.id}:${it.part}" }) { i, item ->
                            QueueRow(graph, item, i, s.index, s.queue.size, reorder, onPlay = { graph.hub.jumpTo(i); onDismiss() }, selection = selection)
                        }
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
    val source = remember(item.source) { graph.source(item.source) }
    val isFolder = source.isFolder(item)
    // null while a folder's songs are still loading.
    val tracks by produceState<Result<List<VideoItem>>?>(if (isFolder) null else Result.success(listOf(item)), item) {
        if (isFolder) value = runCatching { source.folderTracks(item) }
    }
    val songs = tracks?.getOrNull().orEmpty()
    var downloading by remember { mutableStateOf(false) }
    if (downloading) {
        DownloadDialog(graph, item, onDismiss = onDismiss)
        return
    }

    ItemMenuDialog(
        graph, title = item.title, author = item.author,
        status = if (!isFolder) null else tracks.let { t ->
            when {
                t == null -> S.loading
                t.isFailure -> t.exceptionOrNull()?.message ?: S.nothingHere
                songs.isEmpty() -> S.nothingHere
                else -> S.inFolder(songs)
            } to (t?.isFailure == true)
        },
        songs = songs, newName = if (isFolder) item.title else "",
        onDownload = if (!isFolder && graph.downloads.canDownload(item)) ({ downloading = true }) else null,
        star = item.takeIf { !isFolder },
        onDismiss = onDismiss,
    )
}

/** The menu for several items at once (a multi-selection): queue them or add them to a playlist. */
@Composable
fun AddToPlaylistDialog(graph: AppGraph, items: List<VideoItem>, onDismiss: () -> Unit) {
    // One item keeps its full menu (download, folder songs).
    if (items.size == 1) AddToPlaylistDialog(graph, items[0], onDismiss)
    else ItemMenuDialog(graph, title = S.selectedCount(items.size), author = null, status = null, songs = items, newName = "", onDownload = null, onDismiss = onDismiss)
}

/** Shared body of the item menu: queue actions, optional download, then the playlists. */
@Composable
private fun ItemMenuDialog(
    graph: AppGraph,
    title: String,
    author: String?,
    /** A status line under the title and whether it is an error. */
    status: Pair<String, Boolean>?,
    songs: List<VideoItem>,
    newName: String,
    onDownload: (() -> Unit)?,
    /** A single item (not a folder): offer Star / Remove star. */
    star: VideoItem? = null,
    onDismiss: () -> Unit,
) {
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val ready = songs.isNotEmpty()
    if (creating) {
        NameDialog(S.newPlaylist, newName, onDismiss = onDismiss) {
            graph.playlists.create(it, songs)
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // The item's menu: queue, download, then the playlists to add it to.
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                author?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                status?.let { (text, isError) ->
                    Text(
                        text, style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
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
                star?.let { item ->
                    val starred = playlists.firstOrNull { it.id == PlaylistOps.STARRED }?.let { PlaylistOps.contains(it, item) } == true
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            val now = graph.playlists.toggleStar(item)
                            android.widget.Toast.makeText(context, if (now) I18n.s.starAdded else I18n.s.starRemoved, android.widget.Toast.LENGTH_SHORT).show()
                            onDismiss()
                        }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(painterResource(if (starred) R.drawable.ic_star else R.drawable.ic_star_border), null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(if (starred) S.unstar else S.star, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (onDownload != null) {
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onDownload).padding(vertical = 12.dp),
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
                // A single item stars with the row above; folders and selections can still add to Starred here.
                playlists.filter { star == null || it.id != PlaylistOps.STARRED }.forEach { p ->
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
