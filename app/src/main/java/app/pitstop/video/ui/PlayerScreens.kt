package app.pitstop.video.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.S

/**
 * Narrow phones: a slim bar pinned under the library while something plays. Tapping it opens the
 * full player screen; the buttons control playback without leaving the library.
 */
@Composable
fun MiniPlayer(graph: AppGraph, onOpen: () -> Unit) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    val item = s.current ?: return
    val art by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onOpen),
    ) {
        if (s.loading || s.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(painterResource(if (s.isMusic) R.drawable.ic_music else R.drawable.ic_video), null)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                val sub = s.error ?: item.author.orEmpty()
                if (sub.isNotEmpty()) Text(
                    sub, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = graph.hub::togglePause) {
                Icon(painterResource(if (s.wantsToPlay) R.drawable.ic_pause else R.drawable.ic_play), S.playPause)
            }
            IconButton(onClick = graph.hub::next) { Icon(painterResource(R.drawable.ic_skip_next), S.next) }
        }
    }
}

/**
 * Narrow phones: the player on its own screen, closed with the arrow or Back. Below the controls,
 * a Queue / Playlists switch: jump to any queued track, or start one of the saved playlists.
 */
@Composable
fun PlayerScreen(graph: AppGraph, onClose: () -> Unit, onFullscreen: () -> Unit) {
    BackHandler(onBack = onClose)
    val s by graph.hub.state.collectAsStateWithLifecycle()
    val playlists by graph.playlists.playlists.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(painterResource(R.drawable.ic_arrow_down), S.back) }
            Text(S.nowPlaying, style = MaterialTheme.typography.titleMedium)
        }
        val listState = rememberLazyListState()
        val reorder = rememberReorderState(listState)
        reorder.bind(first = 3, count = s.queue.size, onMove = graph.hub::moveInQueue)
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            item(key = "player") { NowPlayingCard(graph, onFullscreen = onFullscreen) }
            item(key = "tabs") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    listOf(S.queueCount(s.index + 1, s.queue.size), S.playlists).forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = tab == i, onClick = { tab = i },
                            shape = SegmentedButtonDefaults.itemShape(i, 2), icon = {},
                        ) { Text(label, maxLines = 1) }
                    }
                }
            }
            if (tab == 0) {
                item(key = "qhead") { QueueHeader(graph, s.index, s.queue.size) }
                itemsIndexed(s.queue, key = { i, it -> "q$i:${it.id}:${it.part}" }) { i, item ->
                    QueueRow(graph, item, i, s.index, s.queue.size, reorder, onPlay = { graph.hub.jumpTo(i) })
                }
            } else if (playlists.isEmpty()) {
                item(key = "none") { Text(S.noPlaylists, Modifier.padding(24.dp), style = MaterialTheme.typography.bodyMedium) }
            } else {
                items(playlists, key = { "p" + it.id }) { p ->
                    PickRow(
                        title = p.name, subtitle = S.videoCount(p.items.size), current = false,
                        onClick = { if (p.items.isNotEmpty()) { graph.hub.playQueue(p.items); tab = 0 } },
                    )
                }
            }
        }
    }
}

/** One line of the queue / playlist picker; the playing track is marked and in bold. */
@Composable
private fun PickRow(title: String, subtitle: String?, current: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (current) Icon(painterResource(R.drawable.ic_play), S.nowPlaying, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        else Spacer(Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (current) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyLarge,
                color = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (!subtitle.isNullOrEmpty()) Text(
                subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Narrow phones, "floating window" style: a small card over the library (the video itself, or
 * the cover for songs) that can be dragged anywhere; tapping it opens the full player page.
 * Call inside the BoxWithConstraints that fills the library area.
 */
@Composable
fun BoxWithConstraintsScope.FloatingPlayer(graph: AppGraph, onOpen: () -> Unit) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    val item = s.current ?: return
    val art by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item) }
    val density = LocalDensity.current
    val width = 200.dp
    val maxX = with(density) { (maxWidth - width - 12.dp).toPx() }.coerceAtLeast(0f)
    val maxY = with(density) { (maxHeight - 200.dp).toPx() }.coerceAtLeast(0f)
    // Starts in the bottom-right corner; position survives rotation.
    var x by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    var y by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    if (x.isNaN() || y.isNaN()) { x = maxX; y = maxY }
    x = x.coerceIn(0f, maxX); y = y.coerceIn(0f, maxY)
    val moveAndTap = Modifier
        .pointerInput(maxX, maxY) {
            detectDragGestures { change, drag ->
                change.consume()
                x = (x + drag.x).coerceIn(0f, maxX)
                y = (y + drag.y).coerceIn(0f, maxY)
            }
        }
        .pointerInput(Unit) { detectTapGestures(onTap = { onOpen() }) }

    Column(
        Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }.padding(6.dp).width(width)
            .shadow(8.dp, MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(if (s.isMusic) 1.6f else 16f / 9f).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            if (!s.isMusic && !s.shownElsewhere() && !s.drivingLocked) {
                PhonePlayer(graph, Modifier.fillMaxSize(), showControls = false)
            } else {
                art?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Icon(painterResource(R.drawable.ic_music), null, Modifier.align(Alignment.Center))
            }
            // Covers the video view so drags and taps reach the window instead of the player.
            Box(Modifier.matchParentSize().then(moveAndTap))
        }
        if (s.loading || s.buffering) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth().then(moveAndTap).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = graph.hub::togglePause) {
                Icon(painterResource(if (s.wantsToPlay) R.drawable.ic_pause else R.drawable.ic_play), S.playPause)
            }
            IconButton(onClick = graph.hub::next) { Icon(painterResource(R.drawable.ic_skip_next), S.next) }
        }
    }
}
