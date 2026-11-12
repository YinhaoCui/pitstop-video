package app.pitstop.video.car

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = context

        playerView = PlayerView(ctx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            setKeepContentOnPlayerReset(true)
        }
        status = text(ctx, 20f, bold = true).apply { gravity = Gravity.CENTER; setPadding(dp(24), 0, dp(24), 0) }
        fullProgress = horizontalBar(ctx)
        videoFrame = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(playerView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(fullProgress, FrameLayout.LayoutParams(MATCH_PARENT, dp(4), Gravity.BOTTOM))
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
                text = "Up next"; setTextColor(0xFF5EEAD4.toInt())
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
            text = "Video is paused while the car is moving.\nPark to keep watching."
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
        scope.launch {
            while (isActive) {
                updateProgress()
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
        fullArea = fullArea?.let { Rect(it).apply { union(rect) } } ?: Rect(rect)
        if (::content.isInitialized) render(hub.state.value)
    }

    private fun render(s: HubState) {
        val split = s.mode == DisplayMode.SPLIT
        // Android Auto's surface extends under its own rail and side cards; only the visible area
        // is actually seen, so both layouts are fitted into it.
        val area = if (split) visibleArea else fullArea ?: visibleArea
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
        videoFrame.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, if (split) 0.64f else 1f)
        panel.layoutParams = LinearLayout.LayoutParams(0, MATCH_PARENT, if (split) 0.36f else 0f)
        panel.visibility = if (split) View.VISIBLE else View.GONE
        fullProgress.visibility = if (split) View.GONE else View.VISIBLE

        val item = s.current
        title.text = item?.title ?: "Nothing playing"
        author.text = item?.author.orEmpty()
        status.text = when {
            s.error != null -> s.error
            s.loading -> "Loading…"
            s.buffering && s.wantsToPlay -> "Buffering…"
            item == null -> "Tap Browse to pick a video,\nor choose one on your phone."
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

        lockOverlay.visibility = if (s.drivingLocked) View.VISIBLE else View.GONE
        content.requestLayout()
        updateProgress()
    }

    private fun updateProgress() {
        val p = hub.player
        val dur = p.duration
        val pos = p.currentPosition
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
