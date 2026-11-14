package app.pitstop.video.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.S
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.formatDuration

/** Above the queue: how to edit it, and Clear upcoming when anything follows the playing item. */
@Composable
fun QueueHeader(graph: AppGraph, current: Int, size: Int) {
    if (size == 0) return
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            S.queueEditHint, Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (current < size - 1) TextButton(onClick = graph.hub::clearUpcoming) { Text(S.clearUpcoming) }
    }
}

/**
 * One queue entry: ⋮⋮ drag handle, small cover, title, creator. Tap plays it; long-press (or the ⋮ button) opens
 * Play next / Move up / Move down / Move to top / Remove / Add to playlist.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueRow(graph: AppGraph, item: VideoItem, i: Int, current: Int, size: Int, reorder: ReorderState, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    if (adding) AddToPlaylistDialog(graph, item) { adding = false }
    val haptics = LocalHapticFeedback.current
    val playing = i == current
    val thumb by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item) }
    Box(Modifier.reorderable(reorder, i, MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            Modifier.fillMaxWidth()
                .combinedClickable(onClick = onPlay, onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true })
                .padding(start = 0.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DragHandle(reorder, i, size)
            Box(
                Modifier.width(if (item.source.music) 44.dp else 72.dp).aspectRatio(if (item.source.music) 1f else 16f / 9f)
                    .clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (playing) Box(Modifier.fillMaxSize().background(Color(0x88000000)), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_play), S.nowPlaying, Modifier.size(20.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    style = if (playing) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                val sub = listOfNotNull(item.author, formatDuration(item.durationSec).ifEmpty { null }).joinToString(" · ")
                if (sub.isNotEmpty()) Text(
                    sub, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more), S.queueOptions) }
        }
        val hub = graph.hub
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.padding(end = 8.dp)) {
            val close = { menu = false }
            if (!playing) DropdownMenuItem(text = { Text(S.playNow) }, onClick = { close(); onPlay() })
            // Right after the playing item: an earlier item goes to the playing item's old place, which is now just after it.
            if (!playing && i != current + 1) DropdownMenuItem(
                text = { Text(S.playNext) },
                onClick = { close(); hub.moveInQueue(i, if (i < current) current else current + 1) },
            )
            DropdownMenuItem(text = { Text(S.moveUp) }, enabled = i > 0, onClick = { close(); hub.moveInQueue(i, i - 1) })
            DropdownMenuItem(text = { Text(S.moveDown) }, enabled = i < size - 1, onClick = { close(); hub.moveInQueue(i, i + 1) })
            DropdownMenuItem(text = { Text(S.moveToTop) }, enabled = i > 0, onClick = { close(); hub.moveInQueue(i, 0) })
            DropdownMenuItem(text = { Text(S.addToPlaylist) }, onClick = { close(); adding = true })
            DropdownMenuItem(
                text = { Text(S.removeFromQueue, color = MaterialTheme.colorScheme.error) },
                onClick = { close(); hub.removeFromQueue(i) },
            )
        }
    }
}
