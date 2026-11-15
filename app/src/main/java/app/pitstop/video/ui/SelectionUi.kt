package app.pitstop.video.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.I18n
import app.pitstop.video.core.S
import app.pitstop.video.core.Selection
import app.pitstop.video.core.VideoItem

/**
 * Long-press multi-select for a list of items: null keys = not selecting. Keys are the list's own row
 * keys, so the same item shown twice can be picked once or twice as the list shows it.
 */
@Stable
class ItemSelection {
    var keys by mutableStateOf<Set<String>?>(null)
        private set
    val active: Boolean get() = keys != null

    fun start(key: String) { keys = setOf(key) }
    fun toggle(key: String) { keys = keys?.let { Selection.toggle(it, key) } }
    fun set(keys: Set<String>) { this.keys = keys }
    fun clear() { keys = null }

    fun isSelected(key: String) = keys?.contains(key) == true

    /** A row's tap: toggles while selecting, else [open]. */
    fun click(key: String, open: () -> Unit) { if (active) toggle(key) else open() }

    /** A row's long-press: starts selecting with this row, or toggles it. */
    fun longPress(key: String) { if (active) toggle(key) else start(key) }
}

/**
 * The queue's selection bar: Play / Add to playlist for the picked entries, and Remove from queue.
 * Keys are queue positions ("0", "1" ...).
 */
@Composable
fun QueueSelectionHeader(graph: AppGraph, selection: ItemSelection, queue: List<VideoItem>) {
    ItemSelectionHeader(graph, selection, queue.mapIndexed { i, it -> "$i" to it }, queueActions = false) { picked ->
        OutlinedButton(onClick = {
            graph.hub.removeAllFromQueue(selection.keys.orEmpty().mapNotNullTo(HashSet()) { it.toIntOrNull() })
            selection.clear()
        }, enabled = picked.isNotEmpty()) {
            Icon(painterResource(R.drawable.ic_delete), null); Spacer(Modifier.size(6.dp)); Text(S.removeFromQueue)
        }
    }
}

/** A selection that is dropped whenever one of [resetKeys] changes (another feed, folder, query ...). */
@Composable
fun rememberItemSelection(vararg resetKeys: Any?): ItemSelection = remember(*resetKeys) { ItemSelection() }

/** Top bar while multi-selecting: cancel, the count, Select all / Invert, then any extra [actions]. */
@Composable
internal fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onCancel: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(painterResource(R.drawable.ic_close), S.cancelSelection) }
        Text(S.selectedCount(count), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        TextButton(onClick = onSelectAll, enabled = !allSelected) { Text(S.selectAll, maxLines = 1) }
        TextButton(onClick = onInvert) { Text(S.invertSelection, maxLines = 1) }
        actions()
    }
}

/**
 * The bar shown above a list while [selection] is active: Select all / Invert over [visible] (row key →
 * item, in display order; folders left out by the caller), then Play / Play next / Add to queue /
 * Add to playlist for the picked items, and any [extra] buttons. Back leaves selection mode.
 */
@Composable
fun ItemSelectionHeader(
    graph: AppGraph,
    selection: ItemSelection,
    visible: List<Pair<String, VideoItem>>,
    /** Off for the queue itself: its items are queued already. */
    queueActions: Boolean = true,
    extra: @Composable RowScope.(picked: List<VideoItem>) -> Unit = {},
) {
    val sel = selection.keys ?: return
    BackHandler { selection.clear() }
    var adding by remember { mutableStateOf<List<VideoItem>?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    val keys = visible.map { it.first }
    val picked = visible.filter { it.first in sel }.map { it.second }
    val any = picked.isNotEmpty()
    val context = LocalContext.current
    Column {
        SelectionBar(
            count = picked.size,
            allSelected = Selection.allSelected(sel, keys),
            onSelectAll = { selection.set(Selection.selectAll(keys)) },
            onInvert = { selection.set(Selection.invert(sel, keys)) },
            onCancel = selection::clear,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { graph.hub.playQueue(picked); selection.clear() }, enabled = any) {
                Icon(painterResource(R.drawable.ic_play), null); Spacer(Modifier.size(6.dp)); Text(S.playSelected)
            }
            if (queueActions) listOf(
                Triple(R.drawable.ic_skip_next, S.playNext) { graph.hub.playNext(picked) },
                Triple(R.drawable.ic_playlist_play, S.addToQueue) { graph.hub.addToQueue(picked) },
            ).forEach { (icon, label, action) ->
                OutlinedButton(onClick = {
                    action()
                    android.widget.Toast.makeText(context, I18n.s.addedToQueue(picked.size), android.widget.Toast.LENGTH_SHORT).show()
                    selection.clear()
                }, enabled = any) { Icon(painterResource(icon), null); Spacer(Modifier.size(6.dp)); Text(label) }
            }
            OutlinedButton(onClick = { adding = picked }, enabled = any) {
                Icon(painterResource(R.drawable.ic_playlist_add), null); Spacer(Modifier.size(6.dp)); Text(S.addToPlaylist)
            }
            extra(picked)
        }
    }
}
