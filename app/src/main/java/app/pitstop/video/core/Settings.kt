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

    fun init(p: SharedPreferences) {
        prefs = p
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

    fun setAudioOnlyWhileDriving(value: Boolean) {
        _audioOnlyWhileDriving.value = value
        prefs?.edit()?.putBoolean("audio_only_driving", value)?.apply()
    }

    /** Preferred subtitle language: the explicit choice, else the app's UI language. */
    fun subtitleLanguage(): String = _subtitles.value.language.ifEmpty {
        if (I18n.language.value == AppLanguage.ZH) "zh-Hans" else "en"
    }
}
