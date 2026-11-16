package app.pitstop.video.bilibili

import org.json.JSONObject

/**
 * Bilibili serves subtitles as JSON (`{"body":[{"from":1.2,"to":3.4,"content":"..."}]}`).
 * The player understands WebVTT, so convert.
 */
object BiliSubtitles {
    fun jsonToVtt(json: String): String {
        val body = JSONObject(json).optJSONArray("body")
        val out = StringBuilder("WEBVTT\n\n")
        if (body != null) for (i in 0 until body.length()) {
            val cue = body.optJSONObject(i) ?: continue
            val from = cue.optDouble("from", Double.NaN)
            val to = cue.optDouble("to", Double.NaN)
            val text = cue.optString("content").trim()
            if (from.isNaN() || to.isNaN() || to <= from || text.isEmpty()) continue
            out.append(time(from)).append(" --> ").append(time(to)).append('\n')
                // A blank line would end the cue early; "-->" inside text would confuse parsers.
                .append(text.replace("\n\n", "\n").replace("-->", "->")).append("\n\n")
        }
        return out.toString()
    }

    internal fun time(sec: Double): String {
        val ms = Math.round(sec * 1000)
        return "%02d:%02d:%02d.%03d".format(ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000)
    }

    /** "ai-zh" → "zh", "zh-CN" stays. */
    fun language(lan: String): String = lan.removePrefix("ai-")

    /** Title for one part of a multi-part video. */
    fun partTitle(videoTitle: String, page: Int, partName: String): String {
        val name = partName.trim()
        return if (name.isEmpty() || name == videoTitle) "$videoTitle · P$page" else "$videoTitle · P$page $name"
    }
}
