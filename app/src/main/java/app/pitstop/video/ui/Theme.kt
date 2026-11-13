package app.pitstop.video.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.random.Random
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import app.pitstop.video.R
import app.pitstop.video.core.Settings
import app.pitstop.video.core.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Sakura Night": a modern anime look – twilight indigo, sakura pink and neon cyan, soft glows,
 * rounded shapes and bold type. A fixed brand palette (not Material You) so the app looks the same
 * on every phone, tablet and head unit.
 */
object Sakura {
    val Pink = Color(0xFFFF5C93)
    val PinkSoft = Color(0xFFFFB3CD)
    val Violet = Color(0xFF8B5CF6)
    val Cyan = Color(0xFF5EE6FF)
    val Lemon = Color(0xFFFFF1A8)
    val Night = Color(0xFF0E0B24)
    val NightHigh = Color(0xFF1A1540)
    val Dusk = Color(0xFF2A2060)

    /** The signature pink -> violet sweep used for active states and the play button. */
    val accent = Brush.linearGradient(listOf(Pink, Violet))
    val accentWide = Brush.horizontalGradient(listOf(Pink, Violet, Cyan))
}

private val NightScheme = darkColorScheme(
    primary = Sakura.Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4A1838),
    onPrimaryContainer = Sakura.PinkSoft,
    secondary = Sakura.Cyan,
    onSecondary = Color(0xFF00222B),
    secondaryContainer = Color(0xFF173A52),
    onSecondaryContainer = Color(0xFFBDF3FF),
    tertiary = Sakura.Lemon,
    onTertiary = Color(0xFF2B2600),
    background = Sakura.Night,
    onBackground = Color(0xFFF2EEFF),
    surface = Sakura.Night,
    onSurface = Color(0xFFF2EEFF),
    surfaceVariant = Color(0xFF231C4D),
    onSurfaceVariant = Color(0xFFB9B1DA),
    surfaceContainerLowest = Color(0xFF0A0819),
    surfaceContainerLow = Color(0xFF14102F),
    surfaceContainer = Color(0xFF1A1540),
    surfaceContainerHigh = Color(0xFF221C4F),
    surfaceContainerHighest = Color(0xFF2B245E),
    outline = Color(0xFF5B5290),
    outlineVariant = Color(0xFF332B66),
    error = Color(0xFFFF8A9A),
    onError = Color(0xFF4A0010),
)

private val DayScheme = lightColorScheme(
    primary = Color(0xFFD81B60),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E4),
    onPrimaryContainer = Color(0xFF3E0020),
    secondary = Color(0xFF006C80),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCFF4FF),
    onSecondaryContainer = Color(0xFF001F27),
    tertiary = Color(0xFF6D5E00),
    background = Color(0xFFFFF7FA),
    onBackground = Color(0xFF231A33),
    surface = Color(0xFFFFF7FA),
    onSurface = Color(0xFF231A33),
    surfaceVariant = Color(0xFFF3E6F2),
    onSurfaceVariant = Color(0xFF5A4C68),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFF0F5),
    surfaceContainer = Color(0xFFFBEAF2),
    surfaceContainerHigh = Color(0xFFF6E2EE),
    surfaceContainerHighest = Color(0xFFF0DAE9),
    outline = Color(0xFF8D7B9C),
    outlineVariant = Color(0xFFE0CDE0),
)

private val SakuraShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private fun sakuraType(): Typography {
    val base = Typography()
    fun TextStyle.heavy(w: FontWeight, ls: Float) = copy(fontWeight = w, letterSpacing = ls.sp)
    return base.copy(
        displaySmall = base.displaySmall.heavy(FontWeight.Black, -0.5f),
        headlineMedium = base.headlineMedium.heavy(FontWeight.ExtraBold, -0.3f),
        headlineSmall = base.headlineSmall.heavy(FontWeight.ExtraBold, -0.2f),
        titleLarge = base.titleLarge.heavy(FontWeight.Bold, 0f),
        titleMedium = base.titleMedium.heavy(FontWeight.Bold, 0.1f),
        titleSmall = base.titleSmall.heavy(FontWeight.SemiBold, 0.1f),
        labelLarge = base.labelLarge.heavy(FontWeight.Bold, 0.3f),
        labelMedium = base.labelMedium.heavy(FontWeight.SemiBold, 0.4f),
    )
}

@Composable
fun PitstopTheme(content: @Composable () -> Unit) {
    val mode by Settings.themeMode.collectAsState()
    val dark = when (mode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    // Status / navigation bar icons follow the app theme (dark icons on the light theme).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) NightScheme else DayScheme, shapes = SakuraShapes, typography = sakuraType(), content = content)
}

/** The user's background picture, decoded once at screen size (downsampled to save memory). */
@Composable
private fun rememberBackgroundPicture(): ImageBitmap? {
    val uri by Settings.background.collectAsState()
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = uri?.let { u ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val res = ctx.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    res.openInputStream(Uri.parse(u))?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    val target = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
                    res.openInputStream(Uri.parse(u))?.use {
                        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                    }?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    return bitmap
}

private val isNight @Composable get() = MaterialTheme.colorScheme.background == Sakura.Night

/**
 * App background: a twilight gradient with soft pink / cyan glows and a few sparkles (night), or a
 * pale sakura wash (day). Purely decorative and static, so it costs nothing while scrolling.
 */
@Composable
fun AnimeBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val night = isNight
    val stars = remember { List(28) { Triple(Random.nextFloat(), Random.nextFloat() * 0.6f, Random.nextFloat()) } }
    val picture = rememberBackgroundPicture()
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(bg)) {
        if (picture != null) {
            // The user's own picture, washed with the theme colour so text stays readable.
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.55f), bg.copy(alpha = 0.78f), bg.copy(alpha = 0.9f)))))
        } else Canvas(Modifier.fillMaxSize()) {
            if (night) {
                drawRect(Brush.verticalGradient(listOf(Sakura.Dusk, Sakura.Night, Sakura.Night)))
                glow(Sakura.Pink.copy(alpha = 0.22f), Offset(size.width * 0.92f, size.height * 0.04f), size.width * 0.7f)
                glow(Sakura.Cyan.copy(alpha = 0.12f), Offset(0f, size.height * 0.55f), size.width * 0.6f)
                stars.forEach { (x, y, a) ->
                    drawCircle(Color.White.copy(alpha = 0.15f + a * 0.45f), radius = 0.8.dp.toPx() + a * 1.2.dp.toPx(),
                        center = Offset(x * size.width, y * size.height))
                }
            } else {
                glow(Sakura.PinkSoft.copy(alpha = 0.45f), Offset(size.width * 0.95f, 0f), size.width * 0.8f)
                glow(Color(0x3363D9F0), Offset(0f, size.height * 0.6f), size.width * 0.6f)
            }
        }
        content()
    }
}

private fun DrawScope.glow(color: Color, center: Offset, radius: Float) =
    drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, radius), radius, center)

/** Four-point anime sparkle. */
@Composable
fun Sparkle(modifier: Modifier, color: Color = Sakura.Lemon) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w / 2, 0f)
            quadraticTo(w * 0.58f, h * 0.42f, w, h / 2)
            quadraticTo(w * 0.58f, h * 0.58f, w / 2, h)
            quadraticTo(w * 0.42f, h * 0.58f, 0f, h / 2)
            quadraticTo(w * 0.42f, h * 0.42f, w / 2, 0f)
            close()
        }
        drawPath(p, color)
    }
}

/** Brand header: gradient "Pitstop" wordmark, sparkle and a small kicker line. */
@Composable
fun BrandHeader(title: String, kicker: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                kicker.uppercase(),
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 2.sp),
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium.copy(brush = Brush.linearGradient(listOf(Sakura.Pink, Sakura.Violet, Sakura.Cyan))),
            )
        }
        Box(Modifier.padding(start = 8.dp)) {
            // Mascot avatar in a soft gradient bubble, with a sparkle.
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFFFFE1EE), Color(0xFFE6E0FF), Color(0xFFD6F3FF)))),
            ) {
                Image(painterResource(R.drawable.ill_mascot), null, Modifier.fillMaxSize().padding(top = 3.dp))
            }
            Sparkle(Modifier.align(Alignment.TopEnd).size(14.dp), if (isNight) Sakura.Lemon else Sakura.Pink)
        }
    }
}

/**
 * Pill tabs: the selected tab gets the pink -> violet gradient. Scrolls horizontally when the
 * labels don't fit (long Chinese / English labels, narrow phones).
 */
@Composable
fun PillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val textColor by animateColorAsState(
                if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, tween(220), label = "tab",
            )
            val shape = RoundedCornerShape(50)
            Box(
                Modifier
                    .clip(shape)
                    .then(if (on) Modifier.background(Sakura.accent) else Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.7f)))
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .heightIn(min = 40.dp)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = textColor, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
    }
}
