package app.pitstop.video.ui

import app.pitstop.video.player.SubtitleStyling
import app.pitstop.video.core.Settings
import app.pitstop.video.core.PlayerStyle
import app.pitstop.video.core.S
import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import app.pitstop.video.car.CarConnections
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.runtime.staticCompositionLocalOf
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
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoQuality
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.formatDuration
import app.pitstop.video.local.PermissionSource
import app.pitstop.video.core.feedsWith
import app.pitstop.video.core.loadFeed
import app.pitstop.video.core.loadFeedPage
import androidx.compose.material3.InputChip
import app.pitstop.video.core.asFeed
import app.pitstop.video.core.FeedPage
import app.pitstop.video.core.Category
import app.pitstop.video.core.ListView
import app.pitstop.video.core.Picked
import app.pitstop.video.player.DisplayMode
import app.pitstop.video.player.HubState
import app.pitstop.video.player.RepeatMode
import kotlinx.coroutines.CancellationException

/** Each time the car connects while this screen is open: a reminder not to look at the phone while driving. */
@Composable
private fun DriveSafelyReminder() {
    val connections by CarConnections.count.collectAsStateWithLifecycle()
    // Connections that happened before this screen appeared were already shown on the car screen.
    var seen by rememberSaveable { mutableStateOf(connections) }
    // On the car screen itself (parked app): once each time the app opens there.
    val carInstance = LocalCarInstance.current
    var shownOnCar by rememberSaveable { mutableStateOf(false) }
    if (connections > seen || (carInstance && !shownOnCar)) {
        val dismiss = { seen = connections; shownOnCar = true }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(S.driveSafelyTitle) },
            text = { Text(S.driveSafely) },
            confirmButton = { TextButton(onClick = dismiss) { Text(S.ok) } },
        )
    }
}

/** True inside the activity that Android Auto shows on the car screen (parked app). */
val LocalCarInstance = staticCompositionLocalOf { false }

/** The video is shown by another screen: the car template surface, or the car activity while this is the phone. */
@Composable
fun HubState.shownElsewhere(): Boolean = carActive || (onCarDisplay && !LocalCarInstance.current)

/**
 * The car screen in "video only" mode: the picture fills the screen (cover art for music). Tapping
 * shows the player's own controls and a button that brings the whole app back.
 */
@Composable
private fun CarVideoOnly(graph: AppGraph) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    DriveSafelyReminder()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            s.current == null -> Text(
                S.carPickOnPhone, Modifier.align(Alignment.Center),
                color = Color.White, style = MaterialTheme.typography.titleLarge,
            )
            s.isMusic -> MusicStage(graph, Modifier.fillMaxSize())
            else -> PhonePlayer(graph, Modifier.fillMaxSize(), showControls = true)
        }
        IconButton(onClick = { Settings.setCarVideoOnly(false) }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
            Icon(painterResource(R.drawable.ic_split), contentDescription = S.carShowApp, tint = Color.White.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun AppRoot(graph: AppGraph, inPip: Boolean, fullscreen: Boolean, onFullscreenChange: (Boolean) -> Unit) {
    if (inPip) {
        PhonePlayer(graph, Modifier.fillMaxSize(), showControls = false)
        return
    }
    if (LocalCarInstance.current && Settings.carVideoOnly.collectAsStateWithLifecycle().value) {
        CarVideoOnly(graph)
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

    DriveSafelyReminder()
    // Only the platforms the user chose to show (Settings → Platforms, or the button after the chips).
    val hidden by Settings.hiddenSources.collectAsStateWithLifecycle()
    val videoSources = remember(hidden) { graph.shownVideoSources }
    // Top level: Video or Music (each with its sources below), plus Playlists and Settings.
    var page by rememberSaveable { mutableStateOf(Page.VIDEO) }
    var videoSourceId by rememberSaveable { mutableStateOf(videoSources.first().id) }
    val liveSources = remember(hidden) { graph.shownLiveSources }
    var liveSourceId by rememberSaveable { mutableStateOf(liveSources.firstOrNull()?.id) }
    // Search everywhere → More: switch platform and run the same search there.
    var pendingQuery by remember { mutableStateOf<String?>(null) }
    val state by graph.hub.state.collectAsStateWithLifecycle()
    // Re-read feed lists after sign-in / sign-out.
    val ytSignedIn by graph.youTubeAccount.signedIn.collectAsStateWithLifecycle()
    val biliLoggedIn by graph.biliCookies.loggedIn.collectAsStateWithLifecycle()

    val browser: @Composable () -> Unit = {
        Column {
            AppHeader(page, onPage = { page = it })
            when (page) {
                Page.VIDEO -> {
                    val index = videoSources.indexOfFirst { it.id == videoSourceId }.coerceAtLeast(0)
                    SourceChips(videoSources.map { S.sourceName(it.id) }, index) { videoSourceId = videoSources[it].id }
                    SourceTab(
                        graph, videoSources[index], refreshKey = "$ytSignedIn/$biliLoggedIn",
                        siblings = videoSources, pendingQuery = pendingQuery, onPendingConsumed = { pendingQuery = null },
                        onSearchIn = { s, q -> videoSourceId = s.id; pendingQuery = q },
                    )
                }
                Page.LIVE -> if (liveSources.isNotEmpty()) {
                    val index = liveSources.indexOfFirst { it.id == liveSourceId }.coerceAtLeast(0)
                    SourceChips(liveSources.map { S.sourceName(it.id) }, index) { liveSourceId = liveSources[it].id }
                    SourceTab(
                        graph, liveSources[index], refreshKey = "$ytSignedIn/$biliLoggedIn",
                        siblings = liveSources, pendingQuery = pendingQuery, onPendingConsumed = { pendingQuery = null },
                        onSearchIn = { s, q -> liveSourceId = s.id; pendingQuery = q },
                    )
                }
                Page.MUSIC -> MusicTab(graph)
                Page.PLAYLISTS -> PlaylistsTab(graph)
                Page.SETTINGS -> AccountScreen(graph)
            }
        }
    }

    AppBackground(Modifier.fillMaxSize()) {
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
                    Box(Modifier.weight(0.45f).fillMaxHeight()) { browser() }
                }
            } else {
                // Narrow phones: library and player are separate screens. A mini bar under the
                // library opens the player; a new video opens it by itself (it needs to be seen).
                var playerOpen by rememberSaveable { mutableStateOf(false) }
                var autoOpenedFor by rememberSaveable { mutableStateOf<String?>(null) }
                val current = state.current
                val style by Settings.playerStyle.collectAsStateWithLifecycle()
                val floating = style == PlayerStyle.FLOATING
                LaunchedEffect(current?.id, state.isMusic, floating) {
                    if (current == null) playerOpen = false
                    // The floating window already shows the video, so only the page style auto-opens.
                    else if (!floating && !state.isMusic && autoOpenedFor != current.id) { autoOpenedFor = current.id; playerOpen = true }
                }
                // The library stays composed under the player, so open folders / scroll survive.
                Box(Modifier.fillMaxSize()) {
                    Column {
                        BoxWithConstraints(Modifier.weight(1f)) {
                            browser()
                            if (current != null && floating && !playerOpen) FloatingPlayer(graph, onOpen = { playerOpen = true })
                        }
                        if (current != null && !floating) MiniPlayer(graph, onOpen = { playerOpen = true })
                    }
                    if (playerOpen && current != null) AppBackground(Modifier.fillMaxSize()) {
                        PlayerScreen(graph, onClose = { playerOpen = false }, onFullscreen = { onFullscreenChange(true) })
                    }
                }
            }
        }
    }
    }
}

private enum class Page { VIDEO, MUSIC, LIVE, PLAYLISTS, SETTINGS }

/** One row: avatar, the Video / Music switch, then Playlists and Settings. */
@Composable
private fun AppHeader(page: Page, onPage: (Page) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(36.dp)
        Spacer(Modifier.width(12.dp))
        SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
            listOf(Page.VIDEO to S.video, Page.MUSIC to S.music, Page.LIVE to S.live).forEachIndexed { i, (p, label) ->
                SegmentedButton(
                    selected = page == p,
                    onClick = { onPage(p) },
                    shape = SegmentedButtonDefaults.itemShape(i, 3),
                    icon = {},
                ) { Text(label, maxLines = 1) }
            }
        }
        Spacer(Modifier.width(4.dp))
        val on = MaterialTheme.colorScheme.primary
        val off = MaterialTheme.colorScheme.onSurfaceVariant
        IconButton(onClick = { onPage(Page.PLAYLISTS) }) {
            Icon(painterResource(R.drawable.ic_playlist_play), S.playlists, tint = if (page == Page.PLAYLISTS) on else off)
        }
        IconButton(onClick = { onPage(Page.SETTINGS) }) {
            Icon(painterResource(R.drawable.ic_settings), S.account, tint = if (page == Page.SETTINGS) on else off)
        }
    }
}

/** Two-pane layout before anything plays. */
@Composable
private fun EmptyStage() {
    Column(
        Modifier.padding(16.dp).fillMaxWidth().aspectRatio(16f / 9f)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(painterResource(R.drawable.ic_play), null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(12.dp))
        Text(S.pickVideo, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(S.pickVideoHint, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/** Play / pause: the one filled control on the screen. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(52.dp)) {
        Icon(painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play), S.playPause)
    }
}

@Composable
internal fun NowPlayingCard(graph: AppGraph, onFullscreen: () -> Unit) {
    val s by graph.hub.state.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    var downloading by remember { mutableStateOf<VideoItem?>(null) }
    downloading?.let { DownloadDialog(graph, it) { downloading = null } }
    var showQueue by remember { mutableStateOf(false) }
    if (showQueue) QueueDialog(graph) { showQueue = false }
    val elsewhere = s.shownElsewhere()
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (elsewhere) {
            Box(
                Modifier.fillMaxWidth().height(96.dp).clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (s.audioOnly) S.audioOnlyPhone else if (s.drivingLocked) S.pausedCarMoving else S.playingOnCar,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        } else {
            if (s.isMusic) MusicStage(graph, Modifier.fillMaxWidth())
            else PhonePlayer(graph, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(MaterialTheme.shapes.large), showControls = true)
        }
        Spacer(Modifier.height(if (s.isMusic) 4.dp else 10.dp))
        // Songs show title + artist inside the music card; only loading / errors appear here.
        if (!s.isMusic || s.error != null || s.loading) Column(Modifier.padding(horizontal = 4.dp)) {
            if (!s.isMusic) Text(s.current?.title.orEmpty(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            val sub = s.error ?: if (s.loading) S.loading else s.current?.author.orEmpty()
            Text(sub, maxLines = 2, style = MaterialTheme.typography.bodySmall,
                color = if (s.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // While a video plays as audio only there is no video view (and no button on it): offer the switch here.
        if (s.noPicture && hasQualityChoice(s.current)) {
            Box(Modifier.padding(horizontal = 4.dp)) { QualityButton(graph) }
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = graph.hub::previous) { Icon(painterResource(R.drawable.ic_skip_previous), S.previous) }
            PlayButton(s.wantsToPlay, graph.hub::togglePause)
            IconButton(onClick = graph.hub::next) { Icon(painterResource(R.drawable.ic_skip_next), S.next) }
            // Lit (accent colour) when on; repeat cycles off → all → one.
            val on = MaterialTheme.colorScheme.primary
            val off = MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(onClick = graph.hub::toggleShuffle) {
                Icon(painterResource(R.drawable.ic_shuffle), S.shuffle, tint = if (s.shuffle) on else off)
            }
            IconButton(onClick = graph.hub::cycleRepeat) {
                Icon(
                    painterResource(if (s.repeat == RepeatMode.ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat),
                    when (s.repeat) { RepeatMode.OFF -> S.repeatOff; RepeatMode.ALL -> S.repeatAll; RepeatMode.ONE -> S.repeatOne },
                    tint = if (s.repeat == RepeatMode.OFF) off else on,
                )
            }
            IconButton(onClick = { showQueue = true }) { Icon(painterResource(R.drawable.ic_playlist_play), S.queue) }
            s.current?.let { cur ->
                IconButton(onClick = { adding = cur }) { Icon(painterResource(R.drawable.ic_playlist_add), S.addToPlaylist) }
                if (graph.downloads.canDownload(cur)) {
                    IconButton(onClick = { downloading = cur }) { Icon(painterResource(R.drawable.ic_download), S.download) }
                }
            }
            if (s.carActive) {
                IconButton(onClick = graph.hub::toggleMode) {
                    Icon(painterResource(if (s.mode == DisplayMode.FULL) R.drawable.ic_split else R.drawable.ic_fullscreen), S.carLayout)
                }
            } else if (!s.isMusic) {
                IconButton(onClick = onFullscreen) { Icon(painterResource(R.drawable.ic_fullscreen), S.fullScreen) }
            }
        }
    }
}

/** Only streamed videos have a quality to choose (music and local files don't). */
private fun hasQualityChoice(item: VideoItem?) = item != null && !item.source.music && item.source != SourceId.LOCAL

/** Quick quality / data-saver switch for the network in use (Wi-Fi or mobile data); also in Settings. */
@Composable
private fun QualityButton(graph: AppGraph, onVideo: Boolean = false) {
    val metered by graph.hub.onMeteredNetwork.collectAsStateWithLifecycle()
    val wifi by Settings.qualityWifi.collectAsStateWithLifecycle()
    val mobile by Settings.qualityMobile.collectAsStateWithLifecycle()
    val current = if (metered) mobile else wifi
    var open by remember { mutableStateOf(false) }
    Box {
        if (onVideo) {
            // On the picture, next to the player's own controls: gear + current choice, YouTube style.
            Row(
                Modifier.clip(MaterialTheme.shapes.small).background(Color.Black.copy(alpha = 0.55f))
                    .clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_settings), S.quality, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(qualityLabel(current), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        } else androidx.compose.material3.AssistChip(
            onClick = { open = true },
            label = { Text("${S.quality}: ${qualityLabel(current)}") },
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(S.qualityFor(metered), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
            VideoQuality.entries.forEach { q ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(qualityLabel(q) + if (q == current) "  ✓" else "") },
                    onClick = { Settings.setQuality(metered, q); open = false },
                )
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
    // The quality button shows and hides together with the player's own controls.
    var controlsShown by remember { mutableStateOf(false) }
    val elsewhere = s.shownElsewhere()
    Box(modifier.background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    setShowNextButton(false); setShowPreviousButton(false); setShowSubtitleButton(true)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controlsShown = v == android.view.View.VISIBLE })
                }
            },
            update = { view ->
                SubtitleStyling.apply(view.subtitleView, subtitleStyle)
                view.useController = showControls && !s.drivingLocked
                // Don't let the phone screen time out mid-video.
                view.keepScreenOn = s.wantsToPlay && !elsewhere
                val wanted = if (elsewhere) null else graph.hub.player
                if (view.player !== wanted) view.player = wanted
            },
            onRelease = { it.player = null },
        )
        if (showControls && controlsShown && !s.drivingLocked && !elsewhere && hasQualityChoice(s.current)) {
            Box(Modifier.align(Alignment.TopStart).padding(10.dp)) { QualityButton(graph, onVideo = true) }
        }
        // On a car screen (Automotive OS / head unit) video is hidden while moving.
        if (s.drivingLocked && !elsewhere) {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text(
                    if (s.audioOnly) "${S.audioOnlyDriving}\n${S.audioOnlyPark}" else S.pausedMoving,
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}


@Composable
internal fun SourceTab(
    graph: AppGraph,
    source: VideoSource,
    refreshKey: String,
    /** The other platforms of this tab, for search everywhere. */
    siblings: List<VideoSource> = emptyList(),
    /** A search handed over from search everywhere's More. */
    pendingQuery: String? = null,
    onPendingConsumed: () -> Unit = {},
    onSearchIn: ((VideoSource, String) -> Unit)? = null,
) {
    if (source is PermissionSource) {
        var granted by remember { mutableStateOf(source.hasPermission()) }
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            granted = source.hasPermission()
        }
        if (!granted) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (source.id.music) S.allowMusicText else S.allowLocalText)
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    launcher.launch(source.requiredPermissions)
                }) { Text(if (source.id.music) S.allowMusicAccess else S.allowVideoAccess) }
            }
            return
        }
    }

    // A creator's page replaces the list until Back.
    var creatorId by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var creatorName by rememberSaveable(source.id) { mutableStateOf("") }
    creatorId?.let { id ->
        CreatorPage(graph, source, id, creatorName, onBack = { creatorId = null })
        return
    }

    // Opened folders (Google Drive): the innermost one replaces the list until Back.
    var openFolders by remember(source.id) { mutableStateOf(listOf<VideoItem>()) }
    openFolders.lastOrNull()?.let { folder ->
        FolderPage(graph, source, folder, onOpen = { openFolders = openFolders + it }, onBack = { openFolders = openFolders.dropLast(1) })
        return
    }

    val savedCreators by graph.creators.creators.collectAsStateWithLifecycle()
    val feeds = remember(source, refreshKey, savedCreators) { source.feedsWith(graph.creators) }
    var feedKey by rememberSaveable(source.id) { mutableStateOf(feeds.firstOrNull()?.key) }
    var query by rememberSaveable(source.id) { mutableStateOf("") }
    var activeQuery by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    // A category picked in the Categories window replaces the feed until it's cleared.
    var categoryKey by rememberSaveable(source.id) { mutableStateOf<String?>(null) }
    var categoryTitle by rememberSaveable(source.id) { mutableStateOf("") }
    var everywhere by rememberSaveable(source.id) { mutableStateOf(false) }
    LaunchedEffect(pendingQuery, source.id) {
        pendingQuery?.let { query = it; activeQuery = it; everywhere = false; categoryKey = null; onPendingConsumed() }
    }
    var reload by remember { mutableIntStateOf(0) }
    var adding by remember { mutableStateOf<VideoItem?>(null) }
    adding?.let { AddToPlaylistDialog(graph, it) { adding = null } }
    val feed = feeds.firstOrNull { it.key == feedKey } ?: feeds.firstOrNull()

    // The platform's categories (loaded once per platform); no button when there are none.
    val categories by produceState(emptyList<Category>(), source.id, refreshKey) {
        value = runCatching { source.categories() }.getOrDefault(emptyList())
    }
    var showCategories by remember { mutableStateOf(false) }
    // Sort & filter: platform options (search only) reload the list; the in-app view sorts / filters what loaded.
    var searchPicked by remember(source.id) { mutableStateOf<Picked>(emptyMap()) }
    var view by remember(source.id) { mutableStateOf(ListView()) }
    var showSortFilter by remember { mutableStateOf(false) }
    val platformGroups = if (activeQuery != null) remember(source.id) { source.searchOptions() } else emptyList()
    if (showSortFilter) SortFilterDialog(
        S.sourceName(source.id), platformGroups, searchPicked, view,
        onApply = { p, v -> searchPicked = p; view = v }, onDismiss = { showSortFilter = false },
    )
    if (showCategories) CategoryDialog(graph, source, categories, categoryKey, onPick = { c ->
        categoryKey = c.key; categoryTitle = c.title; activeQuery = null; showCategories = false
    }, onDismiss = { showCategories = false })

    Column(Modifier.fillMaxSize()) {
        if (source.supportsSearch) {
            SearchField(source, query, onQuery = { query = it }, onSearch = { activeQuery = it })
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val browsingFeed = activeQuery == null && categoryKey == null
            if (!(activeQuery != null && everywhere)) {
                SortFilterChip(active = !view.isDefault || (activeQuery != null && searchPicked.isNotEmpty())) { showSortFilter = true }
            }
            // Active search first, with an x to go back to browsing.
            activeQuery?.let { q ->
                InputChip(
                    selected = true, onClick = { activeQuery = null; query = "" },
                    label = { Text("${S.searchResults}: $q", maxLines = 1) },
                    trailingIcon = { Icon(painterResource(R.drawable.ic_close), S.clearHistory, Modifier.size(16.dp)) },
                )
                // Same search on every platform of this tab.
                if (siblings.count { it.supportsSearch } > 1) FilterChip(
                    selected = everywhere, onClick = { everywhere = !everywhere },
                    label = { Text(S.searchEverywhere) },
                )
            }
            // Categories: opens the window with every category; shows the picked one (x clears it).
            if (categories.isNotEmpty()) {
                FilterChip(
                    selected = categoryKey != null && activeQuery == null,
                    onClick = { showCategories = true },
                    label = { Text(if (categoryKey != null) categoryTitle else S.categories, maxLines = 1) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_category), null, Modifier.size(16.dp)) },
                    trailingIcon = if (categoryKey != null) {
                        { Icon(painterResource(R.drawable.ic_close), S.clearCategory, Modifier.size(16.dp).clickable { categoryKey = null }) }
                    } else null,
                )
            }
            feeds.forEach { f ->
                FilterChip(
                    selected = browsingFeed && f.key == feed?.key,
                    onClick = {
                        activeQuery = null; query = ""; categoryKey = null
                        if (f.key == feed?.key && browsingFeed) reload++ else feedKey = f.key
                    },
                    label = { Text(f.title) },
                )
            }
        }
        val q = activeQuery
        val cat = categoryKey?.let { Category(it, categoryTitle).asFeed() }
        if (q != null && everywhere) {
            EverywhereResults(
                graph, siblings, q,
                onMore = { s -> if (s.id == source.id) everywhere = false else onSearchIn?.invoke(s, q) },
                onFolder = { s, item -> if (s.id == source.id) openFolders = openFolders + item else onSearchIn?.invoke(s, q) },
            )
            return@Column
        }
        PagedVideoList(
            graph, source,
            loadKey = listOf(source.id, q, cat?.key, feed?.key, reload, refreshKey, if (q != null) searchPicked else null),
            view = view,
            page = { cursor ->
                when {
                    q != null -> source.searchPage(q, cursor, searchPicked)
                    cat != null -> source.loadFeedPage(cat, cursor)
                    feed != null -> source.loadFeedPage(feed, cursor)
                    else -> FeedPage(emptyList())
                }
            },
            onFolder = { openFolders = openFolders + it },
            onCreator = { item -> creatorId = item.authorId; creatorName = item.author.orEmpty() },
            onAdd = { adding = it },
        )
    }
}

@Composable
fun VideoRow(
    graph: AppGraph,
    item: VideoItem,
    onClick: () -> Unit,
    onAuthor: (() -> Unit)? = null,
    /** Long-press: the item's menu (play next, add to queue, playlists, download). */
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** Shown before the cover, e.g. a drag handle; the row's left padding shrinks to make room. */
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val thumb by produceState(graph.thumbnails.cached(item), item) { value = graph.thumbnails.load(item) }
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Row(
        modifier.fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { l -> { haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress); l() } },
            )
            .padding(start = if (leading != null) 0.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Box(
            // Songs and playlists get square covers; videos 16:9.
            Modifier.width(if (item.source.music) 76.dp else 140.dp).aspectRatio(if (item.source.music) 1f else 16f / 9f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            val d = formatDuration(item.durationSec)
            if (d.isNotEmpty()) Text(
                d, color = Color.White, style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp)
                    .clip(MaterialTheme.shapes.extraSmall).background(Color(0xCC000000)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            item.author?.let {
                Spacer(Modifier.height(2.dp))
                // Tappable creator name (in the accent colour) opens their videos.
                Text(
                    it, maxLines = 1, style = MaterialTheme.typography.bodySmall,
                    color = if (onAuthor != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = if (onAuthor != null) Modifier.clickable(onClick = onAuthor).padding(vertical = 4.dp) else Modifier,
                )
            }
        }
        trailing()
    }
}
