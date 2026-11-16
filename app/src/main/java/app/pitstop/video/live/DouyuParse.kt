package app.pitstop.video.live

import app.pitstop.video.core.Category
import org.json.JSONObject
import java.security.MessageDigest

/** One live room as listed by Douyu. [viewers] is Douyu's heat number (what the site shows as "watching"). */
data class DouyuRoom(
    val rid: String,
    val title: String,
    val streamer: String,
    val viewers: Long?,
    val cover: String?,
)

/** One quality step from getH5PlayV1's `multirates`; [height] is estimated from the name / rate code. */
data class DouyuRate(val rate: Int, val name: String, val height: Int)

/** Signed parameters for `/lapi/live/getH5PlayV1/{rid}`. */
data class DouyuSignParams(val encData: String, val tt: String, val did: String, val auth: String)

/**
 * Pure (Android-free) Douyu logic: JSON parsing, quality choice and the play-URL signature.
 *
 * Signing (as of Nov 2026): `GET /wgapi/livenc/liveweb/websec/getEncryption?did=` returns
 * `rand_str`, `key`, `enc_time`, `is_special`, `enc_data`. Then
 * `secret = rand_str; repeat(enc_time) { secret = md5(secret + key) }` and
 * `auth = md5(secret + key + (if is_special == 1 "" else rid + tt))`.
 */
object DouyuParse {
    /** Douyu error code for "room not live" (房间未开播); -3 is "room does not exist". */
    const val ERR_OFFLINE = -5
    const val ERR_NO_ROOM = -3

    fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    fun auth(randStr: String, key: String, encTime: Int, isSpecial: Boolean, rid: String, tt: String): String {
        var secret = randStr
        repeat(encTime) { secret = md5(secret + key) }
        return md5(secret + key + if (isSpecial) "" else rid + tt)
    }

    /** Builds the signed form fields from a getEncryption response. */
    fun signParams(encryptionJson: String, rid: String, did: String, tt: String): DouyuSignParams {
        val root = JSONObject(encryptionJson)
        require(root.optInt("error", -1) == 0) { "getEncryption error ${root.opt("error")}" }
        val d = root.getJSONObject("data")
        val a = auth(
            d.getString("rand_str"), d.getString("key"), d.optInt("enc_time", 1),
            d.optInt("is_special", 0) == 1, rid, tt,
        )
        return DouyuSignParams(d.getString("enc_data"), tt, did, a)
    }

    /** Rooms from `/japi/weblist/apinc/allpage/6/{page}` (data.rl; type 1 = live room, others are ads). */
    fun parseListPage(json: String): List<DouyuRoom> {
        val rl = JSONObject(json).optJSONObject("data")?.optJSONArray("rl") ?: return emptyList()
        return (0 until rl.length()).mapNotNull { i ->
            val o = rl.optJSONObject(i) ?: return@mapNotNull null
            if (o.optInt("type", 1) != 1) return@mapNotNull null
            val rid = o.optLong("rid", 0).takeIf { it > 0 } ?: return@mapNotNull null
            DouyuRoom(
                rid = rid.toString(),
                title = o.optString("rn"),
                streamer = o.optString("nn"),
                viewers = o.optLong("ol", -1).takeIf { it >= 0 },
                cover = httpsUrl(o.optString("rs16").ifEmpty { o.optString("rs1") }),
            )
        }
    }

    /**
     * Categories from `m.douyu.com/api/cate/list`: per parent area (cate1Info, in the site's order) an
     * "all of it" entry (key `1_<cate1Id>`, titled by [allOf]) and then its child categories (cate2Info,
     * key `2_<cate2Id>`) with the most live rooms first. Keys are the path part of [directoryPath].
     */
    fun parseCategories(json: String, allOf: (String) -> String): List<Category> {
        val data = JSONObject(json).optJSONObject("data") ?: return emptyList()
        val parents = data.optJSONArray("cate1Info") ?: return emptyList()
        val children = data.optJSONArray("cate2Info")
        val kids = (0 until (children?.length() ?: 0)).mapNotNull { children?.optJSONObject(it) }
        val out = mutableListOf<Category>()
        for (i in 0 until parents.length()) {
            val p = parents.optJSONObject(i) ?: continue
            val pid = p.optInt("cate1Id", 0).takeIf { it > 0 } ?: continue
            val group = p.optString("cate1Name").trim()
            if (group.isEmpty()) continue
            out += Category("1_$pid", allOf(group), group)
            kids.filter { it.optInt("cate1Id") == pid }
                .sortedByDescending { it.optInt("count") }
                .forEach { c ->
                    val cid = c.optInt("cate2Id", 0).takeIf { it > 0 } ?: return@forEach
                    val name = c.optString("cate2Name").trim()
                    if (name.isEmpty()) return@forEach
                    out += Category("2_$cid", name, group, httpsUrl(c.optString("icon").ifEmpty { c.optString("pic") }))
                }
        }
        return out.distinctBy { it.key }
    }

    /** True for keys made by [parseCategories] (`1_<id>` parent area, `2_<id>` category). */
    fun isCategoryKey(key: String) =
        key.length > 2 && key[0] in "12" && key[1] == '_' && key.drop(2).all { it.isDigit() }

    /** Room list of a category (key from [parseCategories]); same JSON shape as [parseListPage] plus `pgcnt`. */
    fun directoryPath(key: String, page: Int) = "/gapi/rkc/directory/mixList/$key/$page"

    /** `data.pgcnt` of a list page: how many pages the list has (0 when missing). */
    fun pageCount(json: String): Int = JSONObject(json).optJSONObject("data")?.optInt("pgcnt", 0) ?: 0

    /** Next page of a paged list, or null: stops at an empty page or past [pageCount] (when known). */
    fun nextPage(page: Int, returned: Int, pageCount: Int, maxPages: Int): Int? = when {
        returned == 0 -> null
        pageCount in 1..page -> null
        page >= maxPages -> null
        else -> page + 1
    }

    /** `data.total` of a searchShow response (rooms matching, live or not). */
    fun searchTotal(json: String): Int = JSONObject(json).optJSONObject("data")?.optInt("total", 0) ?: 0

    /** Rooms from `/japi/search/api/searchShow` (data.relateShow). */
    fun parseSearch(json: String): List<DouyuRoom> {
        val arr = JSONObject(json).optJSONObject("data")?.optJSONArray("relateShow") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val rid = o.optLong("rid", 0).takeIf { it > 0 } ?: return@mapNotNull null
            DouyuRoom(
                rid = rid.toString(),
                title = o.optString("roomName"),
                streamer = o.optString("nickName"),
                viewers = parseHot(o.optString("hot")),
                cover = httpsUrl(o.optString("roomSrc")),
            )
        }
    }

    /** "250.1万" -> 2501000, "1.2亿" -> 120000000, "8345" -> 8345. */
    fun parseHot(text: String?): Long? {
        val t = text?.trim().orEmpty()
        if (t.isEmpty()) return null
        val mult = when {
            t.endsWith("万") -> 10_000.0
            t.endsWith("亿") -> 100_000_000.0
            else -> 1.0
        }
        val num = t.trimEnd('万', '亿').toDoubleOrNull() ?: return null
        return (num * mult).toLong()
    }

    fun httpsUrl(url: String?): String? = when {
        url.isNullOrBlank() || url == "null" -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> url
    }

    /** Height a quality name / rate code stands for: "原画1080P60" -> 1080, "原画2K60" -> 1440, 超清 -> 720. */
    fun heightOf(name: String, rate: Int): Int {
        Regex("""(\d{3,4})[Pp]""").find(name)?.let { return it.groupValues[1].toInt() }
        if (name.contains("4K", ignoreCase = true)) return 2160
        if (name.contains("2K", ignoreCase = true)) return 1440
        return when (rate) {
            4 -> 1080 // 蓝光4M
            3 -> 720 // 超清
            2 -> 540 // 高清
            1 -> 360 // 流畅
            else -> 1080 // 0 = 原画 (source) of unknown size
        }
    }

    fun parseRates(data: JSONObject): List<DouyuRate> {
        val arr = data.optJSONArray("multirates") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name")
            val rate = o.optInt("rate", -1).takeIf { it >= 0 } ?: return@mapNotNull null
            DouyuRate(rate, name, heightOf(name, rate))
        }
    }

    /**
     * Highest rate whose height fits [maxHeight] (ties: the higher bitrate, i.e. lower rate code except 0 = source);
     * the lowest one when none fits or [audioOnly].
     */
    fun chooseRate(rates: List<DouyuRate>, maxHeight: Int, audioOnly: Boolean): DouyuRate? {
        if (rates.isEmpty()) return null
        val byQuality = rates.sortedWith(compareBy<DouyuRate> { it.height }.thenBy { if (it.rate == 0) Int.MAX_VALUE else it.rate })
        if (audioOnly) return byQuality.first()
        return byQuality.lastOrNull { it.height <= maxHeight } ?: byQuality.first()
    }

    sealed interface PlayResult {
        data class Ok(val url: String, val rate: Int, val rates: List<DouyuRate>) : PlayResult
        data object Offline : PlayResult
        data class Failed(val reason: String) : PlayResult
    }

    /** Parses a getH5PlayV1 response into the FLV URL (rtmp_url + "/" + rtmp_live). */
    fun parsePlay(json: String): PlayResult {
        val root = JSONObject(json)
        val err = root.optInt("error", Int.MIN_VALUE)
        if (err == ERR_OFFLINE || err == ERR_NO_ROOM) return PlayResult.Offline
        if (err != 0) return PlayResult.Failed("error $err ${root.optString("msg")}")
        val d = root.optJSONObject("data") ?: return PlayResult.Failed("no data")
        val base = d.optString("rtmp_url")
        val live = d.optString("rtmp_live")
        if (base.isEmpty() || live.isEmpty()) return PlayResult.Failed("no url")
        val url = httpsUrl("$base/$live")!!
        return PlayResult.Ok(url, d.optInt("rate", -1), parseRates(d))
    }
}
