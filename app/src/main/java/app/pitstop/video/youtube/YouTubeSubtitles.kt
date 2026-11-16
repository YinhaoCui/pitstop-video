package app.pitstop.video.youtube

import app.pitstop.video.core.SubtitleTrack

/**
 * Chooses which YouTube caption tracks to offer:
 * - every creator-uploaded track,
 * - auto-generated tracks only in the viewer's language (the rest are noise),
 * - an auto-translated track into the viewer's language when none exists (YouTube's `tlang`).
 */
object YouTubeSubtitles {
    data class Candidate(val url: String, val languageTag: String, val name: String, val auto: Boolean)

    private fun lang(tag: String) = tag.substringBefore('-').substringBefore('_').lowercase()

    fun pick(all: List<Candidate>, deviceLanguage: String, max: Int = 8): List<SubtitleTrack> {
        val device = lang(deviceLanguage)
        val manual = all.filter { !it.auto }
        val autoMine = all.filter { it.auto && lang(it.languageTag) == device }
        val chosen = (manual.sortedByDescending { lang(it.languageTag) == device } + autoMine)
            .distinctBy { it.languageTag to it.auto }
            .take(max)
            .map { SubtitleTrack(it.url, it.languageTag, if (it.auto) "${it.name} (auto)" else it.name, it.auto) }
            .toMutableList()
        if (chosen.none { lang(it.language) == device }) {
            val base = manual.firstOrNull() ?: all.firstOrNull()
            if (base != null) {
                val sep = if ('?' in base.url) '&' else '?'
                val loc = java.util.Locale.forLanguageTag(deviceLanguage)
                val name = loc.getDisplayLanguage(loc).ifEmpty { deviceLanguage }
                chosen += SubtitleTrack("${base.url}${sep}tlang=$deviceLanguage", deviceLanguage, "$name (auto-translated)", true)
            }
        }
        return chosen
    }
}
