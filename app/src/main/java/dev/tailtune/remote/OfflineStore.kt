package dev.tailtune.remote

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * SQLite-backed Navidrome metadata cache and offline-file index.
 *
 * Reads are intentionally not guarded by a process-wide monitor: SQLite WAL can
 * serve readers while a writer is active. Only compound write/file operations
 * are serialized. This prevents the UI, HTTP server and player from blocking
 * each other while a large library is being synchronized.
 */
class OfflineStore(context: Context) {
    private val appContext = context.applicationContext
    private val writeLock = ReentrantLock()
    private val closed = AtomicBoolean(false)
    private val reconcileScheduled = AtomicBoolean(false)
    private val maintenanceExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-StorageMaintenance").apply { isDaemon = true }
    }

    private val storageRoot: File = chooseStorageRoot()
    private val audioDirectory = File(storageRoot, "audio")
    private val storageRemovable = runCatching {
        Environment.isExternalStorageRemovable(storageRoot)
    }.getOrDefault(false)
    private val cachedStorageAvailable = AtomicBoolean(false)
    private val cachedUsableBytes = AtomicLong(0L)
    private val helper = DatabaseHelper(
        appContext,
        CorruptionHandler(appContext)
    ).apply {
        setWriteAheadLoggingEnabled(true)
    }
    // Filled by startup maintenance. Avoid opening SQLite synchronously from
    // Service.onCreate(), which runs on Android's main thread.
    private val managedAudioBytes = AtomicLong(0L)

    fun runStartupMaintenance(onComplete: () -> Unit = {}) {
        scheduleMaintenance {
            runCatching { refreshStorageStats() }
            runCatching { migrateLegacyJsonIfNeeded() }
            runCatching { reconcileAudioIndex() }
            runCatching { managedAudioBytes.set(queryManagedAudioBytes()) }
            runCatching(onComplete)
        }
    }

    /** Constant-time snapshot; filesystem capacity is refreshed only on worker threads. */
    fun storageJson(): JSONObject = JSONObject()
        .put("path", storageRoot.absolutePath)
        .put("databasePath", appContext.getDatabasePath(DATABASE_NAME).absolutePath)
        .put("removable", storageRemovable)
        .put("available", cachedStorageAvailable.get())
        .put("usedBytes", managedAudioBytes.get().coerceAtLeast(0L))
        .put("usableBytes", cachedUsableBytes.get().coerceAtLeast(0L))

    /** Performs filesystem probes; callers must invoke this away from Android's main thread. */
    fun refreshStorageStats(): JSONObject {
        val available = storageAvailable()
        cachedStorageAvailable.set(available)
        cachedUsableBytes.set(
            if (available) runCatching { storageRoot.usableSpace }.getOrDefault(0L) else 0L
        )
        return storageJson()
    }

    fun playlistCount(): Int = readableDb().rawQuery(
        "SELECT COUNT(*) FROM playlists",
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    fun listPlaylistSummaries(): List<PlaylistSummary> = readableDb().rawQuery(
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

    fun listOfflinePlaylists(): List<RemotePlaylist> {
        val ids = readableDb().rawQuery(
            "SELECT id FROM playlists WHERE offline_requested = 1 ORDER BY name COLLATE NOCASE",
            null
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        return ids.mapNotNull(::getPlaylist)
    }

    fun getPlaylist(playlistId: String): RemotePlaylist? {
        val db = readableDb()
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

    fun hasPlaylistSongs(playlistId: String): Boolean = readableDb().rawQuery(
        "SELECT EXISTS(SELECT 1 FROM playlist_songs WHERE playlist_id = ? LIMIT 1)",
        arrayOf(playlistId)
    ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }

    fun needsPlaylistRefresh(
        playlistId: String,
        maxAgeMs: Long = PLAYLIST_DETAIL_MAX_AGE_MS
    ): Boolean = readableDb().rawQuery(
        """
        SELECT p.song_count, p.details_updated_at, COUNT(ps.song_id)
        FROM playlists p
        LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id
        WHERE p.id = ?
        GROUP BY p.id
        """.trimIndent(),
        arrayOf(playlistId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use true
        val expected = cursor.getInt(0)
        val detailsUpdatedAt = cursor.getLong(1)
        val actual = cursor.getInt(2)
        detailsUpdatedAt <= 0L || actual != expected ||
            System.currentTimeMillis() - detailsUpdatedAt > maxAgeMs
    }

    /**
     * Updates the fast summary cache. Empty responses never erase a previously
     * useful cache unless the caller explicitly allows pruning.
     */
    fun updateRemoteSummaries(
        summaries: List<PlaylistSummary>,
        pruneMissing: Boolean = summaries.isNotEmpty()
    ) {
        writeLock.withLock {
            writableDb().transaction {
                if (pruneMissing) execSQL("UPDATE playlists SET remote_present = 0")
                val now = System.currentTimeMillis()
                summaries.forEach { summary ->
                    upsertPlaylistSummary(this, summary, remotePresent = true, updatedAt = now)
                }
                if (pruneMissing) {
                    delete("playlists", "remote_present = 0 AND offline_requested = 0", null)
                    cleanupOrphanSongs(this)
                }
            }
        }
        if (pruneMissing) scheduleMaintenance(::purgeUnreferencedLocalFiles)
    }

    fun saveRemotePlaylist(playlist: RemotePlaylist) {
        writeLock.withLock {
            savePlaylistLocked(playlist, remotePresent = true, requestOffline = null)
        }
        scheduleMaintenance(::purgeUnreferencedLocalFiles)
    }

    fun beginPlaylist(playlist: RemotePlaylist) = writeLock.withLock {
        savePlaylistLocked(playlist, remotePresent = true, requestOffline = true)
        updatePlaylistCompletionLocked(playlist.summary.id)
    }

    fun getStatus(playlistId: String): OfflinePlaylistStatus? = statusQuery(
        "WHERE p.id = ?",
        arrayOf(playlistId)
    ).firstOrNull()

    fun allStatuses(): List<OfflinePlaylistStatus> = statusQuery(
        "WHERE p.offline_requested = 1",
        null
    )

    fun targetFile(song: RemoteSong): File {
        check(storageAvailable()) { "Offline storage is not mounted or writable" }
        return deterministicAudioFile(song)
    }

    /** True only for a non-empty finalized file matching Navidrome metadata when known. */
    fun isCompleteAudioFile(song: RemoteSong, file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        return song.sizeBytes <= 0L || file.length() == song.sizeBytes
    }

    /**
     * Converts a truncated finalized file back into a resumable .part file.
     * Callers run this on a background executor.
     */
    fun demoteIncompleteFinalFile(song: RemoteSong, finalFile: File) = writeLock.withLock {
        if (!finalFile.exists() || isCompleteAudioFile(song, finalFile)) return@withLock
        val partial = File(finalFile.parentFile, "${finalFile.name}.part")
        when {
            song.sizeBytes > 0L && finalFile.length() > song.sizeBytes -> {
                runCatching { finalFile.delete() }
            }
            partial.exists() && partial.length() >= finalFile.length() -> {
                runCatching { finalFile.delete() }
            }
            else -> {
                if (partial.exists()) runCatching { partial.delete() }
                if (!finalFile.renameTo(partial)) {
                    runCatching { finalFile.delete() }
                }
            }
        }
    }

    fun registerDownloadedSong(playlistId: String, song: RemoteSong, file: File) {
        require(isCompleteAudioFile(song, file)) {
            if (!file.isFile || file.length() <= 0L) {
                "Downloaded file is empty"
            } else {
                "Downloaded file size is ${file.length()} bytes; expected ${song.sizeBytes}"
            }
        }
        val newSize = file.length()
        writeLock.withLock {
            val db = writableDb()
            val oldSize = localSizeLocked(db, song.id)
            db.transaction {
                upsertSong(this, song, file.name, newSize)
                val values = ContentValues().apply {
                    put("offline_requested", 1)
                    put("updated_at", System.currentTimeMillis())
                }
                update("playlists", values, "id = ?", arrayOf(playlistId))
            }
            managedAudioBytes.addAndGet(newSize - oldSize)
            updatePlaylistCompletionLocked(playlistId)
        }
    }

    fun markPlaylistComplete(playlistId: String) = writeLock.withLock {
        updatePlaylistCompletionLocked(playlistId)
    }

    fun localFile(songId: String): File? = localFiles(listOf(songId))[songId]

    fun localFiles(songIds: Collection<String>): Map<String, File> {
        if (songIds.isEmpty()) return emptyMap()
        val storageReady = storageAvailable()
        if (!storageReady) return emptyMap()
        val uniqueIds = songIds.filter(String::isNotBlank).distinct()
        if (uniqueIds.isEmpty()) return emptyMap()

        val records = mutableMapOf<String, LocalFileRecord>()
        uniqueIds.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            readableDb().rawQuery(
                """
                SELECT id, local_file_name, local_size_bytes, size_bytes
                FROM songs
                WHERE id IN ($placeholders) AND local_file_name IS NOT NULL
                """.trimIndent(),
                chunk.toTypedArray()
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records[cursor.getString(0)] = LocalFileRecord(
                        songId = cursor.getString(0),
                        fileName = cursor.getString(1),
                        indexedSize = cursor.getLong(2),
                        expectedSize = cursor.getLong(3)
                    )
                }
            }
        }

        val available = mutableMapOf<String, File>()
        records.forEach { (songId, record) ->
            val file = managedAudioFile(record.fileName)
            val actualSize = if (file?.isFile == true) file.length() else 0L
            val expectedMatches = record.expectedSize <= 0L || actualSize == record.expectedSize
            if (actualSize > 0L && expectedMatches) {
                available[songId] = file!!
                if (actualSize != record.indexedSize) {
                    scheduleReconcile()
                }
            } else if (storageReady) {
                // Never play a truncated/corrupt file. Reconciliation clears or
                // demotes it on the dedicated maintenance executor.
                scheduleReconcile()
            }
            // A temporarily unmounted removable card is not treated as deletion.
        }
        return available
    }

    fun localUri(songId: String): Uri? = localFile(songId)?.let(Uri::fromFile)

    fun isSongAvailable(songId: String): Boolean = localFile(songId) != null

    /** Removes only the offline copy; cached Navidrome metadata is retained. */
    fun removePlaylist(playlistId: String) {
        check(storageAvailable()) { "Offline storage is not mounted or writable" }
        val playlistSongHashes = getPlaylist(playlistId)?.songs
            ?.mapTo(mutableSetOf()) { sha256(it.id) }
            .orEmpty()

        var deletedBytes = 0L
        writeLock.withLock {
            val db = writableDb()
            db.transaction {
                val values = ContentValues().apply {
                    put("offline_requested", 0)
                    put("offline_complete", 0)
                    put("updated_at", System.currentTimeMillis())
                }
                update("playlists", values, "id = ?", arrayOf(playlistId))
            }

            val candidates = mutableListOf<LocalFileRecord>()
            db.rawQuery(
                """
                SELECT s.id, s.local_file_name, s.local_size_bytes, s.size_bytes
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
                    candidates += LocalFileRecord(
                        songId = cursor.getString(0),
                        fileName = cursor.getString(1),
                        indexedSize = cursor.getLong(2),
                        expectedSize = cursor.getLong(3)
                    )
                }
            }

            val removed = candidates.filter { record ->
                val file = managedAudioFile(record.fileName)
                val actual = if (file?.isFile == true) file.length() else 0L
                val deleted = file == null || !file.exists() || file.delete()
                if (deleted) deletedBytes += actual.coerceAtLeast(record.indexedSize)
                deleted
            }

            db.transaction {
                removed.forEach { record ->
                    val clear = ContentValues().apply {
                        putNull("local_file_name")
                        put("local_size_bytes", 0L)
                    }
                    update("songs", clear, "id = ?", arrayOf(record.songId))
                }
                cleanupOrphanSongs(this)
            }
        }
        if (deletedBytes > 0L) {
            managedAudioBytes.updateAndGet { current -> (current - deletedBytes).coerceAtLeast(0L) }
        }

        // Explicit removal also discards resumable fragments for this playlist.
        if (playlistSongHashes.isNotEmpty()) {
            audioDirectory.listFiles()?.forEach { file ->
                val hash = file.name.substringBefore('.')
                if (file.isFile && file.name.endsWith(".part") && hash in playlistSongHashes) {
                    runCatching { file.delete() }
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        maintenanceExecutor.shutdownNow()
        runCatching { maintenanceExecutor.awaitTermination(1, TimeUnit.SECONDS) }
        writeLock.withLock { helper.close() }
    }

    fun shutdownMaintenance() {
        maintenanceExecutor.shutdownNow()
    }

    private fun statusQuery(whereClause: String, args: Array<String>?): List<OfflinePlaylistStatus> =
        readableDb().rawQuery(
            """
            SELECT p.id, p.name, p.offline_complete, p.updated_at,
                   COUNT(ps.song_id) AS total_count,
                   COALESCE(SUM(CASE
                       WHEN s.local_file_name IS NOT NULL AND s.local_size_bytes > 0 THEN 1
                       ELSE 0
                   END), 0) AS downloaded_count
            FROM playlists p
            LEFT JOIN playlist_songs ps ON ps.playlist_id = p.id
            LEFT JOIN songs s ON s.id = ps.song_id
            $whereClause
            GROUP BY p.id
            ORDER BY p.name COLLATE NOCASE
            """.trimIndent(),
            args
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        OfflinePlaylistStatus(
                            playlistId = cursor.getString(0),
                            name = cursor.getString(1),
                            downloadedCount = cursor.getInt(5),
                            totalCount = cursor.getInt(4),
                            complete = cursor.getInt(2) == 1,
                            updatedAt = cursor.getLong(3)
                        )
                    )
                }
            }
        }

    private fun savePlaylistLocked(
        playlist: RemotePlaylist,
        remotePresent: Boolean,
        requestOffline: Boolean?
    ) {
        // Audio filenames are deterministic from the Navidrome song ID. If the
        // SQLite index was rebuilt after corruption, reconnect those files as
        // playlist details arrive instead of forcing a second download.
        val recoverableFiles = if (storageAvailable()) {
            playlist.songs.mapNotNull { song ->
                deterministicAudioFile(song).takeIf { isCompleteAudioFile(song, it) }
                    ?.let { song.id to it }
            }.toMap()
        } else {
            emptyMap()
        }

        val db = writableDb()
        val now = System.currentTimeMillis()
        db.transaction {
            upsertPlaylistSummary(
                this,
                playlist.summary.copy(songCount = playlist.songs.size),
                remotePresent,
                now
            )
            val details = ContentValues().apply {
                put("details_updated_at", now)
                put("updated_at", now)
                if (requestOffline != null) {
                    put("offline_requested", if (requestOffline) 1 else 0)
                }
            }
            update("playlists", details, "id = ?", arrayOf(playlist.summary.id))

            delete("playlist_songs", "playlist_id = ?", arrayOf(playlist.summary.id))
            playlist.songs.forEachIndexed { position, song ->
                val recovered = recoverableFiles[song.id]
                upsertSong(
                    this,
                    song,
                    localFileName = recovered?.name,
                    localSizeBytes = recovered?.length()
                )
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
        if (recoverableFiles.isNotEmpty()) {
            managedAudioBytes.set(queryManagedAudioBytes().coerceAtLeast(0L))
        }
        updatePlaylistCompletionLocked(playlist.summary.id)
    }

    private fun updatePlaylistCompletionLocked(playlistId: String) {
        val db = writableDb()
        val counts = db.rawQuery(
            """
            SELECT COUNT(ps.song_id),
                   COALESCE(SUM(CASE
                       WHEN s.local_file_name IS NOT NULL AND s.local_size_bytes > 0 THEN 1
                       ELSE 0
                   END), 0)
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

    private fun upsertPlaylistSummary(
        db: SQLiteDatabase,
        summary: PlaylistSummary,
        remotePresent: Boolean,
        updatedAt: Long
    ) {
        val values = ContentValues().apply {
            put("name", summary.name)
            put("owner", summary.owner)
            put("song_count", summary.songCount.coerceAtLeast(0))
            put("duration_seconds", summary.durationSeconds.coerceAtLeast(0L))
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

    private fun upsertSong(
        db: SQLiteDatabase,
        song: RemoteSong,
        localFileName: String?,
        localSizeBytes: Long?
    ) {
        val values = ContentValues().apply {
            put("title", song.title)
            put("artist", song.artist)
            put("album", song.album)
            put("duration_seconds", song.durationSeconds.coerceAtLeast(0L))
            putNullable("cover_art_id", song.coverArtId)
            putNullable("content_type", song.contentType)
            putNullable("suffix", song.suffix)
            put("size_bytes", song.sizeBytes.coerceAtLeast(0L))
            put("updated_at", System.currentTimeMillis())
            if (localFileName != null) put("local_file_name", localFileName)
            if (localSizeBytes != null) put("local_size_bytes", localSizeBytes.coerceAtLeast(0L))
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
            "local_file_name IS NULL AND NOT EXISTS " +
                "(SELECT 1 FROM playlist_songs ps WHERE ps.song_id = songs.id)",
            null
        )
    }

    private fun purgeUnreferencedLocalFiles() {
        if (!storageAvailable()) return
        var removedBytes = 0L
        writeLock.withLock {
            val db = writableDb()
            val records = mutableListOf<LocalFileRecord>()
            db.rawQuery(
                """
                SELECT s.id, s.local_file_name, s.local_size_bytes, s.size_bytes
                FROM songs s
                WHERE s.local_file_name IS NOT NULL
                  AND NOT EXISTS (
                    SELECT 1 FROM playlist_songs ps WHERE ps.song_id = s.id
                  )
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    records += LocalFileRecord(
                        songId = cursor.getString(0),
                        fileName = cursor.getString(1),
                        indexedSize = cursor.getLong(2),
                        expectedSize = cursor.getLong(3)
                    )
                }
            }

            val deleted = records.filter { record ->
                val file = managedAudioFile(record.fileName)
                val actual = if (file?.isFile == true) file.length() else 0L
                val success = file == null || !file.exists() || file.delete()
                if (success) removedBytes += actual.coerceAtLeast(record.indexedSize)
                success
            }
            db.transaction {
                deleted.forEach { record ->
                    delete("songs", "id = ?", arrayOf(record.songId))
                }
            }
        }
        if (removedBytes > 0L) {
            managedAudioBytes.updateAndGet { current -> (current - removedBytes).coerceAtLeast(0L) }
        }
    }


    private fun localSizeLocked(db: SQLiteDatabase, songId: String): Long = db.rawQuery(
        "SELECT local_size_bytes FROM songs WHERE id = ?",
        arrayOf(songId)
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    private fun queryManagedAudioBytes(): Long = readableDb().rawQuery(
        "SELECT COALESCE(SUM(local_size_bytes), 0) FROM songs WHERE local_file_name IS NOT NULL",
        null
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getLong(0) else 0L
    }

    private fun reconcileAudioIndex() {
        if (!storageAvailable() || closed.get()) return
        writeLock.withLock {
            if (!storageAvailable() || closed.get()) return@withLock
            val records = mutableMapOf<String, LocalFileRecord>()
            readableDb().rawQuery(
                """
                SELECT id, local_file_name, local_size_bytes, size_bytes
                FROM songs WHERE local_file_name IS NOT NULL
                """.trimIndent(),
                null
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val record = LocalFileRecord(
                        songId = cursor.getString(0),
                        fileName = cursor.getString(1),
                        indexedSize = cursor.getLong(2),
                        expectedSize = cursor.getLong(3)
                    )
                    records[record.fileName] = record
                }
            }

            val invalid = mutableMapOf<String, Long>()
            val sizeCorrections = mutableListOf<Pair<String, Long>>()
            var actualBytes = 0L
            records.values.forEach { record ->
                val file = managedAudioFile(record.fileName)
                val actual = if (file?.isFile == true) file.length() else 0L
                val valid = actual > 0L && (record.expectedSize <= 0L || actual == record.expectedSize)
                if (!valid) {
                    invalid[record.songId] = record.indexedSize
                    if (actual > 0L && record.expectedSize > 0L) {
                        val partial = File(file!!.parentFile, "${file.name}.part")
                        when {
                            actual < record.expectedSize && (!partial.exists() || partial.length() < actual) -> {
                                if (partial.exists()) runCatching { partial.delete() }
                                if (!file.renameTo(partial)) runCatching { file.delete() }
                            }
                            else -> runCatching { file.delete() }
                        }
                    }
                } else {
                    actualBytes += actual
                    if (actual != record.indexedSize) sizeCorrections += record.songId to actual
                }
            }

            val db = writableDb()
            db.transaction {
                invalid.keys.chunked(SQLITE_BIND_CHUNK).forEach { chunk ->
                    val placeholders = chunk.joinToString(",") { "?" }
                    execSQL(
                        "UPDATE songs SET local_file_name = NULL, local_size_bytes = 0 " +
                            "WHERE id IN ($placeholders)",
                        chunk.toTypedArray()
                    )
                }
                sizeCorrections.forEach { (songId, actual) ->
                    val values = ContentValues().apply { put("local_size_bytes", actual) }
                    update("songs", values, "id = ?", arrayOf(songId))
                }
                if (invalid.isNotEmpty()) {
                    execSQL("UPDATE playlists SET offline_complete = 0 WHERE offline_requested = 1")
                }
            }

            val knownFiles = records.keys
            val now = System.currentTimeMillis()
            audioDirectory.listFiles()?.forEach { file ->
                if (!file.isFile || file.name in knownFiles) return@forEach
                val isPartial = file.name.endsWith(".part")
                val partialIsFresh = isPartial && now - file.lastModified() <= PARTIAL_RETENTION_MS
                // Fresh partials resume. Completed unindexed files are retained
                // because deterministic IDs can recover them after a DB rebuild.
                if (isPartial && !partialIsFresh) runCatching { file.delete() }
            }
            managedAudioBytes.set(actualBytes.coerceAtLeast(0L))
        }
    }

    private fun migrateLegacyJsonIfNeeded() {
        val legacy = File(appContext.filesDir, "tailtune_offline_library.json")
        if (!legacy.isFile || playlistCount() > 0) return

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
                    val file = managedAudioFile(fileName)
                    if (file != null && isCompleteAudioFile(song, file)) {
                        registerDownloadedSong(playlistId, song, file)
                    }
                }
            }
            markPlaylistComplete(playlistId)
        }
        legacy.renameTo(File(appContext.filesDir, "tailtune_offline_library.migrated.json"))
    }

    private fun scheduleReconcile() {
        if (!reconcileScheduled.compareAndSet(false, true)) return
        val accepted = scheduleMaintenance {
            try {
                reconcileAudioIndex()
            } finally {
                reconcileScheduled.set(false)
            }
        }
        if (!accepted) reconcileScheduled.set(false)
    }

    private fun scheduleMaintenance(block: () -> Unit): Boolean {
        if (closed.get()) return false
        return runCatching {
            maintenanceExecutor.execute(block)
            true
        }.getOrDefault(false)
    }

    private fun chooseStorageRoot(): File {
        val prefs = appContext.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_STORAGE_ROOT, null)?.takeIf(String::isNotBlank)?.let { remembered ->
            // Keep the same root when a microSD card is temporarily absent. Falling
            // back to internal storage would make valid indexed files look deleted.
            return File(remembered)
        }

        val allExternal = appContext.getExternalFilesDirs(Environment.DIRECTORY_MUSIC)
            .filterNotNull()
        val mounted = allExternal.filter(::isPathMounted)
        val existingDatabase = appContext.getDatabasePath(DATABASE_NAME).isFile
        val removable = allExternal.firstOrNull {
            val isRemovable = runCatching {
                Environment.isExternalStorageRemovable(it)
            }.getOrDefault(false)
            isRemovable && (isPathMounted(it) || existingDatabase)
        }
        val base = removable ?: mounted.firstOrNull() ?: appContext.filesDir
        val selected = File(base, "TailTuneOffline")
        prefs.edit().putString(KEY_STORAGE_ROOT, selected.absolutePath).apply()
        return selected
    }

    private fun storageAvailable(): Boolean =
        isPathMounted(storageRoot) &&
            (audioDirectory.isDirectory || audioDirectory.mkdirs()) &&
            audioDirectory.canWrite()

    private fun isPathMounted(file: File): Boolean {
        if (file.absolutePath.startsWith(appContext.filesDir.absolutePath)) return true
        return runCatching {
            Environment.getExternalStorageState(file) == Environment.MEDIA_MOUNTED
        }.getOrDefault(false)
    }

    private fun readableDb(): SQLiteDatabase {
        check(!closed.get()) { "Offline database is closed" }
        return helper.readableDatabase
    }

    private fun writableDb(): SQLiteDatabase {
        check(!closed.get()) { "Offline database is closed" }
        return helper.writableDatabase
    }

    /** Never lets a damaged/legacy database escape the managed audio directory. */
    private fun managedAudioFile(fileName: String): File? {
        if (fileName.isBlank() || fileName.length > MAX_MANAGED_FILE_NAME_LENGTH) return null
        if ('/' in fileName || '\\' in fileName || File(fileName).name != fileName) return null
        return File(audioDirectory, fileName)
    }

    private fun deterministicAudioFile(song: RemoteSong): File = File(
        audioDirectory,
        "${sha256(song.id)}.${song.preferredExtension()}"
    )

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

    private data class LocalFileRecord(
        val songId: String = "",
        val fileName: String,
        val indexedSize: Long,
        val expectedSize: Long = 0L
    )

    private class DatabaseHelper(
        context: Context,
        errorHandler: DatabaseErrorHandler
    ) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION,
        errorHandler
    ) {

        override fun onConfigure(db: android.database.sqlite.SQLiteDatabase) {
            super.onConfigure(db)

            // Foreign-key enforcement is supported directly by Android.
            // Do NOT install PRAGMA busy_timeout here. On this Samsung /
            // Android SQLite implementation it is treated as a query and
            // causes SQLiteConnectionPool initialization to fail.
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
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    details_updated_at INTEGER NOT NULL DEFAULT 0
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
                    local_size_bytes INTEGER NOT NULL DEFAULT 0,
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
            createIndexes(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                if (!hasColumn(db, "playlists", "details_updated_at")) {
                    db.execSQL(
                        "ALTER TABLE playlists ADD COLUMN details_updated_at INTEGER NOT NULL DEFAULT 0"
                    )
                }
                if (!hasColumn(db, "songs", "local_size_bytes")) {
                    db.execSQL(
                        "ALTER TABLE songs ADD COLUMN local_size_bytes INTEGER NOT NULL DEFAULT 0"
                    )
                }
            }
            createIndexes(db)
        }

        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // A future database should never crash an older audited build. Keep
            // audio files, recreate only metadata, and let Navidrome repopulate it.
            db.execSQL("DROP TABLE IF EXISTS playlist_songs")
            db.execSQL("DROP TABLE IF EXISTS songs")
            db.execSQL("DROP TABLE IF EXISTS playlists")
            onCreate(db)
        }

        private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean =
            db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIndex >= 0 && cursor.getString(nameIndex) == column) return@use true
                }
                false
            }

        private fun createIndexes(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_playlist_songs_song ON playlist_songs(song_id)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_playlists_offline ON playlists(offline_requested)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_playlists_remote ON playlists(remote_present)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_songs_local_file ON songs(local_file_name)"
            )
        }
    }

    private class CorruptionHandler(private val context: Context) : DatabaseErrorHandler {
        override fun onCorruption(dbObj: SQLiteDatabase) {
            val path = dbObj.path
            runCatching { dbObj.close() }
            if (path.isNullOrBlank() || path.equals(":memory:", ignoreCase = true)) return

            val timestamp = System.currentTimeMillis()
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val source = File("$path$suffix")
                if (!source.exists()) return@forEach
                val backup = File(context.filesDir, "tailtune-corrupt-$timestamp${suffix.ifBlank { ".db" }}")
                if (!source.renameTo(backup)) runCatching { source.delete() }
            }
        }
    }

    companion object {
        private const val DATABASE_NAME = "tailtune.db"
        private const val DATABASE_VERSION = 2
        private const val SQLITE_BIND_CHUNK = 500
        private const val MAX_MANAGED_FILE_NAME_LENGTH = 255
        private const val PLAYLIST_DETAIL_MAX_AGE_MS = 6L * 60L * 60L * 1000L
        private const val PARTIAL_RETENTION_MS = 7L * 24L * 60L * 60L * 1000L
        private const val STORAGE_PREFS = "tailtune_storage"
        private const val KEY_STORAGE_ROOT = "root_v1"

        fun hasExistingDatabase(context: Context): Boolean =
            context.getDatabasePath(DATABASE_NAME).isFile
    }
}
