package dev.tailtune.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.IBinder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns playback and media state. RemoteServerService owns the HTTP/WebSocket lifetime.
 *
 * ExoPlayer runs on a dedicated looper. Consequently, queue preparation and
 * player calls cannot stall Android's UI thread even for large playlists.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private lateinit var playerThread: HandlerThread
    private lateinit var playerHandler: Handler
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var offlineStore: OfflineStore
    private lateinit var downloadManager: OfflineDownloadManager
    private lateinit var librarySyncManager: LibrarySyncManager
    private lateinit var downloadWakeLock: PowerManager.WakeLock

    @Volatile private var webServer: TailTuneServer? = null
    private val destroyed = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val initializing = AtomicBoolean(false)
    private val componentExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-PlaybackInit").apply { isDaemon = true }
    }
    private val batteryPercent = AtomicInteger(-1)
    private val batteryCharging = AtomicBoolean(false)
    private val queueBroadcastNeeded = AtomicBoolean(true)
    private val queueRevision = AtomicInteger(0)
    private val playbackOngoing = AtomicBoolean(false)

    @Volatile private var client: SubsonicClient? = null
    @Volatile private var lastError: String? = null
    @Volatile private var serverError: String? = null
    @Volatile private var initializationError: String? = null

    private val localBinder = LocalBinder()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_BATTERY_CHANGED) updateBattery(intent)
        }
    }

    private val playbackBroadcast = Runnable {
        if (!destroyed.get()) webServer?.broadcastPlayback()
    }

    private val positionTicker = object : Runnable {
        override fun run() {
            if (destroyed.get() || !::playerHandler.isInitialized) return
            if (::player.isInitialized && player.isPlaying) requestPlaybackBroadcast(0L)
            playerHandler.postDelayed(this, POSITION_UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            // Media3 setup belongs in MediaSessionService.onCreate(). Keep it
            // lightweight: storage, Keystore and SQLite are initialized later
            // on TailTune-PlaybackInit so Android's lifecycle thread cannot ANR.
            createDownloadWakeLock()
            createPlayer()
            registerBatteryUpdates()
            playerHandler.post(positionTicker)
            beginBackgroundInitialization()
        } catch (failure: Exception) {
            failInitialization(failure)
        }
    }

    private fun beginBackgroundInitialization() {
        if (destroyed.get() || initialized.get()) return
        if (!initializing.compareAndSet(false, true)) return
        initializationError = null

        try {
            componentExecutor.execute {
                try {
                    if (destroyed.get()) return@execute

                    val store = OfflineStore(applicationContext)
                    if (destroyed.get()) {
                        runCatching { store.close() }
                        return@execute
                    }
                    offlineStore = store

                    reloadClientOnly()

                    downloadManager = OfflineDownloadManager(
                        store = store,
                        clientProvider = ::currentClient,
                        onDownloadActiveChanged = ::setDownloadWakeLockActive,
                        onChanged = { webServer?.broadcastDownloads() }
                    )

                    librarySyncManager = LibrarySyncManager(
                        store = store,
                        clientProvider = ::currentClientOrNull,
                        onChanged = { webServer?.broadcastLibrary() }
                    )

                    store.runStartupMaintenance {
                        webServer?.broadcastLibrary()
                        webServer?.broadcastDownloads()
                    }

                    if (currentClientOrNull() != null) {
                        librarySyncManager.start(fullRefresh = false)
                    }

                    if (destroyed.get()) return@execute
                    initializationError = null
                    initialized.set(true)
                    Log.i(TAG, "PlaybackService initialized")
                    webServer?.broadcastSnapshot()
                } catch (failure: Exception) {
                    failInitialization(failure)
                } finally {
                    initializing.set(false)
                }
            }
        } catch (_: RejectedExecutionException) {
            initializing.set(false)
            if (!destroyed.get()) {
                failInitialization(IllegalStateException("Playback initializer is unavailable"))
            }
        }
    }

    private fun failInitialization(failure: Throwable) {
        val message = "TailTune playback could not initialize: ${ErrorSanitizer.message(failure)}"
        initializationError = message
        lastError = message
        initialized.set(false)
        Log.e(TAG, "PlaybackService initialization failed", failure)
    }

    inner class LocalBinder : Binder() {
        fun service(): PlaybackService = this@PlaybackService
    }

    override fun onBind(intent: Intent?): IBinder? {
        return if (intent?.action == ACTION_LOCAL_BIND) {
            localBinder
        } else {
            super.onBind(intent)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keep MediaSessionService's media-button/start semantics intact.
        return super.onStartCommand(intent, flags, startId)
    }

    fun isReady(): Boolean = initialized.get() && initializationError == null && !destroyed.get()

    fun isInitializing(): Boolean = initializing.get() && !destroyed.get()

    fun initializationError(): String? = initializationError

    /** Called by RemoteServerService when it takes ownership of the web server. */
    fun attachRemoteServer(server: TailTuneServer?, error: String? = null) {
        webServer = server
        serverError = error
        server?.broadcastSnapshot()
    }

    /**
     * Reload credentials without putting Keystore/SharedPreferences on a lifecycle thread.
     * [fullRefresh] is consumed on the same serialized component executor, so a refresh
     * can never race ahead using stale credentials.
     */
    fun reloadConfiguration(fullRefresh: Boolean = false) {
        if (!isReady()) return
        executeComponentTask("configuration reload") {
            reloadClientOnly()
            lastError = null
            if (::librarySyncManager.isInitialized && currentClientOrNull() != null) {
                librarySyncManager.start(fullRefresh = fullRefresh)
            }
            webServer?.broadcastSnapshot()
        }
    }

    private fun executeComponentTask(name: String, block: () -> Unit) {
        if (destroyed.get()) return
        try {
            componentExecutor.execute {
                if (destroyed.get()) return@execute
                runCatching(block).onFailure { failure ->
                    lastError = "$name failed: ${ErrorSanitizer.message(failure)}"
                    Log.e(TAG, "$name failed", failure)
                    webServer?.broadcastSnapshot()
                }
            }
        } catch (_: RejectedExecutionException) {
            if (!destroyed.get()) lastError = "$name could not be scheduled"
        }
    }

    private fun reloadClientOnly() {
        client = runCatching {
            val settings = ServerSettings.load(this)
            if (settings.configured && settings.validationError() == null) {
                SubsonicClient(settings)
            } else {
                null
            }
        }.onFailure { failure ->
            lastError = ErrorSanitizer.message(failure)
        }.getOrNull()
    }

    fun currentClient(): SubsonicClient = client
        ?: throw ServiceUnavailableException(
            "Configure Navidrome in the TailTune Android app first"
        )

    fun currentClientOrNull(): SubsonicClient? = client

    fun getOfflineStore(): OfflineStore = offlineStore

    fun downloads(): OfflineDownloadManager = downloadManager

    fun librarySync(): LibrarySyncManager = librarySyncManager

    fun playPlaylist(playlist: RemotePlaylist, startIndex: Int = 0) {
        if (playlist.songs.isEmpty()) {
            throw IllegalStateException("This playlist has no playable songs")
        }

        // One SQLite query for the whole playlist, rather than one query/song.
        val localFiles = offlineStore.localFiles(playlist.songs.map(RemoteSong::id))
        val clientSnapshot = currentClientOrNull()
        // When Navidrome is known to be offline and at least part of the
        // playlist is cached, build a local-only queue instead of stalling on
        // every missing remote track. If nothing is cached, still allow one
        // remote attempt because startup sync may not have completed yet.
        val remoteClient = clientSnapshot?.takeIf {
            librarySyncManager.isOnline() || localFiles.isEmpty()
        }
        val playable = playlist.songs.mapIndexedNotNull { originalIndex, song ->
            song.toMediaItemOrNull(localFiles[song.id], remoteClient)?.let {
                originalIndex to it
            }
        }
        if (playable.isEmpty()) {
            throw ServiceUnavailableException(
                "No downloaded tracks are available and Navidrome is unreachable"
            )
        }

        val safeRequestedIndex = startIndex.coerceIn(0, playlist.songs.lastIndex)
        val playerStartIndex = playable.indexOfFirst { it.first >= safeRequestedIndex }
            .takeIf { it >= 0 }
            ?: 0

        onPlayerThread {
            player.setMediaItems(playable.map { it.second }, playerStartIndex, 0L)
            player.prepare()
            player.play()
            lastError = null
        }
        markQueueChanged()
        requestPlaybackBroadcast()
    }

    fun addSong(song: RemoteSong) {
        val local = offlineStore.localFiles(listOf(song.id))[song.id]
        val mediaItem = song.toMediaItemOrNull(local, currentClientOrNull())
            ?: throw ServiceUnavailableException(
                "This song is not downloaded and Navidrome is unavailable"
            )
        onPlayerThread {
            player.addMediaItem(mediaItem)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            lastError = null
        }
        markQueueChanged()
        requestPlaybackBroadcast()
    }

    fun control(action: String, body: JSONObject) {
        onPlayerThread {
            when (action) {
                "play" -> player.play()
                "pause" -> player.pause()
                "toggle" -> if (player.isPlaying) player.pause() else player.play()
                "next" -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                "previous" -> {
                    if (player.currentPosition > PREVIOUS_RESTART_THRESHOLD_MS) {
                        player.seekTo(0L)
                    } else if (player.hasPreviousMediaItem()) {
                        player.seekToPreviousMediaItem()
                    }
                }
                "seek" -> {
                    val requested = requiredLong(body, "positionMs").coerceAtLeast(0L)
                    val duration = player.duration.takeIf { it > 0L }
                    player.seekTo(duration?.let { requested.coerceAtMost(it) } ?: requested)
                }
                "jump" -> {
                    val index = requiredInt(body, "index")
                    require(index in 0 until player.mediaItemCount) {
                        "Queue index is out of range"
                    }
                    player.seekToDefaultPosition(index)
                    player.play()
                }
                else -> throw IllegalArgumentException("Unknown control action: $action")
            }
            lastError = null
        }
        requestPlaybackBroadcast()
    }

    fun queueAction(action: String, body: JSONObject) {
        onPlayerThread {
            when (action) {
                "remove" -> {
                    val index = requiredInt(body, "index")
                    require(index in 0 until player.mediaItemCount) {
                        "Queue index is out of range"
                    }
                    player.removeMediaItem(index)
                }
                "move" -> {
                    val from = requiredInt(body, "from")
                    val to = requiredInt(body, "to")
                    require(from in 0 until player.mediaItemCount) {
                        "Source queue index is out of range"
                    }
                    require(to in 0 until player.mediaItemCount) {
                        "Destination queue index is out of range"
                    }
                    if (from != to) player.moveMediaItem(from, to)
                }
                "clear" -> player.clearMediaItems()
                else -> throw IllegalArgumentException("Unknown queue action: $action")
            }
            lastError = null
        }
        markQueueChanged()
        requestPlaybackBroadcast()
    }

    fun batteryJson(): JSONObject = JSONObject()
        .put("percent", batteryPercent.get())
        .put("charging", batteryCharging.get())

    fun stateJson(includeQueue: Boolean = true): JSONObject = onPlayerThread {
        val current = player.currentMediaItem?.let { item ->
            item.toQueueJson(player.currentMediaItemIndex)
        } ?: JSONObject.NULL

        JSONObject()
            .put("playing", player.isPlaying)
            .put("playbackState", player.playbackState)
            .put("positionMs", player.currentPosition.coerceAtLeast(0L))
            .put("durationMs", player.duration.takeIf { it > 0L } ?: 0L)
            .put("bufferedPositionMs", player.bufferedPosition.coerceAtLeast(0L))
            .put("current", current)
            .put("queueRevision", queueRevision.get())
            .put("error", lastError ?: JSONObject.NULL)
            .put("battery", batteryJson())
            .apply {
                if (includeQueue) {
                    val queue = JSONArray()
                    for (index in 0 until player.mediaItemCount) {
                        queue.put(player.getMediaItemAt(index).toQueueJson(index))
                    }
                    put("queue", queue)
                }
            }
    }

    /**
     * Position-only events avoid rebuilding and transmitting a large queue every
     * second. Clear the queue-dirty bit only after a successful snapshot, and
     * only when no newer queue revision appeared while the JSON was being built.
     */
    fun playbackEventJson(): JSONObject {
        val revisionBefore = queueRevision.get()
        val includeQueue = queueBroadcastNeeded.get()
        val value = stateJson(includeQueue = includeQueue)
        if (includeQueue && queueRevision.get() == revisionBefore) {
            queueBroadcastNeeded.compareAndSet(true, false)
        }
        return value
    }

    fun playlistsJson(): JSONObject {
        val statuses = offlineStore.allStatuses().associateBy(OfflinePlaylistStatus::playlistId)
        val playlists = JSONArray()
        offlineStore.listPlaylistSummaries().forEach { summary ->
            val status = statuses[summary.id]
            playlists.put(
                summary.toJson()
                    .put("downloadedCount", status?.downloadedCount ?: 0)
                    .put("offlineTotal", status?.totalCount ?: summary.songCount)
                    .put("offlineComplete", status?.complete ?: false)
                    .put("offlineKnown", status != null)
            )
        }
        return JSONObject()
            .put("playlists", playlists)
            .put("sync", librarySyncManager.statusJson())
            .put("storage", offlineStore.storageJson())
    }

    fun bootstrapJson(): JSONObject = JSONObject()
        .put("version", BuildConfig.VERSION_NAME)
        .put("server", JSONObject()
            .put("running", webServer != null)
            .put("error", serverError ?: JSONObject.NULL)
        )
        .put("library", playlistsJson())
        .put("playback", stateJson())
        .put("downloads", downloadManager.statusJson())

    private fun createPlayer() {
        playerThread = HandlerThread(
            "TailTune-Player",
            Process.THREAD_PRIORITY_AUDIO
        ).apply {
            start()
        }

        playerHandler = Handler(playerThread.looper)

        val task = FutureTask {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build()

            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    MIN_BUFFER_MS,
                    MAX_BUFFER_MS,
                    BUFFER_FOR_PLAYBACK_MS,
                    BUFFER_AFTER_REBUFFER_MS
                )
                .build()

            val createdPlayer = ExoPlayer.Builder(this@PlaybackService)
                .setLooper(playerThread.looper)
                .setLoadControl(loadControl)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build()
                .apply {
                    // IMPORTANT:
                    // Everything in this block executes on playerThread.
                    setAudioAttributes(audioAttributes, true)
                    setHandleAudioBecomingNoisy(true)

                    addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            lastError = ErrorSanitizer.message(
                                error.message ?: error.errorCodeName
                            )

                            requestPlaybackBroadcast(0L)
                        }

                        override fun onEvents(
                            player: Player,
                            events: Player.Events
                        ) {
                            playbackOngoing.set(
                                player.isPlaying || player.playWhenReady
                            )

                            if (
                                events.contains(
                                    Player.EVENT_TIMELINE_CHANGED
                                )
                            ) {
                                markQueueChanged()
                            }

                            if (
                                events.containsAny(
                                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                                    Player.EVENT_MEDIA_ITEM_TRANSITION,
                                    Player.EVENT_TIMELINE_CHANGED,
                                    Player.EVENT_IS_PLAYING_CHANGED,
                                    Player.EVENT_POSITION_DISCONTINUITY
                                )
                            ) {
                                requestPlaybackBroadcast()
                            }
                        }
                    })
                }

            val createdSession = MediaSession.Builder(
                this@PlaybackService,
                createdPlayer
            ).build()

            createdPlayer to createdSession
        }

        if (!playerHandler.post(task)) {
            playerThread.quitSafely()
            throw IllegalStateException(
                "Unable to start TailTune player thread"
            )
        }

        try {
            val (createdPlayer, createdSession) =
                task.get(
                    PLAYER_COMMAND_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )

            player = createdPlayer
            mediaSession = createdSession

        } catch (error: TimeoutException) {
            task.cancel(false)
            playerThread.quitSafely()

            throw IllegalStateException(
                "Timed out while creating the audio player",
                error
            )

        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel(false)
            playerThread.quitSafely()

            throw IllegalStateException(
                "Audio player initialization was interrupted",
                error
            )

        } catch (error: ExecutionException) {
            task.cancel(false)
            playerThread.quitSafely()

            throw (
                    error.cause
                        ?: IllegalStateException(
                            "Audio player initialization failed",
                            error
                        )
                    )
        }
    }

    private fun RemoteSong.toMediaItemOrNull(
        localFile: File?,
        client: SubsonicClient?
    ): MediaItem? {
        val uri: Uri = when {
            localFile != null -> Uri.fromFile(localFile)
            client != null -> Uri.parse(client.streamUrl(id))
            else -> return null
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMimeType(contentType)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .build()
            )
            .build()
    }

    private fun MediaItem.toQueueJson(index: Int): JSONObject = JSONObject()
        .put("index", index)
        .put("id", mediaId)
        .put("title", mediaMetadata.title?.toString().orEmpty())
        .put("artist", mediaMetadata.artist?.toString().orEmpty())
        .put("album", mediaMetadata.albumTitle?.toString().orEmpty())
        .put("offline", localConfiguration?.uri?.scheme == "file")

    private fun requiredInt(body: JSONObject, key: String): Int {
        val value = requiredLong(body, key)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            "$key is out of range"
        }
        return value.toInt()
    }

    private fun requiredLong(body: JSONObject, key: String): Long {
        if (!body.has(key) || body.isNull(key)) {
            throw IllegalArgumentException("Missing field: $key")
        }
        val raw = body.opt(key)
        return when (raw) {
            is Byte -> raw.toLong()
            is Short -> raw.toLong()
            is Int -> raw.toLong()
            is Long -> raw
            else -> throw IllegalArgumentException("$key must be an integer")
        }
    }

    private fun markQueueChanged() {
        queueRevision.incrementAndGet()
        queueBroadcastNeeded.set(true)
    }

    private fun requestPlaybackBroadcast(delayMs: Long = PLAYBACK_EVENT_DEBOUNCE_MS) {
        if (!::playerHandler.isInitialized || destroyed.get()) return
        playerHandler.removeCallbacks(playbackBroadcast)
        playerHandler.postDelayed(playbackBroadcast, delayMs)
    }

    private fun <T> onPlayerThread(block: () -> T): T {
        check(!destroyed.get()) { "Playback service is shutting down" }
        if (Looper.myLooper() == player.applicationLooper) return block()
        val task = FutureTask<T> { block() }
        if (!playerHandler.post(task)) {
            throw IllegalStateException("Player thread is unavailable")
        }
        return try {
            task.get(PLAYER_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            task.cancel(false)
            throw ServiceUnavailableException("The audio player did not respond in time")
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel(false)
            throw ServiceUnavailableException("The audio command was interrupted")
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        }
    }

    private fun createDownloadWakeLock() {
        downloadWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TailTune:OfflineDownloads"
        ).apply { setReferenceCounted(false) }
    }

    private fun setDownloadWakeLockActive(active: Boolean) {
        runCatching {
            if (active && !downloadWakeLock.isHeld) {
                downloadWakeLock.acquire()
            } else if (!active && downloadWakeLock.isHeld) {
                downloadWakeLock.release()
            }
        }
    }

    private fun registerBatteryUpdates() {
        runCatching {
            ContextCompat.registerReceiver(
                this,
                batteryReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }.onSuccess { sticky ->
            sticky?.let(::updateBattery)
        }.onFailure { failure ->
            lastError = "Battery status unavailable: ${ErrorSanitizer.message(failure)}"
        }
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        batteryPercent.set(
            if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else -1
        )
        batteryCharging.set(
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        )
        requestPlaybackBroadcast()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        if (::mediaSession.isInitialized && !destroyed.get()) mediaSession else null

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Do not delegate to MediaSessionService's default task-removal path.
        // This player intentionally uses a non-main application looper, while
        // Media3's pauseAllPlayersAndStopSelf() helper requires a main-looper
        // player. The always-on RemoteServerService owns the headless lifetime
        // and keeps this service bound while the user has the remote enabled.
        // If neither the remote nor playback needs us, a plain stopSelf() is
        // enough and avoids a helper/looper mismatch crash.
        if (!RemoteServicePreferences.isEnabled(this) && !playbackOngoing.get()) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (!destroyed.compareAndSet(false, true)) return
        initialized.set(false)

        componentExecutor.shutdownNow()
        initializing.set(false)

        if (::playerHandler.isInitialized) {
            playerHandler.removeCallbacksAndMessages(null)
        }
        runCatching { unregisterReceiver(batteryReceiver) }

        webServer = null

        if (::librarySyncManager.isInitialized) librarySyncManager.shutdown()
        if (::downloadManager.isInitialized) downloadManager.shutdown()
        if (::offlineStore.isInitialized) offlineStore.shutdownMaintenance()

        if (::downloadWakeLock.isInitialized && downloadWakeLock.isHeld) {
            runCatching { downloadWakeLock.release() }
        }

        // MediaSession is thread-safe and must be released while the service is
        // being destroyed so connected controllers are notified promptly.
        if (::mediaSession.isInitialized) runCatching { mediaSession.release() }

        // ExoPlayer must be released on its application looper, but Android's
        // lifecycle thread must never wait for that release (waiting here can
        // itself create an ANR on a slow/vendor device).
        if (::player.isInitialized && ::playerHandler.isInitialized) {
            val posted = playerHandler.post {
                runCatching { player.release() }
                if (::playerThread.isInitialized) playerThread.quitSafely()
            }
            if (!posted && ::playerThread.isInitialized) playerThread.quitSafely()
        } else if (::playerThread.isInitialized) {
            playerThread.quitSafely()
        }

        // Socket shutdown, executor joins and SQLite close can involve vendor
        // I/O. Complete them on a daemon cleanup thread, never on main.
        Thread({
            runCatching { componentExecutor.awaitTermination(CLEANUP_WAIT_MS, TimeUnit.MILLISECONDS) }
            if (::librarySyncManager.isInitialized) {
                librarySyncManager.awaitTermination(CLEANUP_WAIT_MS)
            }
            if (::downloadManager.isInitialized) {
                downloadManager.awaitTermination(CLEANUP_WAIT_MS)
            }
            if (::offlineStore.isInitialized) runCatching { offlineStore.close() }
        }, "TailTune-ServiceCleanup").apply {
            isDaemon = true
            start()
        }

        super.onDestroy()
    }

    companion object {
        const val ACTION_LOCAL_BIND = "dev.tailtune.remote.action.LOCAL_BIND"
        private const val TAG = "TailTune-Playback"
        private const val PLAYER_COMMAND_TIMEOUT_SECONDS = 8L
        private const val CLEANUP_WAIT_MS = 2_000L
        private const val POSITION_UPDATE_INTERVAL_MS = 1_000L
        private const val PLAYBACK_EVENT_DEBOUNCE_MS = 100L
        private const val PREVIOUS_RESTART_THRESHOLD_MS = 5_000L
        private const val MIN_BUFFER_MS = 5_000
        private const val MAX_BUFFER_MS = 30_000
        private const val BUFFER_FOR_PLAYBACK_MS = 750
        private const val BUFFER_AFTER_REBUFFER_MS = 2_000
    }
}

/** Maps to HTTP 503 without exposing implementation details to the web UI. */
class ServiceUnavailableException(message: String) : IllegalStateException(message)
