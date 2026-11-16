package app.pitstop.video.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.pitstop.video.R
import app.pitstop.video.core.S
import kotlinx.coroutines.delay

/**
 * Branded opening on a cold start, after the system splash: the personal picture (local builds
 * only) full-bleed, framed on the face, with the app mark and name at the bottom; fades into the
 * app after ~1.2 s. Tap to skip. Shows nothing when no personal picture is bundled.
 */
@Composable
fun OpeningScreen() {
    // A dedicated portrait opening picture (res/drawable-nodpi/opening_picture.*, local only),
    // else the square avatar picture.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    @android.annotation.SuppressLint("DiscouragedApi")
    val opening = remember { ctx.resources.getIdentifier("opening_picture", "drawable", ctx.packageName) }
    val avatar = personalPictureId()
    val picture = if (opening != 0) opening else avatar
    if (picture == 0) return
    // Portrait art: centred, a little left (her face). Square avatar art: keep her eyes in frame.
    val bias = if (opening != 0) BiasAlignment(-0.3f, -0.2f) else BiasAlignment(-0.22f, 0f)
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        delay(1_200)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(250)), exit = fadeOut(tween(400))) {
        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .clickable(remember { MutableInteractionSource() }, indication = null) { visible = false },
        ) {
            Image(
                painterResource(picture), null, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, alignment = bias,
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.35f)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 40.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))) {
                    Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Text(S.appName, color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}
