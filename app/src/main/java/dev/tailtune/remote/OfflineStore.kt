package dev.tailtune.remote

import android.content.Context
import android.os.Environment
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Persists playlist metadata in internal storage and audio in app-specific
 * external storage. A removable SD-card directory is preferred when available.
 */
class OfflineStore(private val context: Context) {
    private val lock = Any()
    private val metadataFile = File(context.filesDir, "tailtune_offline_library.json")
    private val storageRoot: File = chooseStorageRoot()
    private val audioDirectory = File(storageRoot, "audio").apply { mkdirs() }
    private var root: JSONObject = loadRoot()

    fun storageJson(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("path", storageRoot.absolutePath)
            .put("removable", runCatching { Environment.isExternalStorageRemovable(storageRoot) }.getOrDefault(false))
            .put("usedBytes", directorySize(audioDirectory))
            .put("usableBytes", storageRoot.usableSpace)
    }

    fun listOfflinePlaylists(): List<RemotePlaylist> = synchronized(lock) {
        val playlists = root.getJSONObject(KEY_PLAYLISTS)
        val result = mutableListOf<RemotePlaylist>()
        val keys = playlists.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            recordToPlaylist(playlists.optJSONObject(id))?.let(result::add)
        }
        result.sortedBy { it.summary.name.lowercase() }
    }

    fun getPlaylist(playlistId: String): RemotePlaylist? = synchronized(lock) {
        recordToPlaylist(root.getJSONObject(KEY_PLAYLISTS).optJSONObject(playlistId))
    }

    fun getStatus(playlistId: String): OfflinePlaylistStatus? = synchronized(lock) {
        recordToStatus(root.getJSONObject(KEY_PLAYLISTS).optJSONObject(playlistId))
    }

    fun allStatuses(): List<OfflinePlaylistStatus> = synchronized(lock) {
        val playlists = root.getJSONObject(KEY_PLAYLISTS)
        val result = mutableListOf<OfflinePlaylistStatus>()
        val keys = playlists.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            recordToStatus(playlists.optJSONObject(id))?.let(result::add)
        }
        result.sortedBy { it.name.lowercase() }
    }

    fun beginPlaylist(playlist: RemotePlaylist) = synchronized(lock) {
        val playlists = root.getJSONObject(KEY_PLAYLISTS)
        val old = playlists.optJSONObject(playlist.summary.id)
        val oldDownloaded = jsonStringSet(old?.optJSONArray("downloadedIds"))
        val currentSongIds = playlist.songs.mapTo(hashSetOf()) { it.id }
        val preserved = oldDownloaded.filterTo(linkedSetOf()) { id ->
            id in currentSongIds && localFileLocked(id)?.isFile == true
        }

        playlists.put(
            playlist.summary.id,
            JSONObject()
                .put("summary", playlist.summary.copy(songCount = playlist.songs.size).toJson())
                .put("songs", JSONArray().apply { playlist.songs.forEach { put(it.toJson()) } })
                .put("downloadedIds", JSONArray(preserved.toList()))
                .put("complete", preserved.size == playlist.songs.size && playlist.songs.isNotEmpty())
                .put("updatedAt", System.currentTimeMillis())
        )
        saveLocked()
    }

    fun targetFile(song: RemoteSong): File = synchronized(lock) {
        val existing = localFileLocked(song.id)
        if (existing != null) return@synchronized existing
        File(audioDirectory, "${sha256(song.id)}.${song.preferredExtension()}")
    }

    fun registerDownloadedSong(playlistId: String, song: RemoteSong, file: File) = synchronized(lock) {
        require(file.isFile && file.length() > 0L) { "Downloaded file is empty" }
        val playlists = root.getJSONObject(KEY_PLAYLISTS)
        val record = playlists.optJSONObject(playlistId)
            ?: throw IllegalStateException("Offline playlist metadata is missing")

        root.getJSONObject(KEY_SONG_FILES).put(song.id, file.name)
        val ids = jsonStringSet(record.optJSONArray("downloadedIds"))
        ids += song.id
        record.put("downloadedIds", JSONArray(ids.toList()))
        record.put("updatedAt", System.currentTimeMillis())
        record.put("complete", ids.size >= (record.optJSONArray("songs")?.length() ?: 0))
        saveLocked()
    }

    fun markPlaylistComplete(playlistId: String) = synchronized(lock) {
        val record = root.getJSONObject(KEY_PLAYLISTS).optJSONObject(playlistId) ?: return@synchronized
        val songs = jsonSongs(record.optJSONArray("songs"))
        val complete = songs.isNotEmpty() && songs.all { localFileLocked(it.id)?.isFile == true }
        record.put("complete", complete)
        record.put("updatedAt", System.currentTimeMillis())
        saveLocked()
    }

    fun localFile(songId: String): File? = synchronized(lock) { localFileLocked(songId) }

    fun localUri(songId: String): Uri? = localFile(songId)?.let { Uri.fromFile(it) }

    fun isSongAvailable(songId: String): Boolean = localFile(songId)?.isFile == true

    fun removePlaylist(playlistId: String) = synchronized(lock) {
        val playlists = root.getJSONObject(KEY_PLAYLISTS)
        playlists.remove(playlistId)

        val referenced = linkedSetOf<String>()
        val playlistKeys = playlists.keys()
        while (playlistKeys.hasNext()) {
            val id = playlistKeys.next()
            referenced += jsonStringSet(playlists.optJSONObject(id)?.optJSONArray("downloadedIds"))
        }

        val songFiles = root.getJSONObject(KEY_SONG_FILES)
        val staleIds = mutableListOf<String>()
        val songKeys = songFiles.keys()
        while (songKeys.hasNext()) {
            val songId = songKeys.next()
            if (songId !in referenced) staleIds += songId
        }
        staleIds.forEach { songId ->
            val fileName = songFiles.optString(songId, "")
            if (fileName.isNotBlank()) File(audioDirectory, fileName).delete()
            songFiles.remove(songId)
        }
        saveLocked()
    }

    private fun localFileLocked(songId: String): File? {
        val fileName = root.getJSONObject(KEY_SONG_FILES).optString(songId, "")
        if (fileName.isBlank()) return null
        return File(audioDirectory, fileName).takeIf { it.isFile && it.length() > 0L }
    }

    private fun recordToPlaylist(record: JSONObject?): RemotePlaylist? {
        record ?: return null
        val summary = record.optJSONObject("summary")?.let { PlaylistSummary.fromJson(it) } ?: return null
        val songs = jsonSongs(record.optJSONArray("songs"))
        return RemotePlaylist(summary.copy(songCount = songs.size), songs)
    }

    private fun recordToStatus(record: JSONObject?): OfflinePlaylistStatus? {
        record ?: return null
        val playlist = recordToPlaylist(record) ?: return null
        val downloaded = jsonStringSet(record.optJSONArray("downloadedIds"))
            .count { localFileLocked(it)?.isFile == true }
        val total = playlist.songs.size
        return OfflinePlaylistStatus(
            playlistId = playlist.summary.id,
            name = playlist.summary.name,
            downloadedCount = downloaded,
            totalCount = total,
            complete = total > 0 && downloaded >= total && record.optBoolean("complete", false),
            updatedAt = record.optLong("updatedAt", 0L)
        )
    }

    private fun jsonSongs(array: JSONArray?): List<RemoteSong> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { RemoteSong.fromJson(it) }
        }
    }

    private fun jsonStringSet(array: JSONArray?): LinkedHashSet<String> {
        val result = linkedSetOf<String>()
        if (array != null) {
            for (index in 0 until array.length()) {
                array.optString(index).takeIf { it.isNotBlank() }?.let(result::add)
            }
        }
        return result
    }

    private fun chooseStorageRoot(): File {
        val candidates = context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC)
            .filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

        val removable = candidates.firstOrNull {
            runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false)
        }
        val base: File = removable ?: candidates.firstOrNull() ?: context.filesDir
        return File(base, "TailTuneOffline").apply { mkdirs() }
    }

    private fun loadRoot(): JSONObject {
        val loaded = runCatching {
            if (metadataFile.isFile) JSONObject(metadataFile.readText(Charsets.UTF_8)) else JSONObject()
        }.getOrElse { JSONObject() }
        if (!loaded.has(KEY_PLAYLISTS)) loaded.put(KEY_PLAYLISTS, JSONObject())
        if (!loaded.has(KEY_SONG_FILES)) loaded.put(KEY_SONG_FILES, JSONObject())
        loaded.put("version", 1)
        return loaded
    }

    private fun saveLocked() {
        val temporary = File(context.filesDir, "tailtune_offline_library.tmp")
        FileOutputStream(temporary).use { output ->
            val bytes = root.toString().toByteArray(Charsets.UTF_8)
            output.write(bytes)
            output.flush()
            runCatching { output.fd.sync() }
        }
        if (!temporary.renameTo(metadataFile)) {
            temporary.copyTo(metadataFile, overwrite = true)
            temporary.delete()
        }
    }

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter { it.isFile }
        .sumOf { it.length() }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val KEY_PLAYLISTS = "playlists"
        private const val KEY_SONG_FILES = "songFiles"
    }
}
