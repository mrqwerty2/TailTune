package dev.tailtune.standalone

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

data class RemotePlaylist(val id: String, val name: String, val trackIds: List<String>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("ids", JSONArray(trackIds))
    companion object {
        fun fromJson(o: JSONObject): RemotePlaylist? {
            val id = o.optString("id")
            if (id.isBlank()) return null
            val ids = o.optJSONArray("ids") ?: JSONArray()
            return RemotePlaylist(id, o.optString("name", "Playlist"),
                (0 until ids.length()).mapNotNull { ids.optString(it).takeIf(String::isNotBlank) })
        }
    }
}

data class RemoteCatalog(val songs: List<RemoteSong>, val playlists: List<RemotePlaylist>)

data class RemoteSong(val id: String, val title: String, val artist: String,
    val album: String, val durationMs: Long, val suffix: String) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title)
        .put("artist", artist).put("album", album).put("durationMs", durationMs)
        .put("suffix", suffix)

    companion object {
        fun fromJson(o: JSONObject) = RemoteSong(o.getString("id"), o.optString("title", "Unknown"),
            o.optString("artist", "Unknown"), o.optString("album", "Unknown"),
            o.optLong("durationMs", 0).coerceAtLeast(0), o.optString("suffix", "bin"))
    }
}

/** Subsonic/OpenSubsonic API implementation used by Navidrome; network calls are worker-thread only. */
class NavidromeClient(private val settings: NavidromeSettings) {
    private fun connection(operation: String, params: Map<String, String> = emptyMap()): HttpURLConnection {
        val address = NavidromeWire.url(settings.url, operation, settings.username, settings.password, params)
        return (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 30000
            instanceFollowRedirects = false // Don't leak auth tokens to redirect destinations.
            useCaches = false
            setRequestProperty("Accept", "application/json, audio/*, application/octet-stream")
            setRequestProperty("User-Agent", "TailTuneSamsung/1.2")
        }
    }

    private fun json(operation: String, params: Map<String, String> = emptyMap()): JSONObject {
        val conn = connection(operation, params)
        return try {
            if (conn.responseCode != 200) throw IOException("Navidrome HTTP ${conn.responseCode}")
            val bytes = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    if (output.size() + n > 4_000_000) throw IOException("Server response too large")
                    output.write(buf, 0, n)
                }
                output.toByteArray()
            }
            val root = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("subsonic-response")
            if (root.optString("status") != "ok") {
                val err = root.optJSONObject("error")
                throw IOException("Navidrome API error ${err?.optInt("code", -1)}: ${err?.optString("message", "request failed")}")
            }
            root
        } finally { conn.disconnect() }
    }

    fun ping() { json("ping") }

    private fun arrayOrOne(parent: JSONObject, key: String): List<JSONObject> = when (val value = parent.opt(key)) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    /** Index up to 10000 Navidrome songs, including tracks from playlists. */
    fun fetchCatalog(progress: (Int) -> Unit = {}): List<RemoteSong> {
        val tracks = LinkedHashMap<String, RemoteSong>()
        var offset = 0
        while (offset < 1000 && tracks.size < 10000) {
            val albums = arrayOrOne(json("getAlbumList2", mapOf(
                "type" to "alphabeticalByName", "offset" to offset.toString(), "size" to "25"
            )).optJSONObject("albumList2") ?: JSONObject(), "album")
            if (albums.isEmpty()) break
            for (album in albums) {
                val albumId = album.optString("id")
                if (albumId.isBlank()) continue
                val detail = json("getAlbum", mapOf("id" to albumId)).optJSONObject("album") ?: continue
                for (song in arrayOrOne(detail, "song")) {
                    val id = song.optString("id")
                    if (id.isBlank()) continue
                    val title = song.optString("title", "Unknown")
                    val artist = song.optString("artist", detail.optString("artist", "Unknown"))
                    val albumName = song.optString("album", detail.optString("name", "Unknown"))
                    tracks[id] = RemoteSong(id, title, artist, albumName,
                        song.optLong("duration", 0).coerceIn(0, Int.MAX_VALUE.toLong()) * 1000L,
                        song.optString("suffix", "bin"))
                    if (tracks.size >= 10000) break
                }
                if (tracks.size >= 10000) break
            }
            offset += albums.size
            progress(tracks.size)
            if (albums.size < 25) break
        }
        return tracks.values.toList()
    }

    /** Real Navidrome playlists (not synthetic album groups). Order is taken from entry[]. */
    fun fetchPlaylists(): RemoteCatalog {
        val playlists = arrayOrOne(json("getPlaylists").optJSONObject("playlists") ?: JSONObject(), "playlist")
        val songs = LinkedHashMap<String, RemoteSong>()
        val result = ArrayList<RemotePlaylist>()
        for (summary in playlists.take(250)) {
            val id = summary.optString("id")
            if (id.isBlank()) continue
            val detail = json("getPlaylist", mapOf("id" to id)).optJSONObject("playlist") ?: continue
            val order = ArrayList<String>()
            for (entry in arrayOrOne(detail, "entry")) {
                val songId = entry.optString("id")
                if (songId.isBlank()) continue
                order += songId // Repeat occurrences and Navidrome order are intentionally retained.
                songs[songId] = remoteSong(entry)
            }
            result += RemotePlaylist(id, detail.optString("name", summary.optString("name", "Playlist")), order)
        }
        return RemoteCatalog(songs.values.toList(), result)
    }

    private fun remoteSong(song: JSONObject): RemoteSong = RemoteSong(
        song.optString("id"), song.optString("title", "Unknown"),
        song.optString("artist", "Unknown"), song.optString("album", "Unknown"),
        song.optLong("duration", 0).coerceIn(0, Int.MAX_VALUE.toLong()) * 1000,
        song.optString("suffix", "bin"))

    /** The returned connection MUST be disconnected by the caller, including on cancellation/errors. */
    fun openDownload(songId: String): HttpURLConnection {
        val conn = connection("download", mapOf("id" to songId))
        try {
            if (conn.responseCode != 200) throw IOException("Download HTTP ${conn.responseCode}")
            val type = conn.contentType?.lowercase().orEmpty()
            if (type.contains("json") || type.contains("html") || type.startsWith("text/"))
                throw IOException("Server returned an error document instead of audio")
            return conn
        } catch (e: Exception) { conn.disconnect(); throw e }
    }
}
