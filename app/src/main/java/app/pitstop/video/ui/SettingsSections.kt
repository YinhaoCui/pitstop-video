package app.pitstop.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pitstop.video.core.AppLanguage
import app.pitstop.video.core.I18n
import app.pitstop.video.core.S
import app.pitstop.video.core.Settings
import app.pitstop.video.core.SubtitleStyle

/** English / 简体中文. Applies immediately to the phone and the car screen. */
@Composable
fun LanguageSection() {
    val lang by I18n.language.collectAsStateWithLifecycle()
    Text(S.appLanguage, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLanguage.entries.forEach { l ->
            FilterChip(selected = l == lang, onClick = { I18n.set(l) }, label = { Text(l.label) })
        }
    }
}

private val subtitleLanguages = listOf(
    "" to null, "en" to "English", "zh-Hans" to "简体中文", "zh-Hant" to "繁體中文", "ja" to "日本語",
    "ko" to "한국어", "es" to "Español", "fr" to "Français", "de" to "Deutsch", "ru" to "Русский",
)

private val subtitleColors = listOf(0xFFFFFFFF.toInt(), 0xFFFFEB3B.toInt(), 0xFF4DD0E1.toInt(), 0xFF69F0AE.toInt())

/** Subtitle language, size, colour, transparency and edge style, with a live preview. */
@Composable
fun SubtitleSettingsSection() {
    val st by Settings.subtitles.collectAsStateWithLifecycle()
    Text(S.subtitlesTitle, style = MaterialTheme.typography.titleMedium)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(S.subShowByDefault, Modifier.weight(1f))
        Switch(checked = st.showByDefault, onCheckedChange = { v -> Settings.updateSubtitles { it.copy(showByDefault = v) } })
    }

    // Preferred language
    var menu by remember { mutableStateOf(false) }
    Text(S.subLanguage, style = MaterialTheme.typography.bodyMedium)
    Box {
        OutlinedButton(onClick = { menu = true }) {
            Text(subtitleLanguages.firstOrNull { it.first == st.language }?.second ?: S.subLanguageAuto)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            subtitleLanguages.forEach { (tag, name) ->
                DropdownMenuItem(
                    text = { Text(name ?: S.subLanguageAuto) },
                    onClick = { Settings.updateSubtitles { it.copy(language = tag) }; menu = false },
                )
            }
        }
    }

    // Live preview
    val fg = Color(st.foregroundArgb)
    val shadow = when (st.edge) {
        SubtitleStyle.Edge.NONE -> null
        SubtitleStyle.Edge.OUTLINE -> Shadow(Color.Black, blurRadius = 3f)
        SubtitleStyle.Edge.SHADOW -> Shadow(Color.Black, offset = androidx.compose.ui.geometry.Offset(3f, 3f), blurRadius = 6f)
    }
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF3B4A5A)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Text(
            S.subPreview,
            Modifier.padding(bottom = 12.dp).background(Color(st.backgroundArgb)).padding(horizontal = 6.dp, vertical = 2.dp),
            style = TextStyle(color = fg, fontSize = (16 * st.sizePercent / 100f).sp, fontWeight = FontWeight.Medium, shadow = shadow),
        )
    }

    Text(S.subSize(st.sizePercent), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = st.sizePercent.toFloat(), valueRange = 50f..200f, steps = 14,
        onValueChange = { v -> Settings.updateSubtitles { it.copy(sizePercent = (v / 10).toInt() * 10) } },
    )

    Text(S.subColor, style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(S.colorWhite, S.colorYellow, S.colorCyan, S.colorGreen).zip(subtitleColors).forEach { (name, c) ->
            FilterChip(
                selected = (st.textColor and 0xFFFFFF) == (c and 0xFFFFFF),
                onClick = { Settings.updateSubtitles { it.copy(textColor = c) } },
                label = { Text(name) },
                leadingIcon = { Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(Color(c))) },
            )
        }
    }

    Text(S.subTextOpacity(st.textOpacity), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = st.textOpacity.toFloat(), valueRange = 20f..100f, steps = 7,
        onValueChange = { v -> Settings.updateSubtitles { it.copy(textOpacity = (v / 10).toInt() * 10) } },
    )
    Text(S.subBgOpacity(st.backgroundOpacity), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = st.backgroundOpacity.toFloat(), valueRange = 0f..100f, steps = 9,
        onValueChange = { v -> Settings.updateSubtitles { it.copy(backgroundOpacity = (v / 10).toInt() * 10) } },
    )

    Text(S.subEdge, style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(SubtitleStyle.Edge.NONE to S.edgeNone, SubtitleStyle.Edge.OUTLINE to S.edgeOutline, SubtitleStyle.Edge.SHADOW to S.edgeShadow)
            .forEach { (e, name) ->
                FilterChip(selected = st.edge == e, onClick = { Settings.updateSubtitles { it.copy(edge = e) } }, label = { Text(name) })
            }
    }
}

/** Audio-only mode while driving (default on) vs. pausing completely. */
@Composable
fun DrivingAudioSection() {
    val on by Settings.audioOnlyWhileDriving.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(S.drivingTitle, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(S.audioOnly, style = MaterialTheme.typography.bodyLarge)
                Text(S.audioOnlyText, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, onCheckedChange = Settings::setAudioOnlyWhileDriving)
        }
    }
}
