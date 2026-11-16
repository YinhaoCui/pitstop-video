package app.pitstop.video.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitstop.video.AppGraph
import app.pitstop.video.core.Duplicates
import app.pitstop.video.core.Playlist
import app.pitstop.video.core.S

/**
 * Similar items in a playlist, grouped: the first of each group is kept, the rest can be ticked for
 * removal. Certain copies (same item, same title) start ticked; look-alikes start unticked.
 */
@Composable
fun DuplicatesDialog(graph: AppGraph, p: Playlist, onDismiss: () -> Unit) {
    val groups = remember(p.items) { Duplicates.groups(p.items) }
    var remove by remember(groups) { mutableStateOf(groups.flatMap { it.exact }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (groups.isEmpty()) S.noDuplicates else S.duplicateGroups(groups.size)) },
        text = {
            if (groups.isNotEmpty()) Column {
                Text(S.duplicatesHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.heightIn(max = 460.dp)) {
                    groups.forEachIndexed { gi, g ->
                        item(key = "g$gi") { HorizontalDivider(Modifier.padding(vertical = 6.dp)) }
                        items(g.indices, key = { "i$it" }) { i ->
                            val item = p.items[i]
                            val keep = i == g.indices.first()
                            val toggle = { remove = if (i in remove) remove - i else remove + i }
                            Row(
                                Modifier.fillMaxWidth().clickable(enabled = !keep, onClick = toggle).padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = i in remove, onCheckedChange = { toggle() }, enabled = !keep)
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        if (keep) S.duplicateKept else listOfNotNull(S.sourceName(item.source), item.author).joinToString(" · "),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                                        color = if (keep) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (groups.isEmpty()) TextButton(onClick = onDismiss) { Text(S.ok) }
            else TextButton(onClick = { graph.playlists.removeAll(p.id, remove); onDismiss() }, enabled = remove.isNotEmpty()) {
                Text(S.removeCount(remove.size))
            }
        },
        dismissButton = { if (groups.isNotEmpty()) TextButton(onClick = onDismiss) { Text(S.cancel) } },
    )
}
