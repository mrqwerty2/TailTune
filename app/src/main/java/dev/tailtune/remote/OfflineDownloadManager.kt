package dev.tailtune.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

class OfflineDownloadManager(
    private val store: OfflineStore,
    private val clientProvider: () -> SubsonicClient,
    private val onDownloadActiveChanged: (Boolean) -> Unit = {},
    private val onChanged: () -> Unit = {}
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-Downloads").apply { isDaemon = true }
    }
    private val jobs = ConcurrentHashMap<String, DownloadJob>()
    private val activeDownloads = AtomicInteger(0)

    fun start(playlist: RemotePlaylist) {
        require(playlist.summary.id.isNotBlank()) { "Playlist ID is missing" }
        require(playlist.songs.isNotEmpty()) { "This playlist is empty" }

        val id = playlist.summary.id
        if (store.getStatus(id)?.complete == true) return

        val job = DownloadJob(
            playlistId = id,
            name = playlist.summary.name,
            total = playlist.songs.size,
            estimatedTotalBytes = safeByteSum(playlist.songs.map(RemoteSong::sizeBytes))
        )
        val existing = jobs.putIfAbsent(id, job)
        if (existing != null) {
            if (existing.state == STATE_DOWNLOADING || existing.state == STATE_QUEUED) return
            if (!jobs.replace(id, existing, job)) return
        }
        notifyChanged()
        if (!execute { runDownload(job, playlist) }) {
            job.state = STATE_FAILED
            job.error = "The download service is shutting down"
            notifyChanged()
        }
    }

    fun cancelAndRemove(playlistId: String) {
        require(playlistId.isNotBlank()) { "Playlist ID is missing" }
        jobs[playlistId]?.cancelled = true
        execute {
            store.removePlaylist(playlistId)
            jobs.remove(playlistId)
            notifyChanged()
        }
    }

    fun statusJson(): JSONObject {
        val byId = linkedMapOf<String, JSONObject>()
        store.allStatuses().forEach { status ->
            byId[status.playlistId] = status.toJson()
                .put("state", if (status.complete) STATE_COMPLETE else STATE_PAUSED)
                .put("currentSong", JSONObject.NULL)
                .put("error", JSONObject.NULL)
                .put("currentBytes", 0L)
                .put("currentTotalBytes", 0L)
        }
        jobs.values.sortedBy { it.name.lowercase() }.forEach { job ->
            byId[job.playlistId] = job.toJson()
        }
        return JSONObject()
            .put("downloads", JSONArray(byId.values.toList()))
            .put("storage", store.storageJson())
    }

    /** Signals cancellation immediately. Never waits on an Android lifecycle thread. */
    fun shutdown() {
        jobs.values.forEach { it.cancelled = true }
        executor.shutdownNow()
    }

    fun awaitTermination(timeoutMs: Long): Boolean = runCatching {
        executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }.getOrDefault(false)

    private fun runDownload(job: DownloadJob, playlist: RemotePlaylist) {
        job.state = STATE_DOWNLOADING
        if (activeDownloads.incrementAndGet() == 1) {
            runCatching { onDownloadActiveChanged(true) }
        }
        notifyChanged()

        try {
            store.beginPlaylist(playlist)
            val client = clientProvider()
            val existingFiles = store.localFiles(playlist.songs.map(RemoteSong::id))
            job.completed = 0

            val missingSongs = playlist.songs.filterNot { it.id in existingFiles }
            val knownRequiredBytes = safeByteSum(missingSongs.map(RemoteSong::sizeBytes))
            val allSizesKnown = missingSongs.all { it.sizeBytes > 0L }
            val storage = store.refreshStorageStats()
            if (!storage.optBoolean("available", true)) {
                throw IOException("Offline storage is not mounted or writable")
            }
            val usableBytes = storage.optLong("usableBytes", 0L)
            if (usableBytes < MINIMUM_FREE_BYTES) {
                throw IOException("Less than 100 MB of free storage remains")
            }
            if (allSizesKnown && knownRequiredBytes > 0L &&
                knownRequiredBytes > usableBytes - MINIMUM_FREE_BYTES
            ) {
                throw IOException("Not enough free space for this playlist")
            }

            for (song in playlist.songs) {
                ensureNotCancelled(job)
                val existingFile = existingFiles[song.id]
                if (existingFile != null) {
                    store.registerDownloadedSong(playlist.summary.id, song, existingFile)
                    job.completed = (job.completed + 1).coerceAtMost(job.total)
                    notifyChangedThrottled(job)
                    continue
                }

                job.currentSong = song.title
                job.currentBytes = 0L
                job.currentTotalBytes = song.sizeBytes.coerceAtLeast(0L)
                notifyChanged()

                val finalFile = store.targetFile(song)
                if (finalFile.exists() && !store.isCompleteAudioFile(song, finalFile)) {
                    // A previous process death may have left a truncated finalized
                    // file. Move it back to the resumable .part path when possible.
                    store.demoteIncompleteFinalFile(song, finalFile)
                }
                if (store.isCompleteAudioFile(song, finalFile)) {
                    store.registerDownloadedSong(playlist.summary.id, song, finalFile)
                    job.completed = (job.completed + 1).coerceAtMost(job.total)
                    continue
                }

                val partial = File(finalFile.parentFile, "${finalFile.name}.part")
                client.downloadSong(
                    song = song,
                    target = partial,
                    isCancelled = { job.cancelled || Thread.currentThread().isInterrupted },
                    onProgress = { downloaded, total ->
                        job.currentBytes = downloaded
                        job.currentTotalBytes = total
                        notifyChangedThrottled(job)
                    }
                )
                ensureNotCancelled(job)
                if (!partial.isFile || partial.length() <= 0L) {
                    throw IOException("Downloaded file is empty")
                }
                if (song.sizeBytes > 0L && partial.length() != song.sizeBytes) {
                    throw IOException(
                        "Downloaded file size is ${partial.length()} bytes; expected ${song.sizeBytes}"
                    )
                }

                promotePartial(partial, finalFile)
                store.registerDownloadedSong(playlist.summary.id, song, finalFile)
                job.completed = (job.completed + 1).coerceAtMost(job.total)
                notifyChanged()
            }

            store.markPlaylistComplete(playlist.summary.id)
            job.state = STATE_COMPLETE
            job.currentSong = null
            job.currentBytes = 0L
            job.currentTotalBytes = 0L
            job.error = null
        } catch (_: CancellationException) {
            job.state = STATE_PAUSED
            job.currentSong = null
            job.error = null
        } catch (error: Exception) {
            job.state = STATE_FAILED
            val root = ErrorSanitizer.rootCause(error)
            job.error = ErrorSanitizer.message(root)
        } finally {
            val remaining = activeDownloads.updateAndGet { value ->
                (value - 1).coerceAtLeast(0)
            }
            if (remaining == 0) runCatching { onDownloadActiveChanged(false) }
            notifyChanged()
        }
    }

    private fun promotePartial(partial: File, finalFile: File) {
        finalFile.parentFile?.mkdirs()
        try {
            Files.move(
                partial.toPath(),
                finalFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                partial.toPath(),
                finalFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (failure: IOException) {
            // Some vendor filesystems falsely advertise atomic moves. Retry a
            // normal replacement before surfacing the original error.
            runCatching {
                Files.move(
                    partial.toPath(),
                    finalFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }.getOrElse { throw failure }
        }
        if (!finalFile.isFile || finalFile.length() <= 0L) {
            throw IOException("Could not finalize the downloaded file")
        }
    }

    private fun ensureNotCancelled(job: DownloadJob) {
        if (job.cancelled || Thread.currentThread().isInterrupted) {
            throw CancellationException("Download cancelled")
        }
    }

    private fun notifyChangedThrottled(job: DownloadJob) {
        val now = System.currentTimeMillis()
        if (now - job.lastNotificationAt >= PROGRESS_EVENT_INTERVAL_MS) {
            job.lastNotificationAt = now
            notifyChanged()
        }
    }

    private fun execute(block: () -> Unit): Boolean = try {
        executor.execute(block)
        true
    } catch (_: RejectedExecutionException) {
        // Service is shutting down; no new file operation should start.
        false
    }

    private fun notifyChanged() {
        runCatching(onChanged)
    }


    private fun safeByteSum(values: Iterable<Long>): Long {
        var total = 0L
        for (value in values) {
            val positive = value.coerceAtLeast(0L)
            if (Long.MAX_VALUE - total < positive) return Long.MAX_VALUE
            total += positive
        }
        return total
    }

    private class DownloadJob(
        val playlistId: String,
        val name: String,
        val total: Int,
        val estimatedTotalBytes: Long
    ) {
        @Volatile var state: String = STATE_QUEUED
        @Volatile var completed: Int = 0
        @Volatile var currentSong: String? = null
        @Volatile var error: String? = null
        @Volatile var currentBytes: Long = 0L
        @Volatile var currentTotalBytes: Long = 0L
        @Volatile var cancelled: Boolean = false
        @Volatile var lastNotificationAt: Long = 0L

        fun toJson(): JSONObject = JSONObject()
            .put("playlistId", playlistId)
            .put("name", name)
            .put("state", state)
            .put("downloadedCount", completed.coerceAtMost(total))
            .put("totalCount", total)
            .put("complete", state == STATE_COMPLETE)
            .put("currentSong", currentSong ?: JSONObject.NULL)
            .put("error", error ?: JSONObject.NULL)
            .put("currentBytes", currentBytes)
            .put("currentTotalBytes", currentTotalBytes)
            .put("estimatedTotalBytes", estimatedTotalBytes)
    }

    companion object {
        private const val STATE_QUEUED = "queued"
        private const val STATE_DOWNLOADING = "downloading"
        private const val STATE_COMPLETE = "complete"
        private const val STATE_PAUSED = "paused"
        private const val STATE_FAILED = "failed"
        private const val MINIMUM_FREE_BYTES = 100L * 1024L * 1024L
        private const val PROGRESS_EVENT_INTERVAL_MS = 750L
    }
}
