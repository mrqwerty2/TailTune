package dev.tailtune.remote

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CancellationException

class SubsonicClient(private val settings: ServerSettings) {
    init {
        require(settings.configured) { "Navidrome settings are incomplete" }
        settings.validationError()?.let { throw IllegalArgumentException(it) }
    }

    // A Subsonic token may safely reuse its random salt for this in-memory
    // client. Computing one token avoids hundreds of SecureRandom/MD5 calls
    // while preparing a large playback queue.
    private val authSalt: String = ByteArray(8)
        .also(SecureRandom()::nextBytes)
        .joinToString("") { "%02x".format(it) }
    private val authToken: String = md5(settings.password + authSalt)

    fun ping() {
        requestJson("ping")
    }

    fun getPlaylists(): List<PlaylistSummary> {
        val response = requestJson("getPlaylists")
        val playlistsObject = response.optJSONObject("playlists") ?: return emptyList()
        return jsonObjects(playlistsObject.opt("playlist")).mapNotNull { item ->
            val id = item.optString("id").safeIdentifierOrNull() ?: return@mapNotNull null
            PlaylistSummary(
                id = id,
                name = item.optString("name", "Untitled playlist")
                    .safeMetadata("Untitled playlist", MAX_PLAYLIST_NAME_LENGTH),
                owner = item.optString("owner", "")
                    .safeMetadata("", MAX_OWNER_LENGTH),
                songCount = item.optInt("songCount", 0).coerceAtLeast(0),
                durationSeconds = item.optLong("duration", 0L).coerceAtLeast(0L),
                coverArtId = item.optString("coverArt").safeIdentifierOrNull()
            )
        }.sortedBy { it.name.lowercase(Locale.ROOT) }
    }

    fun getPlaylist(id: String): RemotePlaylist {
        require(id.isNotBlank()) { "Playlist ID is missing" }
        val response = requestJson("getPlaylist", mapOf("id" to id))
        val playlist = response.optJSONObject("playlist")
            ?: throw IllegalStateException("Navidrome returned no playlist")

        val summary = PlaylistSummary(
            id = playlist.optString("id", id).safeIdentifierOrNull() ?: id,
            name = playlist.optString("name", "Untitled playlist")
                .safeMetadata("Untitled playlist", MAX_PLAYLIST_NAME_LENGTH),
            owner = playlist.optString("owner", "")
                .safeMetadata("", MAX_OWNER_LENGTH),
            songCount = playlist.optInt("songCount", 0).coerceAtLeast(0),
            durationSeconds = playlist.optLong("duration", 0L).coerceAtLeast(0L),
            coverArtId = playlist.optString("coverArt").safeIdentifierOrNull()
        )

        val songs = jsonObjects(playlist.opt("entry"))
            .asSequence()
            .filterNot { it.optBoolean("isDir", false) }
            .mapNotNull(::songFromJsonOrNull)
            .toList()

        return RemotePlaylist(summary.copy(songCount = songs.size), songs)
    }

    fun streamUrl(songId: String): String {
        require(songId.isNotBlank()) { "Song ID is missing" }
        return buildUrl(
            endpoint = "stream",
            extra = mapOf(
                "id" to songId,
                "format" to "raw",
                "estimateContentLength" to "true"
            )
        )
    }

    fun coverArtUrl(coverArtId: String): String = buildUrl(
        endpoint = "getCoverArt",
        extra = mapOf("id" to coverArtId, "size" to "500")
    )

    /** Downloads to [target] and resumes an existing partial file when supported. */
    fun downloadSong(
        song: RemoteSong,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ) {
        downloadSongAttempt(song, target, isCancelled, onProgress, allowRestart = true)
    }

    private fun downloadSongAttempt(
        song: RemoteSong,
        target: File,
        isCancelled: () -> Boolean,
        onProgress: (Long, Long) -> Unit,
        allowRestart: Boolean
    ) {
        if (isCancelled()) throw CancellationException("Download cancelled")
        target.parentFile?.mkdirs()
        val existingBytes = target.takeIf(File::isFile)?.length()?.coerceAtLeast(0L) ?: 0L
        val connection = openConnection(streamUrl(song.id), DOWNLOAD_CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS)
        if (existingBytes > 0L) connection.setRequestProperty("Range", "bytes=$existingBytes-")

        try {
            val code = connection.responseCode
            if (code == 416 && allowRestart) {
                val serverTotal = parseUnsatisfiedContentRange(
                    connection.getHeaderField("Content-Range")
                )
                val alreadyComplete = existingBytes > 0L && (
                    (song.sizeBytes > 0L && existingBytes == song.sizeBytes) ||
                        (serverTotal > 0L && existingBytes == serverTotal)
                    )
                if (alreadyComplete) {
                    onProgress(existingBytes, serverTotal.takeIf { it > 0L } ?: existingBytes)
                    return
                }
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not restart the partial download")
                }
                return downloadSongAttempt(song, target, isCancelled, onProgress, allowRestart = false)
            }
            if (code !in 200..299) {
                val body = connection.errorStream?.let { readLimitedText(it, ERROR_BODY_LIMIT_BYTES) }.orEmpty()
                throw IOException("Download failed with HTTP $code${body.toSafeSuffix()}")
            }
            val responseType = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if (responseType.startsWith("application/json") ||
                responseType.startsWith("text/html") ||
                responseType.startsWith("text/plain")
            ) {
                val body = connection.inputStream
                    .let { readLimitedText(it, ERROR_BODY_LIMIT_BYTES) }
                throw IOException("Navidrome returned $responseType instead of audio${body.toSafeSuffix()}")
            }

            val contentRange = parseContentRange(connection.getHeaderField("Content-Range"))
            if (code == HttpURLConnection.HTTP_PARTIAL &&
                existingBytes > 0L && contentRange?.start != existingBytes
            ) {
                if (!allowRestart) throw IOException("Navidrome returned an invalid resume range")
                if (target.exists() && !target.delete()) {
                    throw IOException("Could not restart the partial download")
                }
                return downloadSongAttempt(song, target, isCancelled, onProgress, allowRestart = false)
            }

            val append = existingBytes > 0L && code == HttpURLConnection.HTTP_PARTIAL
            val startingBytes = if (append) existingBytes else 0L
            if (!append && existingBytes > 0L && target.exists() && !target.delete()) {
                throw IOException("Could not replace the partial download")
            }

            val responseBytes = connection.contentLengthLong.takeIf { it > 0L } ?: 0L
            val totalBytes = contentRange?.total
                ?: responseBytes.takeIf { it > 0L }?.plus(startingBytes)
                ?: song.sizeBytes.takeIf { it > 0L }
                ?: 0L

            FileOutputStream(target, append).use { output ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var downloaded = startingBytes
                    onProgress(downloaded, totalBytes)
                    while (true) {
                        if (isCancelled() || Thread.currentThread().isInterrupted) {
                            throw CancellationException("Download cancelled")
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded, totalBytes)
                    }
                    // Make a completed partial durable before OfflineDownloadManager
                    // atomically promotes it to the final filename. This minimizes
                    // corruption after battery loss/process death during a download.
                    output.flush()
                    output.fd.sync()
                }
            }

            if (!target.isFile || target.length() <= 0L) {
                throw IOException("Downloaded file is empty")
            }
            if (totalBytes > 0L && target.length() != totalBytes) {
                throw IOException(
                    "Download size mismatch (${target.length()} of $totalBytes bytes)"
                )
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun songFromJsonOrNull(item: JSONObject): RemoteSong? {
        val id = item.optString("id").safeIdentifierOrNull() ?: return null
        return RemoteSong(
            id = id,
            title = item.optString("title", "Unknown title")
                .safeMetadata("Unknown title", MAX_TITLE_LENGTH),
            artist = item.optString("artist", "Unknown artist")
                .safeMetadata("Unknown artist", MAX_ARTIST_LENGTH),
            album = item.optString("album", "Unknown album")
                .safeMetadata("Unknown album", MAX_ALBUM_LENGTH),
            durationSeconds = item.optLong("duration", 0L).coerceAtLeast(0L),
            coverArtId = item.optString("coverArt").safeIdentifierOrNull(),
            contentType = item.optString("contentType")
                .safeMetadata("", MAX_CONTENT_TYPE_LENGTH)
                .takeIf(String::isNotBlank),
            suffix = item.optString("suffix")
                .safeMetadata("", MAX_SUFFIX_LENGTH)
                .takeIf(String::isNotBlank),
            sizeBytes = item.optLong("size", 0L).coerceAtLeast(0L)
        )
    }

    private fun String.safeIdentifierOrNull(): String? {
        val normalized = trim()
        if (normalized.isEmpty() || normalized.length > MAX_IDENTIFIER_LENGTH) return null
        if (normalized.any(Char::isISOControl)) return null
        return normalized
    }

    private fun String.safeMetadata(defaultValue: String, maxLength: Int): String {
        val normalized = replace(Regex("[\r\n\t\u0000-\u001F\u007F]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
            .take(maxLength)
        return normalized.ifBlank { defaultValue }
    }

    private fun requestJson(endpoint: String, extra: Map<String, String> = emptyMap()): JSONObject {
        val connection = openConnection(buildUrl(endpoint, extra), API_CONNECT_TIMEOUT_MS, API_READ_TIMEOUT_MS)
        connection.setRequestProperty("Accept", "application/json")

        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { readLimitedText(it, JSON_BODY_LIMIT_BYTES) }.orEmpty()
            if (code !in 200..299) {
                throw IOException("Navidrome HTTP $code${body.toSafeSuffix()}")
            }

            val root = try {
                JSONObject(body)
            } catch (error: JSONException) {
                throw IOException("Navidrome returned invalid JSON", error)
            }
            val response = root.optJSONObject("subsonic-response")
                ?: throw IOException("Invalid Subsonic response")
            if (!response.optString("status").equals("ok", ignoreCase = true)) {
                val apiError = response.optJSONObject("error")
                val message = apiError?.optString("message")?.takeIf(String::isNotBlank)
                throw IOException(message ?: "Navidrome request failed")
            }
            return response
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(url: String, connectTimeout: Int, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            instanceFollowRedirects = true
            useCaches = false
            setRequestProperty("User-Agent", "TailTune/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept-Charset", "utf-8")
            setRequestProperty("Accept-Encoding", "identity")
        }

    private fun buildUrl(endpoint: String, extra: Map<String, String>): String {
        val params = linkedMapOf(
            "u" to settings.username,
            "t" to authToken,
            "s" to authSalt,
            "v" to "1.16.1",
            "c" to "TailTune",
            "f" to "json"
        ).apply { putAll(extra) }
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

    private fun readLimitedText(input: InputStream, limit: Int): String {
        val output = ByteArrayOutputStream(minOf(limit, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        input.use { stream ->
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw IOException("Navidrome response is too large")
                output.write(buffer, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun parseUnsatisfiedContentRange(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        val match = UNSATISFIED_CONTENT_RANGE_PATTERN.matchEntire(value.trim()) ?: return 0L
        return match.groupValues[1].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
    }

    private fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val match = CONTENT_RANGE_PATTERN.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        if (start < 0L || end < start || total <= end) return null
        return ContentRange(start, total)
    }

    private fun String.toSafeSuffix(): String {
        val cleaned = replace(Regex("<[^>]*>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(240)
        return if (cleaned.isBlank()) "" else ": $cleaned"
    }

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private data class ContentRange(val start: Long, val total: Long)

    companion object {
        private const val API_CONNECT_TIMEOUT_MS = 8_000
        private const val API_READ_TIMEOUT_MS = 20_000
        private const val DOWNLOAD_CONNECT_TIMEOUT_MS = 15_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 60_000
        private const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        private const val JSON_BODY_LIMIT_BYTES = 8 * 1024 * 1024
        private const val ERROR_BODY_LIMIT_BYTES = 16 * 1024
        private const val MAX_IDENTIFIER_LENGTH = 2_048
        private const val MAX_PLAYLIST_NAME_LENGTH = 512
        private const val MAX_OWNER_LENGTH = 256
        private const val MAX_TITLE_LENGTH = 1_024
        private const val MAX_ARTIST_LENGTH = 512
        private const val MAX_ALBUM_LENGTH = 512
        private const val MAX_CONTENT_TYPE_LENGTH = 128
        private const val MAX_SUFFIX_LENGTH = 16
        private val CONTENT_RANGE_PATTERN = Regex("bytes\\s+(\\d+)-(\\d+)/(\\d+)")
        private val UNSATISFIED_CONTENT_RANGE_PATTERN = Regex("bytes\\s+\\*/(\\d+)")
    }
}
