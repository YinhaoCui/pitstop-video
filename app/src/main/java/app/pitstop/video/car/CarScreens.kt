package app.pitstop.video.car

import app.pitstop.video.core.feedsWith
import app.pitstop.video.core.loadFeed

import android.graphics.Bitmap
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pitstop.video.R
import app.pitstop.video.core.I18n
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.formatDuration
import app.pitstop.video.graph
import app.pitstop.video.player.DisplayMode
import app.pitstop.video.player.PlaybackHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun CarContext.icon(@DrawableRes res: Int) = CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

private fun backHeader(title: String) = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()

private fun CarContext.listLimit(default: Int = 6): Int = runCatching {
    getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
}.getOrDefault(default)

private fun CarContext.playPause(hub: PlaybackHub) = Action.Builder()
    .setIcon(icon(if (hub.state.value.wantsToPlay) R.drawable.ic_pause else R.drawable.ic_play))
    .setOnClickListener { hub.togglePause() }.build()

/**
 * Lists open as a side panel over the still-playing (shrunk) video when the host supports it
 * (car API 7+, MapWithContentTemplate); older hosts show them full screen.
 */
private fun CarContext.panel(content: Template, hub: PlaybackHub, extra: List<Action> = emptyList()): Template {
    if (carAppApiLevel < 7) return content
    val strip = ActionStrip.Builder().addAction(playPause(hub))
    extra.forEach(strip::addAction)
    return MapWithContentTemplate.Builder().setContentTemplate(content).setActionStrip(strip.build()).build()
}

/** Screens that should refresh when playback state or the UI language changes. */
private fun Screen.refreshOn(hub: PlaybackHub, key: (app.pitstop.video.player.HubState) -> Any?) {
    lifecycleScope.launch {
        repeatOnLifecycle(Lifecycle.State.STARTED) {
            combine(hub.state.map(key).distinctUntilChanged(), I18n.language) { _, _ -> Unit }
                .collect { invalidate() }
        }
    }
}

/**
 * Root screen. NavigationTemplate is the only template that hands a third-party app the full
 * drawing surface, which is where [CarVideoRenderer] draws the video.
 */
class PlayerScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    init {
        refreshOn(hub) { listOf(it.wantsToPlay, it.mode, it.drivingLocked, it.subtitle != null, it.subtitleOptions.isNotEmpty()) }
        // Any other screen on top of this one is a panel / list: shrink the video beside it.
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = hub.setPanelOpen(false)
            override fun onStop(owner: LifecycleOwner) = hub.setPanelOpen(true)
        })
    }

    override fun onGetTemplate(): Template {
        val s = hub.state.value
        val t = I18n.s
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle(t.browse).setIcon(carContext.icon(R.drawable.ic_list))
                    .setOnClickListener { screenManager.push(BrowseScreen(carContext, hub)) }.build()
            )
            .addAction(carContext.playPause(hub))
            .addAction(
                Action.Builder()
                    .setTitle(if (s.mode == DisplayMode.FULL) t.split else t.full)
                    .setIcon(carContext.icon(if (s.mode == DisplayMode.FULL) R.drawable.ic_split else R.drawable.ic_fullscreen))
                    .setOnClickListener { hub.toggleMode() }.build()
            )
            .addAction(
                Action.Builder().setTitle(t.queue).setIcon(carContext.icon(R.drawable.ic_playlist_play))
                    .setOnClickListener { screenManager.push(QueueScreen(carContext, hub)) }.build()
            )
            .build()
        // Right column (max 4): seek back, seek forward, subtitles on/off, shrink to Browse.
        val side = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_replay_10)).setOnClickListener { hub.seekBy(-10_000) }.build())
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_forward_10)).setOnClickListener { hub.seekBy(10_000) }.build())
            .addAction(
                Action.Builder()
                    .setIcon(carContext.icon(if (s.subtitle != null) R.drawable.ic_subtitles else R.drawable.ic_subtitles_off))
                    .setOnClickListener { hub.toggleSubtitles() }.build()
            )
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_close)).setOnClickListener {
                screenManager.push(BrowseScreen(carContext, hub))
            }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(actions).setMapActionStrip(side).build()
    }
}

/** The current playlist / queue (including Bilibili parts). Tap an entry to jump to it. */
class QueueScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    init {
        refreshOn(hub) { listOf(it.queue, it.index, it.subtitle, it.subtitleOptions, it.wantsToPlay) }
    }

    override fun onGetTemplate(): Template {
        val s = hub.state.value
        val t = I18n.s
        val limit = carContext.listLimit()
        // Show the window of the queue around what's playing (one before, then what's next).
        val start = (s.index - 1).coerceAtLeast(0).coerceAtMost((s.queue.size - limit).coerceAtLeast(0))
        val list = ItemList.Builder().setNoItemsMessage(t.nothingQueued)
        s.queue.withIndex().drop(start).take(limit).forEach { (i, item) ->
            val row = Row.Builder().setTitle(item.title.ifBlank { "—" })
            val sub = listOfNotNull(
                if (i == s.index) "▶ ${t.nowPlaying}" else null,
                item.author?.takeIf { it.isNotBlank() },
                formatDuration(item.durationSec).ifEmpty { null },
            )
            if (sub.isNotEmpty()) row.addText(sub.joinToString(" · "))
            if (i == s.index) row.setImage(carContext.icon(R.drawable.ic_play))
            row.setOnClickListener {
                if (i != s.index) hub.playQueue(s.queue, i)
                screenManager.pop()
            }
            list.addItem(row.build())
        }
        val title = if (s.queue.isEmpty()) t.queue else t.queueCount(s.index + 1, s.queue.size)
        val content = ListTemplate.Builder().setHeader(backHeader(title)).setSingleList(list.build()).build()
        val extra = buildList {
            add(Action.Builder().setIcon(carContext.icon(R.drawable.ic_skip_next)).setOnClickListener { hub.next() }.build())
            if (s.subtitleOptions.isNotEmpty()) {
                // Language choice: Off -> each language -> Off.
                add(
                    Action.Builder().setTitle(t.cc(s.subtitle)).setIcon(carContext.icon(R.drawable.ic_subtitles))
                        .setOnClickListener { hub.cycleSubtitles() }.build()
                )
            }
        }
        return carContext.panel(content, hub, extra)
    }
}

/** Source picker: YouTube / Bilibili / On this device / Playlists, and Exit. */
class BrowseScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    init {
        refreshOn(hub) { listOf(it.wantsToPlay, it.upNext.size) }
    }

    override fun onGetTemplate(): Template {
        val t = I18n.s
        val list = ItemList.Builder()
        val graph = carContext.graph
        (graph.shownVideoSources + graph.shownLiveSources + graph.shownMusicSources).forEach { src ->
            list.addItem(
                Row.Builder().setTitle(t.sourceName(src.id)).setBrowsable(true)
                    .setImage(carContext.icon(when { src.id.music -> R.drawable.ic_music; src.id == SourceId.LOCAL -> R.drawable.ic_folder; else -> R.drawable.ic_video }))
                    .setOnClickListener { screenManager.push(SourceScreen(carContext, hub, src)) }.build()
            )
        }
        list.addItem(
            Row.Builder().setTitle(t.playlists).setBrowsable(true)
                .setImage(carContext.icon(R.drawable.ic_playlist_play))
                .setOnClickListener { screenManager.push(CarPlaylistsScreen(carContext, hub)) }.build()
        )
        val upNext = hub.state.value.upNext
        if (upNext.isNotEmpty()) {
            list.addItem(
                Row.Builder().setTitle(t.upNextCount(upNext.size)).setBrowsable(true)
                    .setImage(carContext.icon(R.drawable.ic_list))
                    .setOnClickListener { screenManager.push(QueueScreen(carContext, hub)) }.build()
            )
        }
        // Android Auto keeps navigation-category apps alive in the map slot; this is the real way out.
        list.addItem(
            Row.Builder().setTitle(t.exitApp).setImage(carContext.icon(R.drawable.ic_close))
                .setOnClickListener {
                    hub.player.pause()
                    carContext.finishCarApp()
                }.build()
        )
        return carContext.panel(ListTemplate.Builder().setHeader(backHeader(t.browse)).setSingleList(list.build()).build(), hub)
    }
}

/** Search + feeds for one source. */
class SourceScreen(carContext: CarContext, private val hub: PlaybackHub, private val source: VideoSource) : Screen(carContext) {
    init {
        refreshOn(hub) { it.wantsToPlay }
    }

    override fun onGetTemplate(): Template {
        val t = I18n.s
        val name = t.sourceName(source.id)
        val list = ItemList.Builder()
        if (source.supportsSearch) {
            list.addItem(
                Row.Builder().setTitle(t.search).setImage(carContext.icon(R.drawable.ic_search)).setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarSearchScreen(carContext, hub, source)) }.build()
            )
        }
        source.feedsWith(carContext.graph.creators).forEach { feed ->
            list.addItem(
                Row.Builder().setTitle(feed.title).setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(ResultsScreen(carContext, hub, "$name · ${feed.title}") { source.loadFeed(feed) })
                    }.build()
            )
        }
        return carContext.panel(ListTemplate.Builder().setHeader(backHeader(name)).setSingleList(list.build()).build(), hub)
    }
}

/** On-screen keyboard search (Android Auto only offers the keyboard while parked). */
class CarSearchScreen(carContext: CarContext, private val hub: PlaybackHub, private val source: VideoSource) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val t = I18n.s
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = Unit
            override fun onSearchSubmitted(searchText: String) {
                val q = searchText.trim()
                if (q.isNotEmpty()) screenManager.push(ResultsScreen(carContext, hub, t.searchFor(q)) { source.search(q) })
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(t.searchIn(t.sourceName(source.id)))
            .setShowKeyboardByDefault(true)
            .setItemList(ItemList.Builder().setNoItemsMessage(t.typeSearch).build())
            .build()
    }
}

/** A list of videos; picking one plays it (and queues the rest) and returns to the full-size video. */
class ResultsScreen(
    carContext: CarContext,
    private val hub: PlaybackHub,
    private val title: String,
    private val loader: suspend () -> List<VideoItem>,
) : Screen(carContext) {
    private var items: List<VideoItem>? = null
    private var error: String? = null
    private val thumbs = mutableMapOf<String, Bitmap>()

    init {
        refreshOn(hub) { it.wantsToPlay }
        lifecycleScope.launch {
            try {
                items = loader()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            invalidate()
            val shown = items.orEmpty().take(carContext.listLimit())
            val loader = carContext.graph.thumbnails
            shown.map { item -> async { loader.load(item, 240)?.let { thumbs[item.id] = it } } }.awaitAll()
            if (thumbs.isNotEmpty()) invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        error?.let {
            return carContext.panel(MessageTemplate.Builder(it).setHeader(backHeader(title)).build(), hub)
        }
        val loaded = items
            ?: return carContext.panel(ListTemplate.Builder().setHeader(backHeader(title)).setLoading(true).build(), hub)

        val list = ItemList.Builder().setNoItemsMessage(I18n.s.noVideos)
        val shown = loaded.take(carContext.listLimit())
        shown.forEachIndexed { i, item ->
            val row = Row.Builder()
                .setTitle(item.title.ifBlank { "—" })
                .setOnClickListener {
                    hub.playQueue(loaded, i)
                    screenManager.popToRoot()
                }
            val sub = listOfNotNull(item.author?.takeIf { it.isNotBlank() }, formatDuration(item.durationSec).ifEmpty { null })
            if (sub.isNotEmpty()) row.addText(sub.joinToString(" · "))
            thumbs[item.id]?.let {
                row.setImage(CarIcon.Builder(IconCompat.createWithBitmap(it)).build(), Row.IMAGE_TYPE_LARGE)
            }
            list.addItem(row.build())
        }
        return carContext.panel(ListTemplate.Builder().setHeader(backHeader(title)).setSingleList(list.build()).build(), hub)
    }
}

/** The user's playlists (created on the phone). Picking one lists its videos; tapping a video plays from there. */
class CarPlaylistsScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    init {
        refreshOn(hub) { it.wantsToPlay }
    }

    override fun onGetTemplate(): Template {
        val t = I18n.s
        val store = carContext.graph.playlists
        val list = ItemList.Builder().setNoItemsMessage(t.noPlaylistsCar)
        store.playlists.value.take(carContext.listLimit()).forEach { p ->
            val row = Row.Builder().setTitle(p.name).addText(t.videoCount(p.items.size))
            if (p.items.isEmpty()) {
                row.setOnClickListener { }
            } else {
                row.setBrowsable(true).setOnClickListener {
                    screenManager.push(ResultsScreen(carContext, hub, p.name) { store.get(p.id)?.items.orEmpty() })
                }
            }
            list.addItem(row.build())
        }
        return carContext.panel(ListTemplate.Builder().setHeader(backHeader(t.playlists)).setSingleList(list.build()).build(), hub)
    }
}
