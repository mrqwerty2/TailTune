package dev.tailtune.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CancellationException

class SubsonicClient(private val settings: ServerSettings) {
    init {
        require(settings.configured) { "Navidrome settings are incomplete" }
    }

    fun ping() {
        requestJson("ping")
    }

    fun getPlaylists(): List<PlaylistSummary> {
        val response = requestJson("getPlaylists")
        val playlistsObject = response.optJSONObject("playlists") ?: return emptyList()
        return jsonObjects(playlistsObject.opt("playlist")).map { item ->
            PlaylistSummary(
                id = item.getString("id"),
                name = item.optString("name", "Untitled playlist"),
                owner = item.optString("owner", ""),
                songCount = item.optInt("songCount", 0),
                durationSeconds = item.optLong("duration", 0),
                coverArtId = item.optString("coverArt").takeIf { it.isNotBlank() }
            )
        }.sortedBy { it.name.lowercase() }
    }

    fun getPlaylist(id: String): RemotePlaylist {
        val response = requestJson("getPlaylist", mapOf("id" to id))
        val playlist = response.optJSONObject("playlist")
            ?: throw IllegalStateException("Navidrome returned no playlist")

        val summary = PlaylistSummary(
            id = playlist.optString("id", id),
            name = playlist.optString("name", "Untitled playlist"),
            owner = playlist.optString("owner", ""),
            songCount = playlist.optInt("songCount", 0),
            durationSeconds = playlist.optLong("duration", 0),
            coverArtId = playlist.optString("coverArt").takeIf { it.isNotBlank() }
        )

        val songs = jsonObjects(playlist.opt("entry"))
            .filterNot { it.optBoolean("isDir", false) }
            .map { songFromJson(it) }

        return RemotePlaylist(summary.copy(songCount = songs.size), songs)
    }

    fun streamUrl(songId: String): String = buildUrl(
        endpoint = "stream",
        extra = mapOf(
            "id" to songId,
            "format" to "raw",
            "estimateContentLength" to "true"
        )
    )

    fun coverArtUrl(coverArtId: String): String = buildUrl(
        endpoint = "getCoverArt",
        extra = mapOf("id" to coverArtId, "size" to "500")
    )

    fun downloadSong(
        song: RemoteSong,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        val connection = URL(streamUrl(song.id)).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 45_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "*/*")

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                val body = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("Download failed with HTTP $code: ${body.take(300)}")
            }

            val expected = connection.contentLengthLong.takeIf { it > 0L } ?: song.sizeBytes
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                    var downloaded = 0L
                    while (true) {
                        if (isCancelled()) throw CancellationException("Download cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, expected)
                    }
                    runCatching { output.fd.sync() }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun songFromJson(item: JSONObject): RemoteSong = RemoteSong(
        id = item.getString("id"),
        title = item.optString("title", "Unknown title"),
        artist = item.optString("artist", "Unknown artist"),
        album = item.optString("album", "Unknown album"),
        durationSeconds = item.optLong("duration", 0),
        coverArtId = item.optString("coverArt").takeIf { it.isNotBlank() },
        contentType = item.optString("contentType").takeIf { it.isNotBlank() },
        suffix = item.optString("suffix").takeIf { it.isNotBlank() },
        sizeBytes = item.optLong("size", 0L)
    )

    private fun requestJson(endpoint: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val connection = URL(buildUrl(endpoint, extra)).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5_000
        connection.readTimeout = 12_000
        connection.setRequestProperty("Accept", "application/json")

        val code = connection.responseCode
        val input = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = input?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readText()
        }.orEmpty()
        connection.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("Navidrome HTTP $code: ${body.take(300)}")
        }

        val root = JSONObject(body)
        val response = root.optJSONObject("subsonic-response")
            ?: throw IllegalStateException("Invalid Subsonic response")

        if (!response.optString("status").equals("ok", ignoreCase = true)) {
            val error = response.optJSONObject("error")
            throw IllegalStateException(error?.optString("message") ?: "Navidrome request failed")
        }
        return response
    }

    private fun buildUrl(endpoint: String, extra: Map<String, String>): String {
        val saltBytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        val token = md5(settings.password + salt)

        val params = linkedMapOf(
            "u" to settings.username,
            "t" to token,
            "s" to salt,
            "v" to "1.16.1",
            "c" to "TailTune",
            "f" to "json"
        )
        params.putAll(extra)

        val query = params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }
        return "${settings.normalizedBaseUrl()}/rest/$endpoint.view?$query"
    }

    private fun jsonObjects(value: Any?): List<JSONObject> = when (value) {
        is JSONArray -> (0 until value.length()).mapNotNull { value.optJSONObject(it) }
        is JSONObject -> listOf(value)
        else -> emptyList()
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
