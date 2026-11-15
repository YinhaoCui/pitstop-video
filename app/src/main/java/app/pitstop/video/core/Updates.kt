package app.pitstop.video.core

import org.json.JSONObject

/** One published version of the app: the GitHub release and its APK. */
data class AppRelease(
    /** Version without the "v" prefix, e.g. "0.6.4". */
    val version: String,
    val tag: String,
    val notes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSize: Long,
    val pageUrl: String,
)

/** Version comparison and GitHub release parsing for "Check for updates" (pure; unit-tested). */
object Updates {
    /** "v0.6.3", "0.6.3-beta", "0.7" -> [0, 6, 3] / [0, 7]; null when it doesn't start with a number. */
    fun parseVersion(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.isEmpty() || parts.any { it.isEmpty() }) return null
        return parts.map { it.toIntOrNull() ?: return null }
    }

    /** True when [candidate] is a higher version than [current] (missing parts count as 0: 0.7 == 0.7.0). */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parseVersion(candidate) ?: return false
        val b = parseVersion(current) ?: return true
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * The release in a `GET /repos/{owner}/{repo}/releases/latest` answer, or null when it is a draft /
     * pre-release, has no version tag or no APK attached.
     */
    fun release(json: JSONObject): AppRelease? {
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val tag = json.optString("tag_name").ifEmpty { return null }
        parseVersion(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) && it.optString("browser_download_url").startsWith("https://") }
            ?: return null
        return AppRelease(
            version = tag.trim().removePrefix("v").removePrefix("V"),
            tag = tag,
            notes = json.optString("body").trim(),
            apkUrl = apk.getString("browser_download_url"),
            apkName = apk.optString("name"),
            apkSize = apk.optLong("size"),
            pageUrl = json.optString("html_url"),
        )
    }
}
