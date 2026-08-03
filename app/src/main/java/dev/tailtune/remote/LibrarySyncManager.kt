package dev.tailtune.remote

import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronizes Navidrome into the local SQLite cache without blocking the web
 * server. The UI can always read cached playlists immediately.
 */
class LibrarySyncManager(
    private val store: OfflineStore,
    private val clientProvider: () -> SubsonicClient?,
    private val onChanged: () -> Unit
) {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-LibrarySync").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(false)

    @Volatile private var online = false
    @Volatile private var state = STATE_IDLE
    @Volatile private var completed = 0
    @Volatile private var total = 0
    @Volatile private var currentPlaylist: String? = null
    @Volatile private var lastSyncAt = 0L
    @Volatile private var error: String? = null

    fun start(force: Boolean = false) {
        if (!running.compareAndSet(false, true)) return
        state = STATE_SYNCING
        completed = 0
        total = 0
        currentPlaylist = null
        error = null
        notifyChanged()

        executor.submit {
            try {
                val client = clientProvider()
                    ?: throw IllegalStateException("Navidrome is not configured")

                val summaries = client.getPlaylists()
                online = true
                total = summaries.size
                store.updateRemoteSummaries(summaries)
                notifyChanged()

                var firstDetailError: String? = null
                summaries.forEach { summary ->
                    currentPlaylist = summary.name
                    val shouldFetch = force || !store.hasPlaylistSongs(summary.id)
                    if (shouldFetch) {
                        runCatching { client.getPlaylist(summary.id) }
                            .onSuccess(store::saveRemotePlaylist)
                            .onFailure { failure ->
                                if (firstDetailError == null) firstDetailError = rootCause(failure).message
                            }
                    }
                    completed += 1
                    notifyChanged()
                }

                online = true
                state = STATE_ONLINE
                currentPlaylist = null
                lastSyncAt = System.currentTimeMillis()
                error = firstDetailError
            } catch (failure: Throwable) {
                online = false
                state = STATE_OFFLINE
                currentPlaylist = null
                error = rootCause(failure).message ?: rootCause(failure).javaClass.simpleName
            } finally {
                running.set(false)
                notifyChanged()
            }
        }
    }

    /**
     * Returns cached data first. A network request is only made when the song
     * list is missing or the caller explicitly requests a fresh copy.
     */
    fun loadPlaylist(playlistId: String, preferRemote: Boolean = false): RemotePlaylist {
        val cached = store.getPlaylist(playlistId)
        if (!preferRemote && cached != null && cached.songs.isNotEmpty()) return cached

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
                error = rootCause(failure).message
                notifyChanged()
            }
            remote.getOrNull()?.let { return it }
        }

        if (cached != null && cached.songs.isNotEmpty()) return cached
        throw IllegalStateException(error ?: "Playlist is not cached and Navidrome is unavailable")
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

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun notifyChanged() {
        runCatching(onChanged)
    }

    private fun rootCause(error: Throwable): Throwable {
        var result = error
        while (result.cause != null && result.cause !== result) result = result.cause!!
        return result
    }

    companion object {
        private const val STATE_IDLE = "idle"
        private const val STATE_SYNCING = "syncing"
        private const val STATE_ONLINE = "online"
        private const val STATE_OFFLINE = "offline"
    }
}
