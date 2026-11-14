package app.pitstop.video.ui

import android.annotation.SuppressLint
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import app.pitstop.video.R
import app.pitstop.video.core.Settings
import app.pitstop.video.core.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One accent (sakura pink) over warm neutrals. A fixed palette rather than Material You, so phones,
 * tablets and head units look the same. Decoration comes from the user's own pictures, not the UI.
 */
object Sakura {
    val Pink = Color(0xFFD6336C)
    val PinkLight = Color(0xFFFF8FB5)
    val Night = Color(0xFF141217)
}

private val NightScheme = darkColorScheme(
    primary = Sakura.PinkLight,
    onPrimary = Color(0xFF3B0A1E),
    primaryContainer = Color(0xFF5A1A33),
    onPrimaryContainer = Color(0xFFFFD9E3),
    secondary = Color(0xFFD5C2CA),
    onSecondary = Color(0xFF392C32),
    secondaryContainer = Color(0xFF3A3036),
    onSecondaryContainer = Color(0xFFF2DDE5),
    background = Sakura.Night,
    onBackground = Color(0xFFECE6EA),
    surface = Sakura.Night,
    onSurface = Color(0xFFECE6EA),
    surfaceVariant = Color(0xFF2A2529),
    onSurfaceVariant = Color(0xFFBDB3B9),
    surfaceContainerLowest = Color(0xFF0F0D11),
    surfaceContainerLow = Color(0xFF1A171B),
    surfaceContainer = Color(0xFF1F1B20),
    surfaceContainerHigh = Color(0xFF29252A),
    surfaceContainerHighest = Color(0xFF342F35),
    outline = Color(0xFF877D83),
    outlineVariant = Color(0xFF3D373C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val DayScheme = lightColorScheme(
    primary = Sakura.Pink,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9E3),
    onPrimaryContainer = Color(0xFF3E0020),
    secondary = Color(0xFF6D5A62),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF5E3EA),
    onSecondaryContainer = Color(0xFF26181E),
    background = Color(0xFFFFFBFC),
    onBackground = Color(0xFF1F1A1D),
    surface = Color(0xFFFFFBFC),
    onSurface = Color(0xFF1F1A1D),
    surfaceVariant = Color(0xFFF2E6EA),
    onSurfaceVariant = Color(0xFF5E5359),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFCF3F6),
    surfaceContainer = Color(0xFFF8EDF1),
    surfaceContainerHigh = Color(0xFFF2E6EB),
    surfaceContainerHighest = Color(0xFFECE0E5),
    outline = Color(0xFF8F8187),
    outlineVariant = Color(0xFFE3D5DB),
)

private val PitstopShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

private fun pitstopType(): Typography {
    val base = Typography()
    fun TextStyle.w(weight: FontWeight, tracking: Float = letterSpacing.value) = copy(fontWeight = weight, letterSpacing = tracking.sp)
    return base.copy(
        headlineSmall = base.headlineSmall.w(FontWeight.Bold, -0.2f),
        titleLarge = base.titleLarge.w(FontWeight.SemiBold, -0.1f),
        titleMedium = base.titleMedium.w(FontWeight.SemiBold),
        titleSmall = base.titleSmall.w(FontWeight.Medium),
        labelLarge = base.labelLarge.w(FontWeight.SemiBold),
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
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.window?.let { w ->
            WindowCompat.getInsetsController(w, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) NightScheme else DayScheme, shapes = PitstopShapes, typography = pitstopType(), content = content)
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

/** Plain theme background, or the user's own picture behind a wash that keeps text readable. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val picture = rememberBackgroundPicture()
    val bg = MaterialTheme.colorScheme.background
    Box(modifier.background(bg)) {
        if (picture != null) {
            Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.6f), bg.copy(alpha = 0.82f), bg.copy(alpha = 0.92f)))))
        }
        content()
    }
}

/**
 * A personal picture (res/drawable-nodpi/header_avatar.*, local builds only; looked up by name so
 * the public source builds without it), else 0.
 */
@Composable
fun personalPictureId(): Int {
    val ctx = LocalContext.current
    @SuppressLint("DiscouragedApi")
    val id = remember { ctx.resources.getIdentifier("header_avatar", "drawable", ctx.packageName) }
    return id
}

/** Round avatar: the personal picture when present (local builds), else the app's default picture. */
@Composable
fun Avatar(size: Dp, modifier: Modifier = Modifier) {
    val personal = personalPictureId()
    Box(modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)) {
        Image(
            painterResource(if (personal != 0) personal else R.drawable.default_avatar), null,
            Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
        )
    }
}
