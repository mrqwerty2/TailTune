package dev.tailtune.standalone

import android.content.Context
import android.net.Uri
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Navidrome-only indexed library. Playlist IDs/order are supplied by the server. */
data class LocalSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val remote: RemoteSong,
    val cacheFile: File,
) {
    val downloaded: Boolean get() = cacheFile.isFile && cacheFile.length() > 0
    fun json(): JSONObject = JSONObject().put("id", id).put("title", title)
        .put("artist", artist).put("album", album)
        .put("durationMs", durationMs).put("downloaded", downloaded)
}

class MusicLibrary(private val context: Context, private val cache: NavidromeCache) {
    @Volatile var songs: List<LocalSong> = emptyList()
        private set
    @Volatile private var byId: Map<String, LocalSong> = emptyMap()
    @Volatile var navidromePlaylists: List<RemotePlaylist> = emptyList()
        private set

    init { setRemote(cache.loadCatalog(), cache.loadPlaylists()) }

    @Synchronized fun setRemote(remote: List<RemoteSong>, playlists: List<RemotePlaylist>) {
        val mapped = remote.take(10000).map { song ->
            val cached = cache.fileFor(song)
            LocalSong("nav:${song.id}", song.title, song.artist, song.album,
                song.durationMs, Uri.fromFile(cached), remote = song, cacheFile = cached)
        }
        songs = mapped
        byId = mapped.associateBy { it.id }
        navidromePlaylists = playlists
    }

    fun find(id: String): LocalSong? = byId[id]
    fun playlist(id: String): List<LocalSong> = when {
        id == "navidrome" -> songs
        id == "downloaded" -> songs.filter { it.downloaded }
        id.startsWith("navplaylist:") -> navidromePlaylists.firstOrNull {
            it.id == id.removePrefix("navplaylist:")
        }?.trackIds?.mapNotNull { byId["nav:$it"] } ?: emptyList()
        else -> emptyList()
    }

    /** Show true Navidrome playlists first, then library-wide and offline views. */
    fun playlists(offset: Int, limit: Int): JSONObject {
        val list = ArrayList<JSONObject>()
        navidromePlaylists.forEach { list += JSONObject().put("id", "navplaylist:${it.id}")
            .put("name", it.name).put("songCount", it.trackIds.size) }
        list += JSONObject().put("id", "navidrome").put("name", "All Navidrome songs").put("songCount", songs.size)
        list += JSONObject().put("id", "downloaded").put("name", "Downloaded / offline").put("songCount", songs.count { it.downloaded })
        return page(list, offset, limit)
    }

    fun tracks(id: String, offset: Int, limit: Int): JSONObject {
        val items = playlist(id)
        return page(items.drop(offset).take(limit).map { it.json() }, offset, limit, items.size, prePaged = true)
    }

    fun search(q: String, offset: Int, limit: Int): JSONObject {
        val results = songs.filter {
            it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
        }
        return page(results.drop(offset).take(limit).map { it.json() }, offset, limit, results.size, prePaged = true)
    }

    companion object {
        fun page(items: List<JSONObject>, offset: Int, limit: Int, total: Int = items.size, prePaged: Boolean = false): JSONObject {
            val batch = if (prePaged) items else items.drop(offset).take(limit)
            return JSONObject().put("items", JSONArray(batch))
                .put("offset", offset).put("total", total).put("hasMore", offset + batch.size < total)
        }
    }
}
