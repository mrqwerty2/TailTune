package dev.tailtune.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

class PlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var playerHandler: Handler
    private lateinit var offlineStore: OfflineStore
    private lateinit var downloadManager: OfflineDownloadManager
    private lateinit var downloadWakeLock: PowerManager.WakeLock
    private var webServer: TailTuneServer? = null

    @Volatile
    private var client: SubsonicClient? = null

    @Volatile
    private var lastError: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startRemoteForegroundNotification()

        offlineStore = OfflineStore(this)
        downloadWakeLock = getSystemService(PowerManager::class.java).newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TailTune:OfflineDownloads"
        ).apply { setReferenceCounted(false) }
        downloadManager = OfflineDownloadManager(
            store = offlineStore,
            clientProvider = { currentClient() },
            onDownloadActiveChanged = { active ->
                if (active && !downloadWakeLock.isHeld) {
                    downloadWakeLock.acquire(DOWNLOAD_WAKE_LOCK_TIMEOUT_MS)
                } else if (!active && downloadWakeLock.isHeld) {
                    downloadWakeLock.release()
                }
            }
        )

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(audioAttributes, true)
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    lastError = error.message ?: error.errorCodeName
                }
            })
        }
        playerHandler = Handler(player.applicationLooper)
        mediaSession = MediaSession.Builder(this, player).build()

        reloadConfiguration()
        webServer = TailTuneServer(this, 8787).also {
            it.start(NanoHttpStartTimeout.SOCKET_READ_TIMEOUT_MS, false)
        }
    }

    fun reloadConfiguration() {
        val settings = ServerSettings.load(this)
        client = if (settings.configured) SubsonicClient(settings) else null
        lastError = null
    }

    fun currentClient(): SubsonicClient = client
        ?: throw IllegalStateException("Configure Navidrome in the TailTune Android app first")

    fun currentClientOrNull(): SubsonicClient? = client

    fun offlineStore(): OfflineStore = offlineStore

    fun downloads(): OfflineDownloadManager = downloadManager

    fun playPlaylist(playlist: RemotePlaylist, startIndex: Int = 0) {
        if (playlist.songs.isEmpty()) throw IllegalStateException("This playlist has no playable songs")
        val clientSnapshot = currentClientOrNull()
        val playable = playlist.songs.mapIndexedNotNull { originalIndex, song ->
            song.toMediaItemOrNull(clientSnapshot)?.let { originalIndex to it }
        }
        if (playable.isEmpty()) {
            throw IllegalStateException("No downloaded tracks are available and Navidrome is unreachable")
        }

        val playerStartIndex = playable.indexOfFirst { it.first >= startIndex }
            .takeIf { it >= 0 }
            ?: 0

        onPlayerThread {
            player.setMediaItems(playable.map { it.second }, playerStartIndex, 0L)
            player.prepare()
            player.play()
            lastError = null
        }
    }

    fun addSong(song: RemoteSong) {
        val mediaItem = song.toMediaItemOrNull(currentClientOrNull())
            ?: throw IllegalStateException("This song is not downloaded and Navidrome is unavailable")
        onPlayerThread {
            player.addMediaItem(mediaItem)
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
        }
    }

    fun control(action: String, body: JSONObject) {
        onPlayerThread {
            when (action) {
                "play" -> player.play()
                "pause" -> player.pause()
                "toggle" -> if (player.isPlaying) player.pause() else player.play()
                "next" -> if (player.hasNextMediaItem()) player.seekToNextMediaItem()
                "previous" -> if (player.currentPosition > 5_000) player.seekTo(0)
                    else if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem()
                "seek" -> player.seekTo(body.optLong("positionMs", 0L).coerceAtLeast(0L))
                "jump" -> {
                    val index = body.optInt("index", -1)
                    if (index in 0 until player.mediaItemCount) {
                        player.seekToDefaultPosition(index)
                        player.play()
                    }
                }
                else -> throw IllegalArgumentException("Unknown control action: $action")
            }
        }
    }

    fun queueAction(action: String, body: JSONObject) {
        onPlayerThread {
            when (action) {
                "remove" -> {
                    val index = body.optInt("index", -1)
                    if (index in 0 until player.mediaItemCount) player.removeMediaItem(index)
                }
                "move" -> {
                    val from = body.optInt("from", -1)
                    val to = body.optInt("to", -1)
                    if (from in 0 until player.mediaItemCount && to in 0 until player.mediaItemCount) {
                        player.moveMediaItem(from, to)
                    }
                }
                "clear" -> player.clearMediaItems()
                else -> throw IllegalArgumentException("Unknown queue action: $action")
            }
        }
    }

    fun stateJson(): JSONObject = onPlayerThread {
        val queue = JSONArray()
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            queue.put(
                JSONObject()
                    .put("index", index)
                    .put("id", item.mediaId)
                    .put("title", item.mediaMetadata.title?.toString().orEmpty())
                    .put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                    .put("album", item.mediaMetadata.albumTitle?.toString().orEmpty())
                    .put("offline", item.localConfiguration?.uri?.scheme == "file")
            )
        }

        val current = player.currentMediaItem?.let { item ->
            JSONObject()
                .put("index", player.currentMediaItemIndex)
                .put("id", item.mediaId)
                .put("title", item.mediaMetadata.title?.toString().orEmpty())
                .put("artist", item.mediaMetadata.artist?.toString().orEmpty())
                .put("album", item.mediaMetadata.albumTitle?.toString().orEmpty())
                .put("offline", item.localConfiguration?.uri?.scheme == "file")
        } ?: JSONObject.NULL

        val duration = player.duration.takeIf { it > 0L } ?: 0L
        JSONObject()
            .put("playing", player.isPlaying)
            .put("positionMs", player.currentPosition.coerceAtLeast(0L))
            .put("durationMs", duration)
            .put("current", current)
            .put("queue", queue)
            .put("error", lastError ?: JSONObject.NULL)
    }

    private fun RemoteSong.toMediaItemOrNull(client: SubsonicClient?): MediaItem? {
        val localFile = offlineStore.localFile(id)
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

    private fun <T> onPlayerThread(block: () -> T): T {
        if (Looper.myLooper() == player.applicationLooper) return block()
        val task = FutureTask<T> { block() }
        if (!playerHandler.post(task)) throw IllegalStateException("Player thread is unavailable")
        return task.get(8, TimeUnit.SECONDS)
    }

    private fun startRemoteForegroundNotification() {
        val channelId = "tailtune_remote"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "TailTune remote",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the TailTune web remote and offline downloads available"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle("TailTune is running")
            .setContentText("Web remote and offline downloads are available")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val serviceTypes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, REMOTE_NOTIFICATION_ID, notification, serviceTypes)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the web remote, downloads and playback alive after the activity is dismissed.
    }

    override fun onDestroy() {
        webServer?.stop()
        downloadManager.shutdown()
        if (::downloadWakeLock.isInitialized && downloadWakeLock.isHeld) downloadWakeLock.release()
        mediaSession.release()
        player.release()
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val REMOTE_NOTIFICATION_ID = 42
        private const val DOWNLOAD_WAKE_LOCK_TIMEOUT_MS = 6L * 60L * 60L * 1000L
        @Volatile var instance: PlaybackService? = null
            private set
    }

    private object NanoHttpStartTimeout {
        const val SOCKET_READ_TIMEOUT_MS = 5_000
    }
}
