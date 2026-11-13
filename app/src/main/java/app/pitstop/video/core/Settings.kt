package app.pitstop.video.core

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How subtitles look and which language is preferred. Applied on the phone and the car screen. */
data class SubtitleStyle(
    val showByDefault: Boolean = false,
    /** BCP-47 language ("en", "zh-Hans", ...); empty = follow the app language. */
    val language: String = "",
    val sizePercent: Int = 100,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textOpacity: Int = 100,
    val backgroundOpacity: Int = 50,
    val edge: Edge = Edge.OUTLINE,
) {
    enum class Edge { NONE, OUTLINE, SHADOW }

    /** Colour with the given opacity percentage applied to its alpha channel. */
    fun withOpacity(rgb: Int, opacityPercent: Int): Int =
        (rgb and 0x00FFFFFF) or ((opacityPercent.coerceIn(0, 100) * 255 / 100) shl 24)

    val foregroundArgb: Int get() = withOpacity(textColor, textOpacity)
    val backgroundArgb: Int get() = withOpacity(0x000000, backgroundOpacity)
}

/** Persistent user settings (besides the UI language, see [I18n]). */
enum class ThemeMode { LIGHT, DARK, SYSTEM }

/** Narrow phones: the player as its own page (mini bar under the library) or a draggable floating window. */
enum class PlayerStyle { PAGE, FLOATING }

object Settings {
    private var prefs: SharedPreferences? = null

    private val _subtitles = MutableStateFlow(SubtitleStyle())
    val subtitles: StateFlow<SubtitleStyle> = _subtitles.asStateFlow()

    private val _audioOnlyWhileDriving = MutableStateFlow(true)
    /** While driving: keep the sound playing with the picture hidden (true) or pause completely (false). */
    val audioOnlyWhileDriving: StateFlow<Boolean> = _audioOnlyWhileDriving.asStateFlow()

    private val _backgroundPlay = MutableStateFlow(true)
    /** Phone: keep the sound playing when the screen turns off or another app is opened. */
    val backgroundPlay: StateFlow<Boolean> = _backgroundPlay.asStateFlow()

    fun setBackgroundPlay(value: Boolean) {
        _backgroundPlay.value = value
        prefs?.edit()?.putBoolean("background_play", value)?.apply()
    }

    private val _themeMode = MutableStateFlow(ThemeMode.LIGHT)
    /** Light (pastel) by default; dark "night" look or follow the system on request. */
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _background = MutableStateFlow<String?>(null)
    /** Optional picture from the user's own gallery shown behind the app (content:// URI). */
    val background: StateFlow<String?> = _background.asStateFlow()

    private val _playerStyle = MutableStateFlow(PlayerStyle.PAGE)
    val playerStyle: StateFlow<PlayerStyle> = _playerStyle.asStateFlow()

    fun setPlayerStyle(value: PlayerStyle) {
        _playerStyle.value = value
        prefs?.edit()?.putString("player_style", value.name)?.apply()
    }

    fun setThemeMode(value: ThemeMode) {
        _themeMode.value = value
        prefs?.edit()?.putString("theme_mode", value.name)?.apply()
    }

    fun setBackground(uri: String?) {
        _background.value = uri
        prefs?.edit()?.putString("background_uri", uri)?.apply()
    }

    fun init(p: SharedPreferences) {
        prefs = p
        _playerStyle.value = runCatching { PlayerStyle.valueOf(p.getString("player_style", null)!!) }.getOrDefault(PlayerStyle.PAGE)
        val d = SubtitleStyle()
        _subtitles.value = SubtitleStyle(
            showByDefault = p.getBoolean("sub_show", d.showByDefault),
            language = p.getString("sub_lang", d.language) ?: "",
            sizePercent = p.getInt("sub_size", d.sizePercent),
            textColor = p.getInt("sub_color", d.textColor),
            textOpacity = p.getInt("sub_text_opacity", d.textOpacity),
            backgroundOpacity = p.getInt("sub_bg_opacity", d.backgroundOpacity),
            edge = runCatching { SubtitleStyle.Edge.valueOf(p.getString("sub_edge", d.edge.name)!!) }.getOrDefault(d.edge),
        )
        _audioOnlyWhileDriving.value = p.getBoolean("audio_only_driving", true)
        _backgroundPlay.value = p.getBoolean("background_play", true)
        _themeMode.value = runCatching { ThemeMode.valueOf(p.getString("theme_mode", null)!!) }.getOrDefault(ThemeMode.LIGHT)
        _background.value = p.getString("background_uri", null)
        _hiddenSources.value = p.getStringSet("hidden_sources", emptySet()).orEmpty()
            .mapNotNull { n -> SourceId.entries.firstOrNull { it.name == n } }.toSet()
        _qualityWifi.value = runCatching { VideoQuality.valueOf(p.getString("quality_wifi", null)!!) }.getOrDefault(VideoQuality.AUTO)
        _qualityMobile.value = runCatching { VideoQuality.valueOf(p.getString("quality_mobile", null)!!) }.getOrDefault(VideoQuality.AUTO)
    }

    fun updateSubtitles(f: (SubtitleStyle) -> SubtitleStyle) {
        val s = f(_subtitles.value)
        _subtitles.value = s
        prefs?.edit()
            ?.putBoolean("sub_show", s.showByDefault)
            ?.putString("sub_lang", s.language)
            ?.putInt("sub_size", s.sizePercent)
            ?.putInt("sub_color", s.textColor)
            ?.putInt("sub_text_opacity", s.textOpacity)
            ?.putInt("sub_bg_opacity", s.backgroundOpacity)
            ?.putString("sub_edge", s.edge.name)
            ?.apply()
    }

    private val _qualityWifi = MutableStateFlow(VideoQuality.AUTO)
    /** Video quality on unmetered networks (Wi-Fi). */
    val qualityWifi: StateFlow<VideoQuality> = _qualityWifi.asStateFlow()

    private val _qualityMobile = MutableStateFlow(VideoQuality.AUTO)
    /** Video quality on metered networks (mobile data, phone hotspot). */
    val qualityMobile: StateFlow<VideoQuality> = _qualityMobile.asStateFlow()

    fun quality(metered: Boolean): VideoQuality = if (metered) _qualityMobile.value else _qualityWifi.value

    fun setQuality(metered: Boolean, value: VideoQuality) {
        if (metered) _qualityMobile.value = value else _qualityWifi.value = value
        prefs?.edit()?.putString(if (metered) "quality_mobile" else "quality_wifi", value.name)?.apply()
    }

    private val _hiddenSources = MutableStateFlow<Set<SourceId>>(emptySet())
    /** Platforms the user turned off: left out of the Video / Music bars on the phone and the car. */
    val hiddenSources: StateFlow<Set<SourceId>> = _hiddenSources.asStateFlow()

    fun setSourceShown(id: SourceId, shown: Boolean) {
        val s = if (shown) _hiddenSources.value - id else _hiddenSources.value + id
        _hiddenSources.value = s
        prefs?.edit()?.putStringSet("hidden_sources", s.mapTo(HashSet()) { it.name })?.apply()
    }

    fun setAudioOnlyWhileDriving(value: Boolean) {
        _audioOnlyWhileDriving.value = value
        prefs?.edit()?.putBoolean("audio_only_driving", value)?.apply()
    }

    /** Preferred subtitle language: the explicit choice, else the app's UI language. */
    fun subtitleLanguage(): String = _subtitles.value.language.ifEmpty {
        if (I18n.language.value == AppLanguage.ZH) "zh-Hans" else "en"
    }
}
