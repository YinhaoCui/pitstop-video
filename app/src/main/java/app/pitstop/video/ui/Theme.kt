package app.pitstop.video.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

@Composable
fun PitstopTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = runCatching { if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx) }
        .getOrElse {
            if (dark) darkColorScheme(primary = Color(0xFF2DD4BF)) else lightColorScheme(primary = Color(0xFF0F766E))
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
