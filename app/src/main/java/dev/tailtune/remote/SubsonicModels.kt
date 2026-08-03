package dev.tailtune.remote

import org.json.JSONObject

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
}

data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Long,
    val coverArtId: String?,
    val contentType: String?
) {
    fun toJson(index: Int? = null): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationSeconds", durationSeconds)
        .put("coverArtId", coverArtId ?: JSONObject.NULL)
        .put("contentType", contentType ?: JSONObject.NULL)
        .also { if (index != null) it.put("index", index) }
}

data class RemotePlaylist(
    val summary: PlaylistSummary,
    val songs: List<RemoteSong>
)
