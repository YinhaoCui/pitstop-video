package app.pitstop.video.car

import app.pitstop.video.graph

import app.pitstop.video.core.I18n
import app.pitstop.video.core.Settings
import app.pitstop.video.player.SubtitleStyling
import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.pitstop.video.core.formatDuration
import app.pitstop.video.player.DisplayMode
import app.pitstop.video.player.HubState
import app.pitstop.video.player.PlaybackHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * What is drawn on the car screen. Lives on a VirtualDisplay backed by the Android Auto surface.
 * FULL: video fills the whole screen. SPLIT: video on the left, now-playing / up-next panel on the right.
 */
@OptIn(UnstableApi::class)
class CarPresentation(
    outerContext: Context,
    display: Display,
    private val hub: PlaybackHub,
) : Presentation(outerContext, display, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var visibleArea: Rect? = null
    /**
     * Largest visible area seen for this surface. Android Auto shrinks the visible area while its
     * buttons are shown; full-screen video uses this instead so it doesn't jump in size and the
     * buttons simply float over the picture.
     */
    private var fullArea: Rect? = null

    private lateinit var content: LinearLayout
    private lateinit var videoFrame: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var status: TextView
    private lateinit var panel: LinearLayout
    private lateinit var title: TextView
    private lateinit var author: TextView
    private lateinit var progress: ProgressBar
    private lateinit var time: TextView
    private lateinit var upNext: LinearLayout
    private lateinit var lockOverlay: TextView
    private lateinit var fullProgress: ProgressBar
    private lateinit var overlayInfo: LinearLayout
    private lateinit var overlayTitle: TextView
    private lateinit var overlayAuthor: TextView
    /** Songs: artwork + title instead of an empty video area. */
    private lateinit var musicView: LinearLayout
    private lateinit var musicArt: android.widget.ImageView
    private lateinit var musicTitle: TextView
    private lateinit var musicAuthor: TextView
    private var musicArtFor: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context

        playerView = PlayerView(ctx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
            // Seen from the driver's seat: a bit larger than on the phone.
            SubtitleStyling.apply(subtitleView, Settings.subtitles.value, scale = 1.25f)
        }
        status = text(ctx, 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
        fullProgress = horizontalBar(ctx)
        // Full mode: title + author top-left, shown together with Android Auto's buttons.
        overlayTitle = text(ctx, 18f, bold = true).apply {
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }
        overlayAuthor = text(ctx, 14f).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(0xFFD0D0D0.toInt()); setShadowLayer(6f, 0f, 1f, Color.BLACK)
        }
        overlayInfo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCC000000.toInt(), 0x00000000),
            )
            addView(overlayTitle)
            addView(overlayAuthor)
            visibility = View.GONE
        }
        musicArt = android.widget.ImageView(ctx).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(12).toFloat())
            }
            setBackgroundColor(0xFF2A2529.toInt())
        }
        musicTitle = text(ctx, 24f, bold = true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        musicAuthor = text(ctx, 17f).apply { setTextColor(0xFFE6DDF5.toInt()); maxLines = 1 }
        musicView = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(24))
            setBackgroundColor(0xFF141217.toInt())
            addView(musicArt, LinearLayout.LayoutParams(dp(180), dp(180)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(28), 0, 0, 0)
                addView(musicTitle)
                addView(musicAuthor, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            visibility = View.GONE
        }
        videoFrame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(musicView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(overlayInfo, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(fullProgress, FrameLayout.LayoutParams(MATCH_PARENT, dp(6), Gravity.BOTTOM))
        }

        title = text(ctx, 22f, bold = true).apply { maxLines = 3 }
        author = text(ctx, 16f).apply { setTextColor(0xFFB0B0B0.toInt()); maxLines = 1 }
        progress = horizontalBar(ctx)
        time = text(ctx, 14f).apply { setTextColor(0xFFB0B0B0.toInt()) }
        upNext = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(title)
            addView(author, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, dp(6)).apply { topMargin = dp(16) })
            addView(time, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
            addView(text(ctx, 15f, bold = true).apply {
                text = I18n.s.upNext; setTextColor(0xFFFF8FB5.toInt())
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24) })
            addView(upNext)
        }

        content = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(videoFrame)
            addView(panel)
        }
        lockOverlay = text(ctx, 22f, bold = true).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            text = I18n.s.pausedMoving
            visibility = View.GONE
        }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(lockOverlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        }
        setContentView(root)

        playerView.player = hub.player
        scope.launch { hub.state.collect(::render) }
        scope.launch { hub.infoPokedAt.collect { updateOverlay() } }
        scope.launch { I18n.language.collect { render(hub.state.value) } }
        scope.launch { Settings.subtitles.collect { SubtitleStyling.apply(playerView.subtitleView, it, scale = 1.25f) } }
        scope.launch {
            while (isActive) {
                updateProgress()
                updateOverlay()
                delay(1_000)
            }
        }
    }

    override fun onStop() {
        playerView.player = null
        scope.cancel()
        super.onStop()
    }

    fun setVisibleArea(rect: Rect) {
        visibleArea = rect
        // Remember the largest area so Android Auto's buttons float over the video instead of
        // shrinking it. A big horizontal change (layout switch: full width <-> card, or a side panel)
        // starts over instead, so the video never ends up under the media card.
        // A side panel opening pushes the left edge far right: keep the layout area for that case.
        val f = fullArea
        fullArea = when {
            f == null -> Rect(rect)
            rect.left - f.left > f.width() * 0.15f -> f
            Math.abs(rect.left - f.left) > f.width() * 0.15f || Math.abs(rect.right - f.right) > f.width() * 0.15f -> Rect(rect)
            else -> Rect(f).apply { union(rect) }
        }
        if (::content.isInitialized) render(hub.state.value)
    }

    private fun render(s: HubState) {
        val split = s.mode == DisplayMode.SPLIT
        // Android Auto's surface extends under its own rail and side cards; only the visible area
        // is actually seen, so both layouts are fitted into it.
        val area = when {
            split -> visibleArea
            s.panelOpen -> shrunkArea()
            else -> fullArea ?: visibleArea
        }
        val metrics = context.resources.displayMetrics
        if (area != null && !area.isEmpty) {
            content.setPadding(
                area.left.coerceAtLeast(0),
                area.top.coerceAtLeast(0),
                (metrics.widthPixels - area.right).coerceAtLeast(0),
                (metrics.heightPixels - area.bottom).coerceAtLeast(0),
            )
        } else {
            content.setPadding(0, 0, 0, 0)
        }
        // Portrait or near-square car screens (and narrow side cards): stack video above the panel.
        val areaW = (area?.width() ?: metrics.widthPixels).toFloat()
        val areaH = (area?.height() ?: metrics.heightPixels).toFloat()
        val stacked = split && (areaW < areaH * 1.3f || areaW / metrics.density < 520f)
        content.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (stacked) {
            videoFrame.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.6f)
            panel.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 0.4f)
        } else {
            videoFrame.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, if (split) 0.64f else 1f)
            panel.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, if (split) 0.36f else 0f)
        }
        panel.visibility = if (split) View.VISIBLE else View.GONE
        fullProgress.visibility = if (split) View.GONE else View.VISIBLE

        val item = s.current
        title.text = item?.title ?: I18n.s.nothingPlaying
        author.text = item?.author.orEmpty()

        overlayTitle.text = item?.title.orEmpty()
        overlayAuthor.text = item?.author.orEmpty()
        overlayAuthor.visibility = if (item?.author.isNullOrBlank()) View.GONE else View.VISIBLE
        // Wide screens: top-left, left of Android Auto's top button row (right ~50%).
        // Narrow layouts (e.g. beside the media card): bottom-left, clear of the right button column.
        val wide = videoFrame.width / context.resources.displayMetrics.density >= 900f
        (overlayInfo.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            val g = if (wide) Gravity.TOP else Gravity.BOTTOM
            if (lp.gravity != g) {
                lp.gravity = g
                overlayInfo.layoutParams = lp
            }
        }
        overlayInfo.background = android.graphics.drawable.GradientDrawable(
            if (wide) android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
            else android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,
            intArrayOf(0xCC000000.toInt(), 0x00000000),
        )
        if (wide) overlayInfo.setPadding(dp(16), dp(12), (videoFrame.width * 0.52f).toInt(), dp(20))
        else overlayInfo.setPadding(dp(16), dp(20), dp(88), dp(16))
        status.text = when {
            s.scrubMs != null -> clock(s.scrubMs)
            s.error != null -> s.error
            s.loading -> I18n.s.loading
            s.buffering && s.wantsToPlay -> I18n.s.buffering
            item == null -> I18n.s.tapBrowse
            else -> ""
        }
        status.visibility = if (status.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        upNext.removeAllViews()
        s.upNext.take(4).forEach { next ->
            upNext.addView(text(context, 15f).apply {
                text = next.title
                maxLines = 2
                setPadding(0, dp(10), 0, 0)
            })
        }
        if (s.upNext.isEmpty()) upNext.addView(text(context, 15f).apply {
            text = "—"; setTextColor(0xFF808080.toInt()); setPadding(0, dp(10), 0, 0)
        })

        lockOverlay.text = if (s.audioOnly) {
            "${I18n.s.audioOnlyDriving}\n\n${item?.title.orEmpty()}\n\n${I18n.s.audioOnlyPark}"
        } else {
            I18n.s.pausedMoving
        }
        // Music has no picture: it keeps playing (and showing its cover) while driving.
        lockOverlay.visibility = if (s.drivingLocked && !s.isMusic) View.VISIBLE else View.GONE
        musicView.visibility = if (s.isMusic) View.VISIBLE else View.GONE
        if (s.isMusic && item != null) {
            musicTitle.text = item.title
            musicAuthor.text = item.author.orEmpty()
            val key = item.thumbnailUrl ?: item.id
            if (musicArtFor != key) {
                musicArtFor = key
                musicArt.setImageDrawable(null)
                scope.launch {
                    val bmp = context.graph.thumbnails.load(item, 480)
                    if (musicArtFor == key && bmp != null) musicArt.setImageBitmap(bmp)
                }
            }
        }
        content.requestLayout()
        updateProgress()
    }

    /** Title + author for ~8 s after a touch, a button press or a new video (like Android Auto's own buttons). */
    private fun updateOverlay() {
        val s = hub.state.value
        val recent = android.os.SystemClock.elapsedRealtime() - hub.infoPokedAt.value < 8_000
        overlayInfo.visibility =
            if (s.mode == DisplayMode.FULL && s.current != null && recent && !s.drivingLocked && !s.panelOpen && !s.isMusic) View.VISIBLE else View.GONE
    }

    /** While a side panel (Browse, Queue, ...) is open: the part of the screen beside it. */
    private fun shrunkArea(): Rect? {
        val full = fullArea ?: return visibleArea
        val vis = visibleArea
        // Use what Android Auto reports when it accounts for the panel; else assume a left panel of ~42%.
        if (vis != null && vis.width() < full.width() * 0.85f) return vis
        return Rect(full.left + (full.width() * 0.42f).toInt(), full.top, full.right, full.bottom)
    }

    private fun clock(posMs: Long): String {
        val dur = hub.player.duration
        return if (dur != C.TIME_UNSET && dur > 0) "${formatDuration(posMs / 1000)} / ${formatDuration(dur / 1000)}"
        else formatDuration(posMs / 1000)
    }

    /**
     * Tap on the progress bar: position along it (0..1), or null when the tap was elsewhere.
     * The touch target is generous (bottom 56 dp of the video) because the bar is thin.
     */
    fun progressFraction(x: Float, y: Float): Float? {
        if (!::videoFrame.isInitialized || hub.state.value.mode != DisplayMode.FULL) return null
        val loc = IntArray(2).also(videoFrame::getLocationInWindow)
        val left = loc[0].toFloat()
        val bottom = (loc[1] + videoFrame.height).toFloat()
        if (y < bottom - dp(56) || y > bottom + dp(8) || x < left || x > left + videoFrame.width) return null
        return ((x - left) / videoFrame.width).coerceIn(0f, 1f)
    }

    /** Horizontal drag distance as a fraction of the progress bar width. */
    fun dragFraction(distanceX: Float): Float =
        if (!::videoFrame.isInitialized || videoFrame.width == 0) 0f else -distanceX / videoFrame.width

    private fun updateProgress() {
        val p = hub.player
        val dur = p.duration
        val pos = hub.state.value.scrubMs ?: p.currentPosition
        val pct = if (dur != C.TIME_UNSET && dur > 0) (pos * 1000 / dur).toInt() else 0
        progress.progress = pct
        fullProgress.progress = pct
        time.text = if (dur != C.TIME_UNSET && dur > 0) "${formatDuration(pos / 1000)} / ${formatDuration(dur / 1000)}" else ""
    }

    private fun text(ctx: Context, sp: Float, bold: Boolean = false) = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun horizontalBar(ctx: Context) =
        ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF2DD4BF.toInt())
        }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
