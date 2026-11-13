package app.pitstop.video.ui

import app.pitstop.video.player.SubtitleStyling
import app.pitstop.video.core.Settings
import app.pitstop.video.core.S
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import app.pitstop.video.AppGraph
import app.pitstop.video.R
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.formatDuration
import app.pitstop.video.local.LocalSource
import app.pitstop.video.player.DisplayMode
import kotlinx.coroutines.CancellationException

@Composable
fun AppRoot(graph: AppGraph, inPip: Boolean, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    if (inPip) {
        PhonePlayer(graph, Modifier.fillMaxSize(), showControls = false)
        return
    }
    if (fullscreen) {
        BackHandler { onFullscreenChange(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PhonePlayer(graph, Modifier.fillMaxSize(), showControls = true)
            IconButton(onClick = { onFullscreenChange(false) }, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)) {
                Icon(painterResource(R.drawable.ic_split), contentDescription = S.exitFullScreen, tint = Color.White)
            }
        }
        return
    }

    val tabs = graph.sources.map { S.sourceName(it.id) } + S.playlists + S.account
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by graph.hub.state.collectAsStateWithLifecycle()
    // Re-read feed lists after sign-in / sign-out.
    val ytSignedIn by graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    val biliLoggedIn by graph.biliCookies.loggedIn.collectAsStateWithLifecycle()

    val browser: @Composable (showHeader: Boolean) -> Unit = { showHeader ->
        Column {
            if (showHeader) BrandHeader(S.appName, S.brandKicker)
            PillTabs(tabs, tab, onSelect = { tab = it })
            if (tab < graph.sources.size) {
                val source = graph.sources[tab]
                SourceTab(graph, source, refreshKey = "$ytSignedIn/$biliLoggedIn")
            } else if (tab == graph.sources.size) {
                PlaylistsTab(graph)
            } else {
                AccountScreen(graph)
            }
        }
    }

    AnimeBackdrop(Modifier.fillMaxSize()) {
    Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onSurface) {
        BoxWithConstraints(Modifier.safeDrawingPadding()) {
            // Tablets, foldables, landscape phones and in-car / head-unit screens: player and
            // library side by side. Narrow portrait phones: player stacked above the library.
            val twoPane = maxWidth >= 600.dp && maxWidth > maxHeight * 0.9f
            if (twoPane) {
                Row(Modifier.fillMaxSize()) {
                    Column(Modifier.weight(0.55f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        if (state.current != null) {
                            NowPlayingCard(graph, onFullscreen = { onFullscreenChange(true) })
                        } else {
                            EmptyStage()
                        }
                    }
                    Box(Modifier.weight(0.45f).fillMaxHeight()) { browser(true) }
                }
            } else {
                Column {
                    if (state.current != null) {
                        NowPlayingCard(graph, onFullscreen = { onFullscreenChange(true) })
                    }
                    // Portrait phone with a video open: give the space to the player, not the logo.
                    browser(state.current == null)
                }
            }
        }
    }
    }
}

/** Two-pane layout before anything plays: a glowing stage instead of a black box. */
@Composable
private fun EmptyStage() {
    val night = MaterialTheme.colorScheme.background == Sakura.Night
    val stageBrush = if (night) Brush.linearGradient(listOf(Sakura.Dusk, Color(0xFF4B2378), Sakura.Pink.copy(alpha = 0.75f)))
    else Brush.linearGradient(listOf(Color(0xFFFFE1EE), Color(0xFFEDE5FF), Color(0xFFD9F4FF)))
    val ink = if (night) Color.White else Color(0xFF3A2C7A)
    Box(
        Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.extraLarge)
            .background(stageBrush)
            .border(1.dp, Color.White.copy(alpha = if (night) 0.12f else 0.8f), MaterialTheme.shapes.extraLarge),
    ) {
        // The mascot peeks in from the bottom-left corner.
        Image(
            painterResource(R.drawable.ill_mascot), null,
            Modifier.align(Alignment.BottomStart).fillMaxHeight(0.92f).aspectRatio(1f).padding(start = 8.dp),
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.58f).padding(end = 20.dp),
        ) {
            Box(Modifier.size(60.dp).clip(CircleShape).background(Sakura.accent), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_play), null, tint = Color.White, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(S.pickVideo, color = ink, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(S.pickVideoHint, color = ink.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
        Sparkle(Modifier.align(Alignment.TopEnd).padding(18.dp).size(20.dp), if (night) Sakura.Lemon else Sakura.Pink)
        Sparkle(Modifier.align(Alignment.BottomStart).padding(22.dp).size(12.dp), Sakura.Cyan)
    }
}

/** Gradient play / pause button: the one loud control on the screen. */
@Composable
private fun PlayOrb(playing: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Sakura.accent).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), S.playPause, tint = Color.White)
    }
}

@Composable
private fun NowPlayingCard(graph: AppGraph, onFullscreen: () -> Unit) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    var showQueue by remember { mutableStateOf(false) }
    if (showQueue) QueueDialog(graph) { showQueue = false }
    val stage = RoundedCornerShape(24.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Thin pink -> cyan rim around the player: the "screen glow".
        val rim = Modifier.border(1.5.dp, Sakura.accentWide, stage).padding(1.5.dp).clip(stage)
        if (s.carActive) {
            Box(
                Modifier.fillMaxWidth().height(104.dp).then(rim)
                    .background(Brush.linearGradient(listOf(Sakura.Dusk, Color(0xFF4B2378)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (s.audioOnly) S.audioOnlyPhone else if (s.drivingLocked) S.pausedCarMoving else S.playingOnCar,
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            PhonePlayer(graph, Modifier.fillMaxWidth().aspectRatio(16f / 9f).then(rim), showControls = true)
        }
        Spacer(Modifier.height(10.dp))
        Column(Modifier.padding(horizontal = 6.dp)) {
            Text(s.current?.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            val sub = s.error ?: if (s.loading) S.loading else s.current?.author.orEmpty()
            Text(sub, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.75f))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = graph.hub::previous) { Icon(painterResource(R.drawable.ic_skip_previous), S.previous) }
            PlayOrb(s.wantsToPlay, graph.hub::togglePause)
            IconButton(onClick = graph.hub::next) { Icon(painterResource(R.drawable.ic_skip_next), S.next) }
            IconButton(onClick = { showQueue = true }) { Icon(painterResource(R.drawable.ic_playlist_play), S.queue) }
            s.current?.let { cur ->
                IconButton(onClick = { adding = cur }) { Icon(painterResource(R.drawable.ic_playlist_add), S.addToPlaylist) }
            }
            if (s.carActive) {
                IconButton(onClick = graph.hub::toggleMode) {
                    Icon(painterResource(if (s.mode == DisplayMode.FULL) R.drawable.ic_split else R.drawable.ic_fullscreen), S.carLayout)
                }
            } else {
                IconButton(onClick = onFullscreen) { Icon(painterResource(R.drawable.ic_fullscreen), S.fullScreen) }
            }
        }
    }
}

/** The phone's video view. It detaches while the car screen owns the video output. */
@OptIn(UnstableApi::class)
@Composable
fun PhonePlayer(graph: AppGraph, modifier: Modifier, showControls: Boolean) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    val subtitleStyle by Settings.subtitles.collectAsStateWithLifecycle()
    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setShowNextButton(false); setShowPreviousButton(false); setShowSubtitleButton(true)
                }
            },
            update = { view ->
                SubtitleStyling.apply(view.subtitleView, subtitleStyle)
                view.useController = showControls && !s.drivingLocked
                // Don't let the phone screen time out mid-video.
                view.keepScreenOn = s.wantsToPlay && !s.carActive
                val wanted = if (s.carActive) null else graph.hub.player
                if (view.player !== wanted) view.player = wanted
            },
            onRelease = { it.player = null },
        )
        // On a car screen (Automotive OS / head unit) video is hidden while moving.
        if (s.drivingLocked && !s.carActive) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(
                    if (s.audioOnly) "♪  ${S.audioOnlyDriving}\n${S.audioOnlyPark}" else S.pausedMoving,
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

private sealed interface LoadState {
    data object Loading : LoadState
    data class Loaded(val items: List<VideoItem>) : LoadState
    data class Failed(val message: String) : LoadState
}

@Composable
private fun SourceTab(graph: AppGraph, source: VideoSource, refreshKey: String) {
    if (source is LocalSource) {
        var granted by remember { mutableStateOf(source.hasPermission()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = source.hasPermission()
        }
        if (!granted) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(S.allowLocalText)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    launcher.launch(source.requiredPermissions)
                }) { Text(S.allowVideoAccess) }
            }
            return
        }
    }

    val feeds = remember(source, refreshKey) { source.feeds() }
    var feedKey by rememberSaveable(source.id) { mutableStateOf(feeds.firstOrNull()?.key) }
    var query by rememberSaveable(source.id) { mutableStateOf("") }
    var activeQuery by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    val feed = feeds.firstOrNull { it.key == feedKey } ?: feeds.firstOrNull()

    val load by produceState<LoadState>(LoadState.Loading, source, feed, activeQuery, reload) {
        value = LoadState.Loading
        value = try {
            LoadState.Loaded(activeQuery?.let { source.search(it) } ?: feed?.let { source.load(it) } ?: emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoadState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (source.supportsSearch) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                singleLine = true,
                placeholder = { Text(S.searchIn(S.sourceName(source.id))) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { activeQuery = query.trim().ifEmpty { null } }),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            feeds.forEach { f ->
                FilterChip(
                    selected = activeQuery == null && f.key == feed?.key,
                    onClick = {
                        activeQuery = null; query = ""
                        if (f.key == feed?.key) reload++ else feedKey = f.key
                    },
                    label = { Text(f.title) },
                )
            }
        }
        when (val l = load) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is LoadState.Failed -> Column(Modifier.fillMaxWidth().padding(24.dp)) {
                Text(l.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { reload++ }) { Text(S.retry) }
            }
            is LoadState.Loaded -> if (l.items.isEmpty()) {
                Text(S.noVideos, Modifier.padding(24.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(l.items, key = { i, it -> "$i:${it.id}" }) { i, item ->
                        VideoRow(graph, item, onClick = { graph.hub.playQueue(l.items, i) }) {
                            IconButton(onClick = { adding = item }) {
                                Icon(painterResource(R.drawable.ic_playlist_add), S.addToPlaylist)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun VideoRow(graph: AppGraph, item: VideoItem, onClick: () -> Unit, trailing: @Composable () -> Unit = {}) {
    val thumb by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.72f))
            .clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(140.dp).aspectRatio(16f / 9f).clip(MaterialTheme.shapes.medium)
                .background(Brush.linearGradient(listOf(Sakura.Dusk, Color(0xFF4B2378)))),
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            val d = formatDuration(item.durationSec)
            if (d.isNotEmpty()) Text(
                d, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xCC1A1446)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            item.author?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
        }
        trailing()
    }
}
