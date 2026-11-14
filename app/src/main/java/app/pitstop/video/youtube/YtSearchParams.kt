package app.pitstop.video.youtube

import java.util.Base64

/**
 * YouTube's search "sp" parameter (what the site's Filters menu puts in the URL): a small protobuf,
 * base64. Field 1 = sort order; field 2 = filters { 1: upload date, 2: type (1 = video), 3: length }.
 * Always asks for videos only. Pure; unit-tested ("CAISAhAB" = newest videos).
 */
object YtSearchParams {
    /** 0 relevance, 1 rating, 2 upload date, 3 view count. */
    const val SORT = "sort"
    /** 0 any, 1 last hour, 2 today, 3 this week, 4 this month, 5 this year. */
    const val DATE = "date"
    /** 0 any, 1 under 4 min, 3 4-20 min, 2 over 20 min (YouTube's own numbering). */
    const val LENGTH = "length"

    fun encode(sort: Int, date: Int, length: Int): String {
        val filters = buildList {
            if (date > 0) { add(0x08); add(date) }
            add(0x10); add(1)
            if (length > 0) { add(0x18); add(length) }
        }
        val bytes = buildList {
            if (sort > 0) { add(0x08); add(sort) }
            add(0x12); add(filters.size); addAll(filters)
        }
        return Base64.getEncoder().encodeToString(ByteArray(bytes.size) { bytes[it].toByte() })
    }
}
