package app.pitstop.video.bilibili

import org.json.JSONObject

/**
 * Paid-content check for Bilibili's playurl answer. Paid videos (bangumi / courses / charge-only)
 * the account hasn't bought come back as a short trial clip marked `is_preview`; the app refuses to
 * play those instead of passing a clip off as the video. Pure JVM, unit-tested.
 */
object BiliPaid {
    fun isPreview(playurlData: JSONObject): Boolean =
        playurlData.optInt("is_preview", 0) == 1 || playurlData.optBoolean("is_preview", false)
}
