package app.pitstop.video.core

import org.json.JSONArray
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

    /** "0.7.0-beta.2" -> "beta.2"; null for a final version ("0.7.0", "0.7.0+build5"). */
    fun preRelease(text: String): String? =
        text.trim().substringBefore('+').substringAfter('-', "").ifEmpty { null }

    /**
     * Orders versions like semver: numbers part by part (missing parts count as 0: 0.7 == 0.7.0), then a
     * pre-release comes before its final version (0.7.0-beta.1 < 0.7.0-beta.2 < 0.7.0-rc.1 < 0.7.0).
     * Unparsable versions sort lowest.
     */
    fun compare(a: String, b: String): Int {
        val x = parseVersion(a)
        val y = parseVersion(b)
        if (x == null || y == null) return (if (x == null) 0 else 1) - (if (y == null) 0 else 1)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val p = preRelease(a)
        val q = preRelease(b)
        return when {
            p == null && q == null -> 0
            p == null -> 1
            q == null -> -1
            else -> comparePre(p.split('.'), q.split('.'))
        }
    }

    private fun comparePre(p: List<String>, q: List<String>): Int {
        for (i in 0 until minOf(p.size, q.size)) {
            val m = p[i].toIntOrNull()
            val n = q[i].toIntOrNull()
            val c = when {
                m != null && n != null -> m.compareTo(n)
                m != null -> -1
                n != null -> 1
                else -> p[i].compareTo(q[i])
            }
            if (c != 0) return c
        }
        return p.size.compareTo(q.size)
    }

    /** True when [candidate] is a higher version than [current] (see [compare]). */
    fun isNewer(candidate: String, current: String): Boolean {
        if (parseVersion(candidate) == null) return false
        if (parseVersion(current) == null) return true
        return compare(candidate, current) > 0
    }

    /**
     * The newest usable release in a `GET /repos/{owner}/{repo}/releases` answer (the beta channel):
     * pre-releases count when [allowPreRelease]; drafts and releases without an APK never do.
     */
    fun newest(list: JSONArray, allowPreRelease: Boolean, nav: Boolean = false): AppRelease? =
        (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let { r -> release(r, allowPreRelease, nav) } }
            .reduceOrNull { best, r -> if (compare(r.version, best.version) > 0) r else best }

    /**
     * True for the navigation build's APK. Every release carries both builds:
     * `PitstopVideo-v1.2.3.apk` (parked, "Pitstop Video") and `PitstopVideo-v1.2.3.mirror.apk` (nav,
     * "Mirror Video"). The nav name must sort after the parked one: GitHub lists assets by name, and
     * versions before 0.8.0 take the first `.apk` of a release. (0.8.0 was first published with
     * `MirrorVideo-v0.8.0.apk`, still recognised.)
     */
    fun isNavApk(name: String): Boolean =
        name.endsWith(".mirror.apk", ignoreCase = true) || name.startsWith("MirrorVideo", ignoreCase = true)

    /**
     * The release in a `GET /repos/{owner}/{repo}/releases/latest` answer, or null when it is a draft,
     * a pre-release (unless [allowPreRelease]), has no version tag or no APK of this build ([nav])
     * attached. Each build only updates to its own APK, so a nav install never turns into the parked one.
     */
    fun release(json: JSONObject, allowPreRelease: Boolean = false, nav: Boolean = false): AppRelease? {
        if (json.optBoolean("draft") || (json.optBoolean("prerelease") && !allowPreRelease)) return null
        val tag = json.optString("tag_name").ifEmpty { return null }
        parseVersion(tag) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).mapNotNull { assets.optJSONObject(it) }
            .firstOrNull {
                val name = it.optString("name")
                name.endsWith(".apk", ignoreCase = true) && isNavApk(name) == nav &&
                    it.optString("browser_download_url").startsWith("https://")
            }
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
