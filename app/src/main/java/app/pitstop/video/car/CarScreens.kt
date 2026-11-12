package app.pitstop.video.car

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
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import app.pitstop.video.R
import app.pitstop.video.core.VideoItem
import app.pitstop.video.core.VideoSource
import app.pitstop.video.core.formatDuration
import app.pitstop.video.graph
import app.pitstop.video.player.DisplayMode
import app.pitstop.video.player.PlaybackHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private fun CarContext.icon(@DrawableRes res: Int) = CarIcon.Builder(IconCompat.createWithResource(this, res)).build()

private fun backHeader(title: String) = Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK).build()

private fun CarContext.listLimit(default: Int = 6): Int = runCatching {
    getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
}.getOrDefault(default)

/**
 * Root screen. NavigationTemplate is the only template that hands a third-party app the full
 * drawing surface, which is where [CarVideoRenderer] draws the video.
 */
class PlayerScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                hub.state.map { Triple(it.wantsToPlay, it.mode, it.drivingLocked) }
                    .distinctUntilChanged()
                    .collect { invalidate() }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val s = hub.state.value
        val actions = ActionStrip.Builder()
            .addAction(
                Action.Builder().setTitle("Browse").setIcon(carContext.icon(R.drawable.ic_list))
                    .setOnClickListener { screenManager.push(BrowseScreen(carContext, hub)) }.build()
            )
            .addAction(
                Action.Builder()
                    .setIcon(carContext.icon(if (s.wantsToPlay) R.drawable.ic_pause else R.drawable.ic_play))
                    .setOnClickListener { hub.togglePause() }.build()
            )
            .addAction(
                Action.Builder()
                    .setTitle(if (s.mode == DisplayMode.FULL) "Split" else "Full")
                    .setIcon(carContext.icon(if (s.mode == DisplayMode.FULL) R.drawable.ic_split else R.drawable.ic_fullscreen))
                    .setOnClickListener { hub.toggleMode() }.build()
            )
            .addAction(
                Action.Builder().setIcon(carContext.icon(R.drawable.ic_skip_next))
                    .setOnClickListener { hub.next() }.build()
            )
            .build()
        val seek = ActionStrip.Builder()
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_replay_10)).setOnClickListener { hub.seekBy(-10_000) }.build())
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_forward_10)).setOnClickListener { hub.seekBy(10_000) }.build())
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_skip_previous)).setOnClickListener { hub.previous() }.build())
            // Android Auto keeps navigation-category apps alive in the map slot; give the driver a real way out.
            .addAction(Action.Builder().setIcon(carContext.icon(R.drawable.ic_close)).setOnClickListener {
                hub.player.pause()
                carContext.finishCarApp()
            }.build())
            .build()
        return NavigationTemplate.Builder().setActionStrip(actions).setMapActionStrip(seek).build()
    }
}

/** Source picker: YouTube / Bilibili / On this phone. */
class BrowseScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        carContext.graph.sources.forEach { src ->
            list.addItem(
                Row.Builder().setTitle(src.displayName).setBrowsable(true)
                    .setImage(carContext.icon(if (src.id.name == "LOCAL") R.drawable.ic_folder else R.drawable.ic_video))
                    .setOnClickListener { screenManager.push(SourceScreen(carContext, hub, src)) }.build()
            )
        }
        list.addItem(
            Row.Builder().setTitle("Playlists").setBrowsable(true)
                .setImage(carContext.icon(R.drawable.ic_playlist_play))
                .setOnClickListener { screenManager.push(CarPlaylistsScreen(carContext, hub)) }.build()
        )
        val upNext = hub.state.value.upNext
        if (upNext.isNotEmpty()) {
            list.addItem(
                Row.Builder().setTitle("Up next (${upNext.size})").setBrowsable(true)
                    .setImage(carContext.icon(R.drawable.ic_list))
                    .setOnClickListener {
                        screenManager.push(ResultsScreen(carContext, hub, "Up next") { hub.state.value.upNext })
                    }.build()
            )
        }
        return ListTemplate.Builder().setHeader(backHeader("Browse")).setSingleList(list.build()).build()
    }
}

/** Search + feeds for one source. */
class SourceScreen(carContext: CarContext, private val hub: PlaybackHub, private val source: VideoSource) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        if (source.supportsSearch) {
            list.addItem(
                Row.Builder().setTitle("Search").setImage(carContext.icon(R.drawable.ic_search)).setBrowsable(true)
                    .setOnClickListener { screenManager.push(CarSearchScreen(carContext, hub, source)) }.build()
            )
        }
        source.feeds().forEach { feed ->
            list.addItem(
                Row.Builder().setTitle(feed.title).setBrowsable(true)
                    .setOnClickListener {
                        screenManager.push(ResultsScreen(carContext, hub, "${source.displayName} · ${feed.title}") { source.load(feed) })
                    }.build()
            )
        }
        return ListTemplate.Builder().setHeader(backHeader(source.displayName)).setSingleList(list.build()).build()
    }
}

/** On-screen keyboard search (Android Auto only offers the keyboard while parked). */
class CarSearchScreen(carContext: CarContext, private val hub: PlaybackHub, private val source: VideoSource) : Screen(carContext) {
    override fun onGetTemplate(): Template =
        SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = Unit
            override fun onSearchSubmitted(searchText: String) {
                val q = searchText.trim()
                if (q.isNotEmpty()) screenManager.push(ResultsScreen(carContext, hub, "Search: $q") { source.search(q) })
            }
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search ${source.displayName}")
            .setShowKeyboardByDefault(true)
            .setItemList(ItemList.Builder().setNoItemsMessage("Type a search and press enter").build())
            .build()
}

/** A list of videos; picking one plays it (and queues the rest) and returns to the player. */
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
            return MessageTemplate.Builder(it).setHeader(backHeader(title)).build()
        }
        val loaded = items ?: return ListTemplate.Builder().setHeader(backHeader(title)).setLoading(true).build()

        val list = ItemList.Builder().setNoItemsMessage("No videos")
        val shown = loaded.take(carContext.listLimit())
        shown.forEachIndexed { i, item ->
            val row = Row.Builder()
                .setTitle(item.title.ifBlank { "(untitled)" })
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
        return ListTemplate.Builder().setHeader(backHeader(title)).setSingleList(list.build()).build()
    }
}

/** The user's playlists (created on the phone). Picking one lists its videos; tapping a video plays from there. */
class CarPlaylistsScreen(carContext: CarContext, private val hub: PlaybackHub) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val store = carContext.graph.playlists
        val list = ItemList.Builder().setNoItemsMessage("No playlists yet – create them in the phone app")
        store.playlists.value.take(carContext.listLimit()).forEach { p ->
            val row = Row.Builder().setTitle(p.name).addText("${p.items.size} video(s)")
            if (p.items.isEmpty()) {
                row.setOnClickListener { }
            } else {
                row.setBrowsable(true).setOnClickListener {
                    screenManager.push(ResultsScreen(carContext, hub, p.name) { store.get(p.id)?.items.orEmpty() })
                }
            }
            list.addItem(row.build())
        }
        return ListTemplate.Builder().setHeader(backHeader("Playlists")).setSingleList(list.build()).build()
    }
}
