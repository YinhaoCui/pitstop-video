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
import androidx.compose.foundation.verticalScroll
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
import app.pitstop.video.core.VideoQuality
import app.pitstop.video.core.SourceId
import app.pitstop.video.core.Settings
import app.pitstop.video.core.ThemeMode
import app.pitstop.video.core.PlayerStyle
import androidx.compose.foundation.clickable
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
fun PhonePlaybackSection() {
    val on by Settings.backgroundPlay.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(S.phonePlayback, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(S.backgroundPlay, style = MaterialTheme.typography.bodyLarge)
                Text(S.backgroundPlayText, style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = on, onCheckedChange = Settings::setBackgroundPlay)
        }
    }
}

/** Platform switches for the Video and Music bars; a bar's last shown platform can't be turned off. */
@Composable
fun PlatformToggles() {
    val hidden by Settings.hiddenSources.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        listOf(S.video to SourceId.entries.filter { !it.music }, S.music to SourceId.entries.filter { it.music })
            .forEach { (title, ids) ->
                Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                val shownCount = ids.count { it !in hidden }
                ids.forEach { id ->
                    val shown = id !in hidden
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(S.sourceName(id), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Switch(
                            checked = shown,
                            enabled = !shown || shownCount > 1,
                            onCheckedChange = { Settings.setSourceShown(id, it) },
                        )
                    }
                }
            }
    }
}

@Composable
fun PlatformsSection() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(S.platforms, style = MaterialTheme.typography.titleMedium)
        Text(S.platformsText, style = MaterialTheme.typography.bodySmall)
        PlatformToggles()
    }
}

/** The same switches as a dialog, opened from the button at the end of a platform bar. */
@Composable
fun PlatformPickerDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.choosePlatforms) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(S.platformsText, style = MaterialTheme.typography.bodySmall)
                PlatformToggles()
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(S.done) } },
    )
}

/** Label for a quality choice ("Auto", "720p", "Audio only"). */
@Composable
fun qualityLabel(q: VideoQuality): String = when (q) {
    VideoQuality.AUTO -> S.qualityAuto
    VideoQuality.AUDIO -> S.qualityAudio
    else -> "${q.maxHeight}p"
}

/** Data saver: separate video quality for Wi-Fi and mobile data, down to audio only. */
@Composable
fun DataSaverSection() {
    val wifi by Settings.qualityWifi.collectAsStateWithLifecycle()
    val mobile by Settings.qualityMobile.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(S.dataSaver, style = MaterialTheme.typography.titleMedium)
        Text(S.dataSaverText, style = MaterialTheme.typography.bodySmall)
        listOf(false to wifi, true to mobile).forEach { (metered, current) ->
            Text(if (metered) S.onMobileData else S.onWifi, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoQuality.entries.forEach { q ->
                    FilterChip(
                        selected = current == q,
                        onClick = { Settings.setQuality(metered, q) },
                        label = { Text(qualityLabel(q)) },
                    )
                }
            }
        }
    }
}

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

/** Theme (light by default) and an optional background picture from the user's own gallery. */
@Composable
fun AppearanceSection() {
    val mode by Settings.themeMode.collectAsStateWithLifecycle()
    val picture by Settings.background.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            // Keep access to the picture across restarts; release the previous one.
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            picture?.let { old ->
                runCatching {
                    ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            Settings.setBackground(uri.toString())
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(S.appearance, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.LIGHT to S.themeLight, ThemeMode.DARK to S.themeDark, ThemeMode.SYSTEM to S.themeSystem).forEach { (m, label) ->
                FilterChip(selected = mode == m, onClick = { Settings.setThemeMode(m) }, label = { Text(label) })
            }
        }
        val style by Settings.playerStyle.collectAsStateWithLifecycle()
        Text(S.playerStyle, style = MaterialTheme.typography.bodyLarge)
        Text(S.playerStyleText, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(PlayerStyle.PAGE to S.playerStylePage, PlayerStyle.FLOATING to S.playerStyleFloating).forEach { (m, label) ->
                FilterChip(selected = style == m, onClick = { Settings.setPlayerStyle(m) }, label = { Text(label) })
            }
        }
        Text(S.backgroundImage, style = MaterialTheme.typography.bodyLarge)
        Text(S.backgroundImageText, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { pick.launch(arrayOf("image/*")) }) { Text(S.choosePicture) }
            if (picture != null) OutlinedButton(onClick = {
                picture?.let { old ->
                    runCatching {
                        ctx.contentResolver.releasePersistableUriPermission(android.net.Uri.parse(old), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                }
                Settings.setBackground(null)
            }) { Text(S.removePicture) }
        }
    }
}

/** App version line at the bottom of Settings. */
@Composable
fun VersionFooter() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
    }
    var taps by remember { mutableStateOf(0) }
    Text(
        S.versionLine(version),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
            .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {
                if (++taps >= 7) {
                    taps = 0
                    runCatching {
                        ctx.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://ichigoproduction.com/"))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            },
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
