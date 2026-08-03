package dev.tailtune.remote

import org.json.JSONObject

private fun JSONObject.nullableString(key: String): String? {
    val value = opt(key)
    if (value == null || value === JSONObject.NULL) return null
    return value.toString().takeIf { it.isNotBlank() && it != "null" }
}

data class PlaylistSummary(
    val id: String,
    val name: String,
    val owner: String,
    val songCount: Int,
    val durationSeconds: Long,
    val coverArtId: String?
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("owner", owner)
        .put("songCount", songCount)
        .put("durationSeconds", durationSeconds)
        .put("coverArtId", coverArtId ?: JSONObject.NULL)

    companion object {
        fun fromJson(value: JSONObject): PlaylistSummary = PlaylistSummary(
            id = value.getString("id"),
            name = value.optString("name", "Untitled playlist"),
            owner = value.optString("owner", ""),
            songCount = value.optInt("songCount", 0),
            durationSeconds = value.optLong("durationSeconds", 0L),
            coverArtId = value.nullableString("coverArtId")
        )
    }
}

data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Long,
    val coverArtId: String?,
    val contentType: String?,
    val suffix: String?,
    val sizeBytes: Long
) {
    fun toJson(index: Int? = null): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationSeconds", durationSeconds)
        .put("coverArtId", coverArtId ?: JSONObject.NULL)
        .put("contentType", contentType ?: JSONObject.NULL)
        .put("suffix", suffix ?: JSONObject.NULL)
        .put("sizeBytes", sizeBytes)
        .also { if (index != null) it.put("index", index) }

    fun preferredExtension(): String {
        val cleanedSuffix = suffix
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9]"), "")
            ?.takeIf { it.length in 2..5 }
        if (cleanedSuffix != null) return cleanedSuffix

        return when (contentType?.lowercase()) {
            "audio/mpeg", "audio/mp3" -> "mp3"
            "audio/flac", "audio/x-flac" -> "flac"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            "audio/ogg", "application/ogg" -> "ogg"
            "audio/opus" -> "opus"
            "audio/wav", "audio/x-wav" -> "wav"
            else -> "audio"
        }
    }

    companion object {
        fun fromJson(value: JSONObject): RemoteSong = RemoteSong(
            id = value.getString("id"),
            title = value.optString("title", "Unknown title"),
            artist = value.optString("artist", "Unknown artist"),
            album = value.optString("album", "Unknown album"),
            durationSeconds = value.optLong("durationSeconds", 0L),
            coverArtId = value.nullableString("coverArtId"),
            contentType = value.nullableString("contentType"),
            suffix = value.nullableString("suffix"),
            sizeBytes = value.optLong("sizeBytes", 0L)
        )
    }
}

data class RemotePlaylist(
    val summary: PlaylistSummary,
    val songs: List<RemoteSong>
)

data class OfflinePlaylistStatus(
    val playlistId: String,
    val name: String,
    val downloadedCount: Int,
    val totalCount: Int,
    val complete: Boolean,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("playlistId", playlistId)
        .put("name", name)
        .put("downloadedCount", downloadedCount)
        .put("totalCount", totalCount)
        .put("complete", complete)
        .put("updatedAt", updatedAt)
}
