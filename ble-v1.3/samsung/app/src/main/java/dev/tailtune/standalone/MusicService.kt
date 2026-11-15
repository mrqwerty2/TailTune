package dev.tailtune.standalone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.tailtune.remote.ble.CallbackTailTuneBleApi
import dev.tailtune.remote.ble.TailTuneGattServer
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger

/** Downloads music over Wi-Fi/mobile data; sends audio via normal Android output (including A2DP).
 *  BLE is *only* the iPhone's low-bandwidth playback control and metadata channel.
 */
class MusicService : Service() {
    private lateinit var cache: NavidromeCache
    private lateinit var secrets: NavidromeSecrets
    private lateinit var library: MusicLibrary
    private lateinit var engine: PlaybackEngine
    private var gatt: TailTuneGattServer? = null
    private val scanner = Executors.newSingleThreadExecutor()
    private val telemetry = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newFixedThreadPool(2)
    private var initialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val batchGeneration = AtomicLong(0)
    private val activeTransfers = AtomicInteger(0)
    private fun beginTransfer() { activeTransfers.incrementAndGet() }
    private fun finishTransfer() { if (activeTransfers.decrementAndGet() <= 0) transferIdle() }
    private val batteryTick = object : Runnable {
        override fun run() {
            if (initialized) telemetry.execute {
                runCatching { gatt?.broadcastState(engine.snapshot()) }
            }
            mainHandler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        secrets = NavidromeSecrets(this)
        cache = NavidromeCache(this)
        library = MusicLibrary(this, cache)
        engine = PlaybackEngine(this, library, changed = {
            runCatching { gatt?.broadcastState(engine.snapshot()) }
        }, fetchRemote = { song, done -> fetchRemote(song, done) })
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "TailTune player", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(73, notification())
        mainHandler.postDelayed(batteryTick, 30_000)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("TailTune · Samsung music")
            .setContentText("Navidrome cache and BLE remote ready")
            .setContentIntent(open).setOngoing(true).build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (!initialized) initBle()
        when (intent?.action) {
            ACTION_SYNC -> syncNavidrome()
            ACTION_DOWNLOAD -> downloadBatch(intent.getIntExtra(EXTRA_COUNT, 25).coerceIn(1, 100), null)
            ACTION_DOWNLOAD_PLAYLIST -> downloadBatch(10000, intent.getStringExtra(EXTRA_ID))
            ACTION_DOWNLOAD_TRACK -> intent.getStringExtra(EXTRA_ID)?.let { downloadTrack(it) }
            ACTION_DELETE_TRACK -> intent.getStringExtra(EXTRA_ID)?.let { deleteTrack(it) }
            ACTION_DELETE_ALL -> deleteAll()
            ACTION_CANCEL_DOWNLOAD -> { batchGeneration.incrementAndGet(); updateStatus("Batch downloads will stop after the current track") }
        }
        return START_STICKY
    }

    private fun updateStatus(message: String) {
        getSharedPreferences("tailtune_status", MODE_PRIVATE).edit()
            .putString("status", message.take(220)).apply()
        Log.i(TAG, message)
    }

    private fun initBle() {
        initialized = true
        val api = CallbackTailTuneBleApi(
            snapshotBlock = { engine.snapshot() },
            queueBlock = { offset, limit -> engine.queue(offset, limit) },
            playlistsBlock = { offset, limit -> library.playlists(offset, limit) },
            playlistTracksBlock = { id, offset, limit -> library.tracks(id, offset, limit) },
            searchBlock = { q, offset, limit -> library.search(q, offset, limit) },
            controlBlock = { action, value -> engine.control(action, value) },
            playPlaylistBlock = { id, index -> engine.playPlaylist(id, index) },
            playTrackBlock = { id -> engine.playTrack(id) },
            queueAddBlock = { id -> engine.addToQueue(id) },
        )
        gatt = TailTuneGattServer(applicationContext, api).also { ble ->
            when (val result = ble.start()) {
                TailTuneGattServer.StartResult.Started -> updateStatus("BLE server started. Check advertising in Logcat.")
                is TailTuneGattServer.StartResult.Unavailable -> updateStatus("BLE unavailable: ${result.reason}")
            }
        }
    }

    private fun syncNavidrome() {
        scanner.execute {
            try {
                val config = secrets.load() ?: throw IllegalStateException("Save Navidrome credentials first")
                val client = NavidromeClient(config)
                updateStatus("Connecting to Navidrome...")
                client.ping()
                engine.navidromeOnline = true
                val albums = client.fetchCatalog { updateStatus("Indexed $it Navidrome songs...") }
                updateStatus("Reading real Navidrome playlists...")
                val fetched = client.fetchPlaylists()
                val combined = LinkedHashMap<String, RemoteSong>()
                fetched.songs.forEach { combined[it.id] = it }
                albums.forEach { combined.putIfAbsent(it.id, it) }
                val remote = combined.values.take(10000)
                cache.saveCatalog(remote, fetched.playlists)
                library.setRemote(remote, fetched.playlists)
                gatt?.broadcastLibraryChanged()
                updateStatus("Synced ${remote.size} Navidrome songs and ${fetched.playlists.size} playlists")
            } catch (e: Exception) {
                engine.navidromeOnline = false
                updateStatus("Navidrome sync failed: ${safeError(e)}. Previously downloaded songs remain available.")
            }
        }
    }

    private fun fetchRemote(song: RemoteSong, callback: (Result<File>) -> Unit) {
        try {
            downloader.execute {
                beginTransfer()
                val result = runCatching {
                    val config = secrets.load() ?: throw IllegalStateException("Navidrome login not saved")
                    cache.download(NavidromeClient(config), song) { t ->
                        transfer(song.title, t)
                    }
                }
                if (result.isSuccess) gatt?.broadcastLibraryChanged()
                finishTransfer()
                callback(result)
            }
        } catch (e: RejectedExecutionException) {
            callback(Result.failure(IllegalStateException("Player is stopping")))
        }
    }

    private fun transfer(title: String, transfer: NavidromeCache.Transfer) {
        getSharedPreferences("tailtune_status", MODE_PRIVATE).edit()
            .putString("downloadTitle", title.take(80))
            .putLong("downloadBytes", transfer.done)
            .putLong("downloadTotal", transfer.total)
            .putFloat("downloadSpeed", transfer.bytesPerSecond.toFloat())
            .putBoolean("downloadBusy", true)
            .apply()
    }

    private fun transferIdle() {
        getSharedPreferences("tailtune_status", MODE_PRIVATE).edit()
            .putBoolean("downloadBusy", false).putFloat("downloadSpeed", 0f).apply()
    }

    /** Only Navidrome tracks: optionally download a particular server playlist in its original order. */
    private fun downloadBatch(count: Int, playlistId: String?) {
        val selection = if (playlistId.isNullOrBlank()) library.songs
            else library.playlist(playlistId)
        val uncached = selection.filter { !it.downloaded }.distinctBy { it.id }.take(count)
        if (uncached.isEmpty()) {
            updateStatus("Nothing to download. Sync Navidrome or choose another playlist.")
            return
        }
        val generation = batchGeneration.incrementAndGet()
        downloader.execute {
            beginTransfer()
            try {
                val config = secrets.load() ?: throw IllegalStateException("Save Navidrome login first")
                val client = NavidromeClient(config)
                var succeeded = 0
                for ((position, track) in uncached.withIndex()) {
                    if (generation != batchGeneration.get() || Thread.currentThread().isInterrupted) break
                    updateStatus("Downloading ${position + 1}/${uncached.size}: ${track.title}")
                    try {
                        cache.download(client, track.remote) { t -> transfer(track.title, t) }
                        succeeded++
                        gatt?.broadcastLibraryChanged()
                    } catch (e: Exception) {
                        updateStatus("Download stopped: ${safeError(e)}")
                        return@execute
                    }
                }
                updateStatus("Downloaded $succeeded/${uncached.size} tracks. Cache: ${cache.usage().count} songs.")
            } catch (e: Exception) {
                updateStatus("Download failed: ${safeError(e)}")
            } finally { finishTransfer() }
        }
    }

    private fun downloadTrack(id: String) {
        val song = library.find(id) ?: return updateStatus("Song is not in Navidrome index")
        downloader.execute {
            beginTransfer()
            try {
                val settings = secrets.load() ?: throw IllegalStateException("Save Navidrome login first")
                updateStatus("Downloading ${song.title}...")
                cache.download(NavidromeClient(settings), song.remote) { t -> transfer(song.title, t) }
                updateStatus("Downloaded ${song.title}")
                gatt?.broadcastLibraryChanged()
            } catch (e: Exception) { updateStatus("Could not download: ${safeError(e)}") }
            finally { finishTransfer() }
        }
    }

    private fun deleteTrack(id: String) {
        val song = library.find(id) ?: return updateStatus("Song not found")
        scanner.execute {
            if (cache.remove(song.remote)) {
                updateStatus("Removed offline copy of ${song.title}. Song remains on Navidrome.")
                gatt?.broadcastLibraryChanged()
            } else updateStatus("Unable to delete cached ${song.title}")
        }
    }

    private fun deleteAll() {
        batchGeneration.incrementAndGet()
        scanner.execute {
            // Stop playback first if it holds a file that is about to be deleted.
            runCatching { engine.control("pause", null) }
            val count = cache.clearDownloads()
            updateStatus("Removed $count cached Navidrome files; server library unchanged")
            gatt?.broadcastLibraryChanged()
        }
    }

    private fun safeError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Connection timed out"
        is java.net.UnknownHostException -> "Server address not reachable"
        else -> e.message.orEmpty().substringBefore('?').take(100).ifBlank { "Unknown error" }
    }

    override fun onDestroy() {
        gatt?.close(); gatt = null
        mainHandler.removeCallbacks(batteryTick)
        scanner.shutdownNow()
        telemetry.shutdownNow()
        downloader.shutdownNow()
        engine.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.tailtune.standalone.START"
        const val ACTION_STOP = "dev.tailtune.standalone.STOP"
        const val ACTION_SYNC = "dev.tailtune.standalone.SYNC_NAVIDROME"
        const val ACTION_DOWNLOAD = "dev.tailtune.standalone.DOWNLOAD_NAVIDROME"
        const val ACTION_DOWNLOAD_PLAYLIST = "dev.tailtune.standalone.DOWNLOAD_PLAYLIST"
        const val ACTION_DOWNLOAD_TRACK = "dev.tailtune.standalone.DOWNLOAD_TRACK"
        const val ACTION_DELETE_TRACK = "dev.tailtune.standalone.DELETE_TRACK"
        const val ACTION_DELETE_ALL = "dev.tailtune.standalone.DELETE_ALL"
        const val ACTION_CANCEL_DOWNLOAD = "dev.tailtune.standalone.CANCEL_DOWNLOAD"
        const val EXTRA_ID = "navidrome_id"
        const val EXTRA_COUNT = "track_count"
        private const val CHANNEL = "tailtune-player"
        private const val TAG = "TailTune-Samsung"
    }
}
