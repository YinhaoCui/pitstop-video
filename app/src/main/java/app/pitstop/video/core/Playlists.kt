package app.pitstop.video.core

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** A user playlist; items can mix YouTube, Bilibili and local videos. */
data class Playlist(val id: String, val name: String, val items: List<VideoItem> = emptyList())

/** Pure playlist operations + JSON codec (unit-tested). */
object PlaylistOps {
    /** Same video = same source, id and part (Bilibili parts share one id). */
    fun contains(p: Playlist, item: VideoItem) =
        p.items.any { it.source == item.source && it.id == item.id && it.part == item.part }

    fun add(p: Playlist, item: VideoItem): Playlist = if (contains(p, item)) p else p.copy(items = p.items + item)

    /** Appends [items] in order, skipping ones already in the playlist (or repeated in [items]). */
    fun addAll(p: Playlist, items: List<VideoItem>): Playlist = items.fold(p, ::add)

    fun removeAt(p: Playlist, index: Int): Playlist =
        if (index !in p.items.indices) p else p.copy(items = p.items.filterIndexed { i, _ -> i != index })

    fun move(p: Playlist, from: Int, to: Int): Playlist {
        if (from !in p.items.indices || to !in p.items.indices || from == to) return p
        val list = p.items.toMutableList()
        list.add(to, list.removeAt(from))
        return p.copy(items = list)
    }

    fun toJson(playlists: List<Playlist>): String = JSONArray().apply {
        playlists.forEach { p ->
            put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("items", JSONArray().apply {
                    p.items.forEach { v ->
                        put(JSONObject().apply {
                            put("source", v.source.name)
                            put("id", v.id)
                            put("title", v.title)
                            v.author?.let { put("author", it) }
                            v.thumbnailUrl?.let { put("thumb", it) }
                            v.durationSec?.let { put("duration", it) }
                            v.extra?.let { put("extra", it) }
                            v.part?.let { put("part", it) }
                            v.authorId?.let { put("authorId", it) }
                        })
                    }
                })
            })
        }
    }.toString()

    fun fromJson(text: String): List<Playlist> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val items = o.optJSONArray("items") ?: JSONArray()
            Playlist(
                id = o.getString("id"),
                name = o.getString("name"),
                items = (0 until items.length()).mapNotNull { j ->
                    val v = items.getJSONObject(j)
                    val source = runCatching { SourceId.valueOf(v.getString("source")) }.getOrNull() ?: return@mapNotNull null
                    VideoItem(
                        source = source,
                        id = v.getString("id"),
                        title = v.optString("title"),
                        author = v.optString("author").ifEmpty { null },
                        thumbnailUrl = v.optString("thumb").ifEmpty { null },
                        durationSec = if (v.has("duration")) v.getLong("duration") else null,
                        extra = v.optString("extra").ifEmpty { null },
                        part = if (v.has("part")) v.getInt("part") else null,
                        authorId = v.optString("authorId").ifEmpty { null },
                    )
                },
            )
        }
    }
}

/** Playlists persisted as JSON in app-private storage; shared by phone UI and car screens. */
class PlaylistStore(private val file: File) {
    private val _playlists = MutableStateFlow(load())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    fun get(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    fun create(name: String, items: List<VideoItem> = emptyList()): Playlist {
        val p = Playlist(UUID.randomUUID().toString(), name.trim().ifEmpty { "Playlist" }, items)
        mutate { it + p }
        return p
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim().ifEmpty { it.name }) }
    fun delete(id: String) = mutate { list -> list.filterNot { it.id == id } }
    fun add(id: String, item: VideoItem) = edit(id) { PlaylistOps.add(it, item) }
    fun addAll(id: String, items: List<VideoItem>) = edit(id) { PlaylistOps.addAll(it, items) }
    fun removeAt(id: String, index: Int) = edit(id) { PlaylistOps.removeAt(it, index) }
    fun move(id: String, from: Int, to: Int) = edit(id) { PlaylistOps.move(it, from, to) }
    /** Saves a new order of the same items (Sort & filter → Keep this order). */
    fun reorder(id: String, items: List<VideoItem>) = edit(id) { p -> if (items.size == p.items.size) p.copy(items = items) else p }

    private fun edit(id: String, f: (Playlist) -> Playlist) = mutate { list -> list.map { if (it.id == id) f(it) else it } }

    private fun mutate(f: (List<Playlist>) -> List<Playlist>) {
        _playlists.update(f)
        val snapshot = _playlists.value
        scope.launch {
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(PlaylistOps.toJson(snapshot))
                tmp.renameTo(file)
            }
        }
    }

    private fun load(): List<Playlist> =
        runCatching { if (file.exists()) PlaylistOps.fromJson(file.readText()) else emptyList() }.getOrDefault(emptyList())
}
