package app.pitstop.video.ui

import app.pitstop.video.core.S
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import app.pitstop.video.core.Playlist
import app.pitstop.video.core.PlaylistOps
import app.pitstop.video.core.VideoItem

@Composable
fun PlaylistsTab(graph: AppGraph) {
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val open = playlists.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        PlaylistDetail(graph, open, onBack = { openId = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(S.playlistCount(playlists.size), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Button(onClick = { creating = true }) { Text(S.newPlaylist) }
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

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), S.back) }
            Text(
                p.name, Modifier.weight(1f).clickable { renaming = true },
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), S.deletePlaylist) }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { graph.hub.playQueue(p.items) }, enabled = p.items.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.size(6.dp)); Text(S.playAll)
            }
            OutlinedButton(onClick = { graph.hub.playQueue(p.items.shuffled()) }, enabled = p.items.size > 1) {
                Icon(painterResource(R.drawable.ic_shuffle), null); Spacer(Modifier.size(6.dp)); Text(S.shuffle)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (p.items.isEmpty()) Text(S.playlistEmpty, Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(p.items, key = { i, it -> "$i:${it.source}:${it.id}" }) { i, item ->
                VideoRow(graph, item, onClick = { graph.hub.playQueue(p.items, i) }) {
                    Column {
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
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(s.queue, key = { i, it -> "$i:${it.id}:${it.part}" }) { i, item ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { if (i != s.index) graph.hub.playQueue(s.queue, i); onDismiss() }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (i == s.index) Icon(painterResource(R.drawable.ic_play), S.nowPlaying, Modifier.size(20.dp))
                            else Spacer(Modifier.size(20.dp))
                            Spacer(Modifier.size(8.dp))
                            Text(
                                item.title, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = if (i == s.index) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(S.close) } },
    )
}

/** Pick a playlist for [item], or create a new one containing it. */
@Composable
fun AddToPlaylistDialog(graph: AppGraph, item: VideoItem, onDismiss: () -> Unit) {
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        NameDialog(S.newPlaylist, "", onDismiss = onDismiss) {
            graph.playlists.create(it, listOf(item))
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.addToPlaylist) },
        text = {
            Column {
                Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                playlists.forEach { p ->
                    val already = PlaylistOps.contains(p, item)
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !already) { graph.playlists.add(p.id, item); onDismiss() }
                            .padding(vertical = 12.dp),
                    ) {
                        Text(p.name, Modifier.weight(1f))
                        Text(if (already) S.added else "${p.items.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text(S.newPlaylist) } },
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
