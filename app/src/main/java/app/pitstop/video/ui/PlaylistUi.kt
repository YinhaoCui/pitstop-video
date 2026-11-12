package app.pitstop.video.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
            Text("${playlists.size} playlist(s)", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Button(onClick = { creating = true }) { Text("New playlist") }
        }
        if (playlists.isEmpty()) {
            Text(
                "No playlists yet. Tap + next to any video to add it to a playlist, or create one here.",
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
                        Text("${p.items.size} video(s)", style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { graph.hub.playQueue(p.items) }, enabled = p.items.isNotEmpty()) {
                        Icon(painterResource(R.drawable.ic_play), "Play all")
                    }
                }
                HorizontalDivider()
            }
        }
    }
    if (creating) NameDialog("New playlist", "", onDismiss = { creating = false }) {
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
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Back") }
            Text(
                p.name, Modifier.weight(1f).clickable { renaming = true },
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = { confirmDelete = true }) { Icon(painterResource(R.drawable.ic_delete), "Delete playlist") }
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { graph.hub.playQueue(p.items) }, enabled = p.items.isNotEmpty()) {
                Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.size(6.dp)); Text("Play all")
            }
            OutlinedButton(onClick = { graph.hub.playQueue(p.items.shuffled()) }, enabled = p.items.size > 1) {
                Icon(painterResource(R.drawable.ic_shuffle), null); Spacer(Modifier.size(6.dp)); Text("Shuffle")
            }
        }
        Spacer(Modifier.height(8.dp))
        if (p.items.isEmpty()) Text("Empty. Tap + next to a video in any tab to add it here.", Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(p.items, key = { i, it -> "$i:${it.source}:${it.id}" }) { i, item ->
                VideoRow(graph, item, onClick = { graph.hub.playQueue(p.items, i) }) {
                    Column {
                        IconButton(onClick = { graph.playlists.move(p.id, i, i - 1) }, enabled = i > 0, modifier = Modifier.size(32.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_up), "Move up")
                        }
                        IconButton(onClick = { graph.playlists.move(p.id, i, i + 1) }, enabled = i < p.items.lastIndex, modifier = Modifier.size(32.dp)) {
                            Icon(painterResource(R.drawable.ic_arrow_down), "Move down")
                        }
                    }
                    IconButton(onClick = { graph.playlists.removeAt(p.id, i) }) {
                        Icon(painterResource(R.drawable.ic_delete), "Remove from playlist")
                    }
                }
            }
        }
    }
    if (renaming) NameDialog("Rename playlist", p.name, onDismiss = { renaming = false }) {
        graph.playlists.rename(p.id, it)
        renaming = false
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete “${p.name}”?") },
        text = { Text("The playlist is removed. The videos themselves are not affected.") },
        confirmButton = { TextButton(onClick = { graph.playlists.delete(p.id); confirmDelete = false; onBack() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

/** Pick a playlist for [item], or create a new one containing it. */
@Composable
fun AddToPlaylistDialog(graph: AppGraph, item: VideoItem, onDismiss: () -> Unit) {
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    if (creating) {
        NameDialog("New playlist", "", onDismiss = onDismiss) {
            graph.playlists.create(it, listOf(item))
            onDismiss()
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
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
                        Text(if (already) "added" else "${p.items.size}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { creating = true }) { Text("New playlist") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
