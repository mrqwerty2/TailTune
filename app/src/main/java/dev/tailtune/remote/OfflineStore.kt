package dev.tailtune.remote

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * SQLite-backed library cache and offline-file index.
 *
 * The database stores every Navidrome playlist and song that TailTune has seen,
 * so the web UI can open immediately without waiting for the Navidrome server.
 * Audio files are stored in app-specific external storage. A removable SD card
 * is preferred when Android exposes one.
 */
class OfflineStore(private val context: Context) {
    private val lock = Any()
    private val storageRoot: File = chooseStorageRoot()
    private val audioDirectory = File(storageRoot, "audio").apply { mkdirs() }
    private val helper = DatabaseHelper(context).apply { setWriteAheadLoggingEnabled(true) }

    init {
        synchronized(lock) {
            helper.writableDatabase
            migrateLegacyJsonIfNeeded()
        }
    }

    fun storageJson(): JSONObject = synchronized(lock) {
        JSONObject()
            .put("path", storageRoot.absolutePath)
            .put("databasePath", context.getDatabasePath(DATABASE_NAME).absolutePath)
            .put(
                "removable",
                runCatching { Environment.isExternalStorageRemovable(storageRoot) }.getOrDefault(false)
            )
            .put("usedBytes", directorySize(audioDirectory))
            .put("usableBytes", storageRoot.usableSpace)
    }

    fun playlistCount(): Int = synchronized(lock) {
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM playlists", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    fun listPlaylistSummaries(): List<PlaylistSummary> = synchronized(lock) {
        helper.readableDatabase.rawQuery(
            """
            SELECT id, name, owner, song_count, duration_seconds, cover_art_id
            FROM playlists
            ORDER BY name COLLATE NOCASE
            """.trimIndent(),
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toPlaylistSummary())
            }
        }
    }

    fun listOfflinePlaylists(): List<RemotePlaylist> = synchronized(lock) {
        helper.readableDatabase.rawQuery(
            "SELECT id FROM playlists WHERE offline_requested = 1 ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    getPlaylistLocked(cursor.getString(0))?.let(::add)
                }
            }
        }
    }

    fun getPlaylist(playlistId: String): RemotePlaylist? = synchronized(lock) {
        getPlaylistLocked(playlistId)
    }

    fun hasPlaylistSongs(playlistId: String): Boolean = synchronized(lock) {
        helper.readableDatabase.rawQuery(
            "SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlist_id = ? LIMIT 1)",
            arrayOf(playlistId)
        ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
    }

    /**
     * Updates the quickly available playlist list after getPlaylists. Existing
     * song mappings and offline flags are preserved.
     */
    fun updateRemoteSummaries(summaries: List<PlaylistSummary>) = synchronized(lock) {
        val db = helper.writableDatabase
        db.transaction {
            execSQL("UPDATE playlists SET remote_present = 0")
            val now = System.currentTimeMillis()
            summaries.forEach { summary -> upsertPlaylistSummary(this, summary, true, now) }
            delete("playlists", "remote_present = 0 AND offline_requested = 0", null)
            cleanupOrphanSongs(this)
        }
    }

    /** Stores complete playlist metadata and its ordered song list. */
    fun saveRemotePlaylist(playlist: RemotePlaylist) = synchronized(lock) {
        savePlaylistLocked(playlist, remotePresent = true, requestOffline = null)
    }

    fun beginPlaylist(playlist: RemotePlaylist) = synchronized(lock) {
        savePlaylistLocked(playlist, remotePresent = true, requestOffline = true)
        updatePlaylistCompletionLocked(playlist.summary.id)
    }

    fun getStatus(playlistId: String): OfflinePlaylistStatus? = synchronized(lock) {
        statusLocked(playlistId)
    }

    fun allStatuses(): List<OfflinePlaylistStatus> = synchronized(lock) {
        helper.readableDatabase.rawQuery(
            "SELECT id FROM playlists WHERE offline_requested = 1 ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) statusLocked(cursor.getString(0))?.let(::add)
            }
        }
    }

    fun targetFile(song: RemoteSong): File = synchronized(lock) {
        localFileLocked(song.id) ?: File(
            audioDirectory,
            "${sha256(song.id)}.${song.preferredExtension()}"
        )
    }

    fun registerDownloadedSong(playlistId: String, song: RemoteSong, file: File) = synchronized(lock) {
        require(file.isFile && file.length() > 0L) { "Downloaded file is empty" }
        val db = helper.writableDatabase
        db.transaction {
            upsertSong(this, song, file.name)
            val offlineValues = ContentValues().apply {
                put("offline_requested", 1)
                put("updated_at", System.currentTimeMillis())
            }
            update("playlists", offlineValues, "id = ?", arrayOf(playlistId))
        }
        updatePlaylistCompletionLocked(playlistId)
    }

    fun markPlaylistComplete(playlistId: String) = synchronized(lock) {
        updatePlaylistCompletionLocked(playlistId)
    }

    fun localFile(songId: String): File? = synchronized(lock) { localFileLocked(songId) }

    fun localUri(songId: String): Uri? = localFile(songId)?.let(Uri::fromFile)

    fun isSongAvailable(songId: String): Boolean = localFile(songId)?.isFile == true

    /**
     * Removes only the offline copy. The cached Navidrome playlist remains, so
     * the web interface can still display it instantly.
     */
    fun removePlaylist(playlistId: String) = synchronized(lock) {
        val db = helper.writableDatabase
        db.transaction {
            val values = ContentValues().apply {
                put("offline_requested", 0)
                put("offline_complete", 0)
                put("updated_at", System.currentTimeMillis())
            }
            update("playlists", values, "id = ?", arrayOf(playlistId))

            val removableSongs = mutableListOf<Pair<String, String>>()
            rawQuery(
                """
                SELECT s.id, s.local_file_name
                FROM songs s
                WHERE s.local_file_name IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1
                    FROM playlist_songs ps
                    JOIN playlists p ON p.id = ps.playlist_id
                    WHERE ps.song_id = s.id AND p.offline_requested = 1
                  )
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    removableSongs += cursor.getString(0) to cursor.getString(1)
                }
            }

            removableSongs.forEach { (songId, fileName) ->
                File(audioDirectory, fileName).delete()
                val clear = ContentValues().apply { putNull("local_file_name") }
                update("songs", clear, "id = ?", arrayOf(songId))
            }
            cleanupOrphanSongs(this)
        }
    }

    fun close() = synchronized(lock) { helper.close() }

    private fun getPlaylistLocked(playlistId: String): RemotePlaylist? {
        val db = helper.readableDatabase
        val summary = db.rawQuery(
            """
            SELECT id, name, owner, song_count, duration_seconds, cover_art_id
            FROM playlists WHERE id = ?
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.toPlaylistSummary() else null
        } ?: return null

        val songs = db.rawQuery(
            """
            SELECT s.id, s.title, s.artist, s.album, s.duration_seconds,
                   s.cover_art_id, s.content_type, s.suffix, s.size_bytes
            FROM playlist_songs ps
            JOIN songs s ON s.id = ps.song_id
            WHERE ps.playlist_id = ?
            ORDER BY ps.position
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toRemoteSong())
            }
        }
        return RemotePlaylist(summary.copy(songCount = songs.size), songs)
    }

    private fun savePlaylistLocked(
        playlist: RemotePlaylist,
        remotePresent: Boolean,
        requestOffline: Boolean?
    ) {
        val db = helper.writableDatabase
        db.transaction {
            upsertPlaylistSummary(
                this,
                playlist.summary.copy(songCount = playlist.songs.size),
                remotePresent,
                System.currentTimeMillis()
            )
            if (requestOffline != null) {
                val values = ContentValues().apply {
                    put("offline_requested", if (requestOffline) 1 else 0)
                    put("updated_at", System.currentTimeMillis())
                }
                update("playlists", values, "id = ?", arrayOf(playlist.summary.id))
            }

            delete("playlist_songs", "playlist_id = ?", arrayOf(playlist.summary.id))
            playlist.songs.forEachIndexed { position, song ->
                upsertSong(this, song, null)
                insertOrThrow(
                    "playlist_songs",
                    null,
                    ContentValues().apply {
                        put("playlist_id", playlist.summary.id)
                        put("song_id", song.id)
                        put("position", position)
                    }
                )
            }
            cleanupOrphanSongs(this)
        }
        updatePlaylistCompletionLocked(playlist.summary.id)
    }

    private fun statusLocked(playlistId: String): OfflinePlaylistStatus? {
        val db = helper.readableDatabase
        val row = db.rawQuery(
            """
            SELECT p.id, p.name, p.offline_requested, p.offline_complete,
                   p.updated_at,
                   COUNT(ps.song_id) AS total_count,
                   SUM(CASE WHEN s.local_file_name IS NOT NULL THEN 1 ELSE 0 END) AS downloaded_count
            FROM playlists p
            LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id
            LEFT JOIN songs s ON s.id = ps.song_id
            WHERE p.id = ?
            GROUP BY p.id
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.getInt(2) != 1) return null
            OfflinePlaylistStatus(
                playlistId = cursor.getString(0),
                name = cursor.getString(1),
                downloadedCount = cursor.getInt(6),
                totalCount = cursor.getInt(5),
                complete = cursor.getInt(3) == 1,
                updatedAt = cursor.getLong(4)
            )
        }
        return row
    }

    private fun updatePlaylistCompletionLocked(playlistId: String) {
        val db = helper.writableDatabase
        val counts = db.rawQuery(
            """
            SELECT COUNT(ps.song_id),
                   SUM(CASE WHEN s.local_file_name IS NOT NULL THEN 1 ELSE 0 END)
            FROM playlist_songs ps
            JOIN songs s ON s.id = ps.song_id
            WHERE ps.playlist_id = ?
            """.trimIndent(),
            arrayOf(playlistId)
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getInt(1) else 0 to 0
        }
        val complete = counts.first > 0 && counts.second >= counts.first
        val values = ContentValues().apply {
            put("offline_complete", if (complete) 1 else 0)
            put("updated_at", System.currentTimeMillis())
        }
        db.update("playlists", values, "id = ?", arrayOf(playlistId))
    }

    private fun localFileLocked(songId: String): File? {
        val fileName = helper.readableDatabase.rawQuery(
            "SELECT local_file_name FROM songs WHERE id = ?",
            arrayOf(songId)
        ).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        } ?: return null

        val file = File(audioDirectory, fileName)
        if (file.isFile && file.length() > 0L) return file

        val clear = ContentValues().apply { putNull("local_file_name") }
        helper.writableDatabase.update("songs", clear, "id = ?", arrayOf(songId))
        return null
    }

    private fun upsertPlaylistSummary(
        db: SQLiteDatabase,
        summary: PlaylistSummary,
        remotePresent: Boolean,
        updatedAt: Long
    ) {
        val values = ContentValues().apply {
            put("name", summary.name)
            put("owner", summary.owner)
            put("song_count", summary.songCount)
            put("duration_seconds", summary.durationSeconds)
            putNullable("cover_art_id", summary.coverArtId)
            put("remote_present", if (remotePresent) 1 else 0)
            put("updated_at", updatedAt)
        }
        val updated = db.update("playlists", values, "id = ?", arrayOf(summary.id))
        if (updated == 0) {
            values.put("id", summary.id)
            db.insertOrThrow("playlists", null, values)
        }
    }

    private fun upsertSong(db: SQLiteDatabase, song: RemoteSong, localFileName: String?) {
        val values = ContentValues().apply {
            put("title", song.title)
            put("artist", song.artist)
            put("album", song.album)
            put("duration_seconds", song.durationSeconds)
            putNullable("cover_art_id", song.coverArtId)
            putNullable("content_type", song.contentType)
            putNullable("suffix", song.suffix)
            put("size_bytes", song.sizeBytes)
            put("updated_at", System.currentTimeMillis())
            if (localFileName != null) put("local_file_name", localFileName)
        }
        val updated = db.update("songs", values, "id = ?", arrayOf(song.id))
        if (updated == 0) {
            values.put("id", song.id)
            db.insertOrThrow("songs", null, values)
        }
    }

    private fun cleanupOrphanSongs(db: SQLiteDatabase) {
        db.delete(
            "songs",
            "local_file_name IS NULL AND NOT EXISTS (SELECT 1 FROM playlist_songs ps WHERE ps.song_id = songs.id)",
            null
        )
    }

    private fun migrateLegacyJsonIfNeeded() {
        val legacy = File(context.filesDir, "tailtune_offline_library.json")
        if (!legacy.isFile || playlistCount() > 0) return

        runCatching {
            val root = JSONObject(legacy.readText(Charsets.UTF_8))
            val playlistObject = root.optJSONObject("playlists") ?: JSONObject()
            val songFiles = root.optJSONObject("songFiles") ?: JSONObject()
            val keys = playlistObject.keys()
            while (keys.hasNext()) {
                val playlistId = keys.next()
                val record = playlistObject.optJSONObject(playlistId) ?: continue
                val summaryJson = record.optJSONObject("summary") ?: continue
                val songsJson = record.optJSONArray("songs") ?: JSONArray()
                val playlist = RemotePlaylist(
                    PlaylistSummary.fromJson(summaryJson),
                    (0 until songsJson.length()).mapNotNull { index ->
                        songsJson.optJSONObject(index)?.let(RemoteSong::fromJson)
                    }
                )
                beginPlaylist(playlist)
                playlist.songs.forEach { song ->
                    val fileName = songFiles.optString(song.id, "")
                    if (fileName.isNotBlank()) {
                        val file = File(audioDirectory, fileName)
                        if (file.isFile && file.length() > 0L) {
                            registerDownloadedSong(playlistId, song, file)
                        }
                    }
                }
                markPlaylistComplete(playlistId)
            }
            legacy.renameTo(File(context.filesDir, "tailtune_offline_library.migrated.json"))
        }
    }

    private fun chooseStorageRoot(): File {
        val candidates = context.getExternalFilesDirs(Environment.DIRECTORY_MUSIC)
            .filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }

        val removable = candidates.firstOrNull {
            runCatching { Environment.isExternalStorageRemovable(it) }.getOrDefault(false)
        }
        val base = removable ?: candidates.firstOrNull() ?: context.filesDir
        return File(base, "TailTuneOffline").apply { mkdirs() }
    }

    private fun directorySize(directory: File): Long = directory.walkTopDown()
        .filter(File::isFile)
        .sumOf(File::length)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun Cursor.toPlaylistSummary(): PlaylistSummary = PlaylistSummary(
        id = getString(0),
        name = getString(1),
        owner = getString(2),
        songCount = getInt(3),
        durationSeconds = getLong(4),
        coverArtId = if (isNull(5)) null else getString(5)
    )

    private fun Cursor.toRemoteSong(): RemoteSong = RemoteSong(
        id = getString(0),
        title = getString(1),
        artist = getString(2),
        album = getString(3),
        durationSeconds = getLong(4),
        coverArtId = if (isNull(5)) null else getString(5),
        contentType = if (isNull(6)) null else getString(6),
        suffix = if (isNull(7)) null else getString(7),
        sizeBytes = getLong(8)
    )

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private inline fun <T> SQLiteDatabase.transaction(block: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val result = block()
            setTransactionSuccessful()
            result
        } finally {
            endTransaction()
        }
    }

    private class DatabaseHelper(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE playlists (
                    id TEXT PRIMARY KEY NOT NULL,
                    name TEXT NOT NULL,
                    owner TEXT NOT NULL DEFAULT '',
                    song_count INTEGER NOT NULL DEFAULT 0,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    cover_art_id TEXT,
                    remote_present INTEGER NOT NULL DEFAULT 1,
                    offline_requested INTEGER NOT NULL DEFAULT 0,
                    offline_complete INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE songs (
                    id TEXT PRIMARY KEY NOT NULL,
                    title TEXT NOT NULL,
                    artist TEXT NOT NULL,
                    album TEXT NOT NULL,
                    duration_seconds INTEGER NOT NULL DEFAULT 0,
                    cover_art_id TEXT,
                    content_type TEXT,
                    suffix TEXT,
                    size_bytes INTEGER NOT NULL DEFAULT 0,
                    local_file_name TEXT,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE playlist_songs (
                    playlist_id TEXT NOT NULL,
                    song_id TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    PRIMARY KEY (playlist_id, position),
                    FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                    FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX index_playlist_songs_song ON playlist_songs(song_id)")
            db.execSQL("CREATE INDEX index_playlists_offline ON playlists(offline_requested)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v0.4 is the first SQLite schema. Future migrations must be additive.
            if (oldVersion < 1) onCreate(db)
        }
    }

    companion object {
        private const val DATABASE_NAME = "tailtune.db"
        private const val DATABASE_VERSION = 1
    }
}
