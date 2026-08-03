package dev.tailtune.remote

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

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

    fun start(playlist: RemotePlaylist) {
        val id = playlist.summary.id
        val active = jobs[id]
        if (active?.state == STATE_DOWNLOADING || active?.state == STATE_QUEUED) return
        if (store.getStatus(id)?.complete == true) return

        val job = DownloadJob(
            playlistId = id,
            name = playlist.summary.name,
            total = playlist.songs.size,
            estimatedTotalBytes = playlist.songs.sumOf { it.sizeBytes.coerceAtLeast(0L) }
        )
        jobs[id] = job
        notifyChanged()
        executor.submit { runDownload(job, playlist) }
    }

    fun cancelAndRemove(playlistId: String) {
        jobs[playlistId]?.cancelled = true
        store.removePlaylist(playlistId)
        jobs.remove(playlistId)
        notifyChanged()
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
        jobs.values.forEach { job -> byId[job.playlistId] = job.toJson() }

        return JSONObject()
            .put("downloads", JSONArray(byId.values.toList()))
            .put("storage", store.storageJson())
    }

    fun shutdown() {
        jobs.values.forEach { it.cancelled = true }
        executor.shutdownNow()
    }

    private fun runDownload(job: DownloadJob, playlist: RemotePlaylist) {
        job.state = STATE_DOWNLOADING
        onDownloadActiveChanged(true)
        notifyChanged()

        try {
            if (playlist.songs.isEmpty()) throw IllegalStateException("This playlist is empty")
            store.beginPlaylist(playlist)
            val client = clientProvider()
            job.completed = 0

            val unknownSizes = playlist.songs.any { it.sizeBytes <= 0L }
            val required = playlist.songs
                .filterNot { store.isSongAvailable(it.id) }
                .sumOf { it.sizeBytes.coerceAtLeast(0L) }
            if (!unknownSizes && required > 0L &&
                store.storageJson().optLong("usableBytes") < required + MINIMUM_FREE_BYTES
            ) {
                throw IllegalStateException("Not enough free space for this playlist")
            }

            for (song in playlist.songs) {
                if (job.cancelled) throw CancellationException("Download cancelled")
                val existingFile = store.localFile(song.id)
                if (existingFile != null) {
                    store.registerDownloadedSong(playlist.summary.id, song, existingFile)
                    job.completed += 1
                    notifyChanged()
                    continue
                }

                job.currentSong = song.title
                job.currentBytes = 0L
                job.currentTotalBytes = song.sizeBytes
                notifyChanged()

                val finalFile = store.targetFile(song)
                val partial = File(finalFile.parentFile, "${finalFile.name}.part")
                partial.delete()

                try {
                    client.downloadSong(
                        song = song,
                        target = partial,
                        isCancelled = { job.cancelled },
                        onProgress = { downloaded, total ->
                            job.currentBytes = downloaded
                            job.currentTotalBytes = total
                            notifyChangedThrottled(job)
                        }
                    )
                    if (job.cancelled) throw CancellationException("Download cancelled")
                    if (partial.length() <= 0L) throw IllegalStateException("Downloaded file is empty")

                    if (!partial.renameTo(finalFile)) {
                        partial.copyTo(finalFile, overwrite = true)
                        partial.delete()
                    }
                    store.registerDownloadedSong(playlist.summary.id, song, finalFile)
                    job.completed += 1
                    notifyChanged()
                } catch (error: Throwable) {
                    partial.delete()
                    throw error
                }
            }

            store.markPlaylistComplete(playlist.summary.id)
            job.state = STATE_COMPLETE
            job.currentSong = null
            job.error = null
        } catch (cancelled: CancellationException) {
            job.state = STATE_PAUSED
            job.currentSong = null
            job.error = null
        } catch (error: Throwable) {
            job.state = STATE_FAILED
            val root = rootCause(error)
            job.error = root.message ?: root.javaClass.simpleName
        } finally {
            onDownloadActiveChanged(false)
            notifyChanged()
        }
    }

    private fun notifyChangedThrottled(job: DownloadJob) {
        val now = System.currentTimeMillis()
        if (now - job.lastNotificationAt >= PROGRESS_EVENT_INTERVAL_MS) {
            job.lastNotificationAt = now
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        runCatching(onChanged)
    }

    private fun rootCause(error: Throwable): Throwable {
        var result = error
        while (result.cause != null && result.cause !== result) result = result.cause!!
        return result
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
        private const val PROGRESS_EVENT_INTERVAL_MS = 500L
    }
}
