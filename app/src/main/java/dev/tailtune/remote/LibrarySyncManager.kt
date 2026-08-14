package dev.tailtune.remote

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synchronizes Navidrome without making cached reads wait for the network. */
class LibrarySyncManager(
    private val store: OfflineStore,
    private val clientProvider: () -> SubsonicClient?,
    private val onChanged: () -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-LibrarySync").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)
    private val pendingFullRefresh = AtomicBoolean(false)
    private val detailRefreshes = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var online = false
    @Volatile private var state = STATE_IDLE
    @Volatile private var completed = 0
    @Volatile private var total = 0
    @Volatile private var currentPlaylist: String? = null
    @Volatile private var lastSyncAt = 0L
    @Volatile private var error: String? = null

    /**
     * A normal refresh fetches one lightweight playlist-summary response. A
     * full refresh is available for repair/debugging but is never used during
     * ordinary app startup.
     */
    fun start(fullRefresh: Boolean = false) {
        if (!running.compareAndSet(false, true)) {
            if (fullRefresh) pendingFullRefresh.set(true)
            return
        }

        state = STATE_SYNCING
        completed = 0
        total = 0
        currentPlaylist = null
        error = null
        notifyChanged()

        executeSafely {
            try {
                val client = clientProvider()
                    ?: throw IllegalStateException("Navidrome is not configured")
                val summaries = client.getPlaylists()

                online = true
                total = summaries.size
                store.updateRemoteSummaries(
                    summaries = summaries,
                    pruneMissing = summaries.isNotEmpty()
                )
                notifyChanged()

                if (summaries.isEmpty() && store.playlistCount() > 0) {
                    error = "Navidrome returned no playlists; the existing cache was kept"
                }

                if (fullRefresh) {
                    refreshAllDetails(client, summaries)
                } else {
                    completed = total
                }

                state = STATE_ONLINE
                currentPlaylist = null
                lastSyncAt = System.currentTimeMillis()
            } catch (failure: Exception) {
                online = false
                state = STATE_OFFLINE
                currentPlaylist = null
                error = ErrorSanitizer.message(failure)
            } finally {
                running.set(false)
                notifyChanged()
                if (pendingFullRefresh.getAndSet(false)) start(fullRefresh = true)
            }
        }
    }

    /**
     * Returns cached details immediately. Stale cached details are refreshed in
     * the background so opening or playing a playlist never waits unnecessarily.
     */
    fun loadPlaylist(
        playlistId: String,
        preferRemote: Boolean = false
    ): RemotePlaylist {
        require(playlistId.isNotBlank()) { "Playlist ID is missing" }
        val cached = store.getPlaylist(playlistId)
        val needsRefresh = store.needsPlaylistRefresh(playlistId)
        val cacheHasUsableDetails = cached != null && (!needsRefresh || cached.songs.isNotEmpty())

        if (!preferRemote && cacheHasUsableDetails) {
            if (needsRefresh && clientProvider() != null) refreshPlaylistInBackground(playlistId)
            return cached!!
        }

        val client = clientProvider()
        if (client != null) {
            val remote = runCatching { client.getPlaylist(playlistId) }
            remote.onSuccess { playlist ->
                online = true
                error = null
                store.saveRemotePlaylist(playlist)
                notifyChanged()
            }.onFailure { failure ->
                online = false
                error = ErrorSanitizer.message(failure)
                notifyChanged()
            }
            remote.getOrNull()?.let { return it }
        }

        if (cached != null && (!needsRefresh || cached.songs.isNotEmpty())) return cached
        throw IllegalStateException(
            error ?: "Playlist is not cached and Navidrome is unavailable"
        )
    }

    fun statusJson(): JSONObject = JSONObject()
        .put("state", state)
        .put("syncing", running.get())
        .put("online", online)
        .put("completed", completed)
        .put("total", total)
        .put("currentPlaylist", currentPlaylist ?: JSONObject.NULL)
        .put("lastSyncAt", lastSyncAt)
        .put("error", error ?: JSONObject.NULL)

    fun isOnline(): Boolean = online

    /** Signals cancellation immediately. Never waits on an Android lifecycle thread. */
    fun shutdown() {
        pendingFullRefresh.set(false)
        detailRefreshes.clear()
        executor.shutdownNow()
    }

    fun awaitTermination(timeoutMs: Long): Boolean = runCatching {
        executor.awaitTermination(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
    }.getOrDefault(false)

    private fun refreshAllDetails(
        client: SubsonicClient,
        summaries: List<PlaylistSummary>
    ) {
        var firstDetailError: String? = null
        summaries.forEach { summary ->
            if (Thread.currentThread().isInterrupted) return
            currentPlaylist = summary.name
            runCatching { client.getPlaylist(summary.id) }
                .onSuccess(store::saveRemotePlaylist)
                .onFailure { failure ->
                    if (firstDetailError == null) {
                        firstDetailError = ErrorSanitizer.message(failure)
                    }
                }
            completed += 1
            if (completed % PROGRESS_BATCH == 0 || completed == total) notifyChanged()
        }
        if (firstDetailError != null) error = firstDetailError
    }

    private fun refreshPlaylistInBackground(playlistId: String) {
        if (!detailRefreshes.add(playlistId)) return
        executeSafely {
            try {
                val client = clientProvider() ?: return@executeSafely
                val playlist = client.getPlaylist(playlistId)
                store.saveRemotePlaylist(playlist)
                online = true
                error = null
            } catch (failure: Exception) {
                online = false
                error = ErrorSanitizer.message(failure)
            } finally {
                detailRefreshes.remove(playlistId)
                notifyChanged()
            }
        }
    }

    private fun executeSafely(block: () -> Unit) {
        try {
            executor.execute(block)
        } catch (_: RejectedExecutionException) {
            running.set(false)
            state = STATE_OFFLINE
            online = false
            currentPlaylist = null
            error = "The library synchronizer is shutting down"
            notifyChanged()
        }
    }

    private fun notifyChanged() {
        runCatching(onChanged)
    }

    companion object {
        private const val STATE_IDLE = "idle"
        private const val STATE_SYNCING = "syncing"
        private const val STATE_ONLINE = "online"
        private const val STATE_OFFLINE = "offline"
        private const val PROGRESS_BATCH = 5
    }
}
