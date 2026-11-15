package dev.tailtune.standalone

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.io.File
import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** All MediaPlayer interactions are serialized on a dedicated Looper; never block a BLE binder callback. */
class PlaybackEngine(
    private val context: Context,
    private val library: MusicLibrary,
    private val changed: () -> Unit,
    private val fetchRemote: (RemoteSong, (Result<File>) -> Unit) -> Unit,
) : AutoCloseable {
    private val thread = HandlerThread("TailTune-Player").apply { start() }
    private val handler = Handler(thread.looper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var prepared = false
    private var wantsToPlay = false
    private var queue: MutableList<LocalSong> = mutableListOf()
    private var index = -1
    private var revision = 0
    private var shuffle = false
    private var repeat = "off"
    private var failure: String? = null
    private var requestVersion = 0L
    private var loading = false
    @Volatile var navidromeOnline: Boolean = false
    @Volatile private var closed = false

    private fun <T> onPlayer(block: () -> T): T {
        if (closed) throw IllegalStateException("Player stopped")
        if (Looper.myLooper() == thread.looper) return block()
        val result = CompletableFuture<T>()
        handler.post {
            try { result.complete(block()) }
            catch (e: Exception) { result.completeExceptionally(e) }
        }
        return result.get(8, TimeUnit.SECONDS)
    }

    private fun active(): LocalSong? = queue.getOrNull(index)
    private fun notifyChange() { changed() }

    private fun startAt(newIndex: Int) {
        val song = queue.getOrNull(newIndex) ?: return
        index = newIndex
        requestVersion++
        val expectedVersion = requestVersion
        prepared = false
        loading = false
        wantsToPlay = true
        failure = null
        // Silence the previous track immediately instead of playing it during a new download.
        runCatching { player?.reset() }
        revision++
        notifyChange()

        if (song.remote != null && !song.downloaded) {
            loading = true
            fetchRemote(song.remote) { outcome ->
                if (!closed) handler.post {
                    if (closed || expectedVersion != requestVersion || active()?.id != song.id) return@post
                    loading = false
                    outcome.fold(
                        onSuccess = { file -> openAudio(song, android.net.Uri.fromFile(file), expectedVersion) },
                        onFailure = {
                            prepared = false
                            wantsToPlay = false
                            failure = it.message?.take(160) ?: "Download failed"
                            notifyChange()
                        },
                    )
                }
            }
            return
        }
        openAudio(song, song.uri, expectedVersion)
    }

    private fun openAudio(song: LocalSong, mediaUri: android.net.Uri, expectedVersion: Long) {
        try {
            val mp = player ?: MediaPlayer().also { player = it }
            mp.reset()
            mp.setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            )
            mp.setDataSource(context, mediaUri)
            mp.setOnPreparedListener { ready ->
                if (closed || requestVersion != expectedVersion || active()?.id != song.id) return@setOnPreparedListener
                prepared = true
                if (wantsToPlay) runCatching { ready.start() }
                    .onFailure { failure = it.message ?: "Could not start audio" }
                notifyChange()
            }
            mp.setOnCompletionListener {
                if (!closed && requestVersion == expectedVersion && active()?.id == song.id) advance(completion = true)
            }
            mp.setOnErrorListener { _, what, extra ->
                if (closed || requestVersion != expectedVersion || active()?.id != song.id) return@setOnErrorListener true
                prepared = false
                wantsToPlay = false
                failure = "Playback error $what / $extra"
                notifyChange()
                true
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            prepared = false
            wantsToPlay = false
            failure = e.message?.take(160) ?: "Cannot open music file"
            notifyChange()
        }
    }

    private fun advance(completion: Boolean = false) {
        if (queue.isEmpty()) return
        if (completion && repeat == "one") {
            startAt(index.coerceAtLeast(0))
            return
        }
        val next = if (shuffle && queue.size > 1) {
            var chosen = Random.nextInt(queue.size - 1)
            if (chosen >= index) chosen++
            chosen
        } else index + 1
        when {
            next in queue.indices -> startAt(next)
            repeat == "all" -> startAt(0)
            else -> {
                runCatching { player?.seekTo(0) }
                wantsToPlay = false
                notifyChange()
            }
        }
    }

    fun playPlaylist(id: String, startIndex: Int): JSONObject = onPlayer {
        val items = library.playlist(id)
        if (items.isEmpty()) throw IllegalArgumentException("No tracks in this playlist")
        queue = items.toMutableList()
        startAt(startIndex.coerceIn(queue.indices))
        JSONObject().put("accepted", true)
    }

    fun playTrack(id: String): JSONObject = onPlayer {
        val track = library.find(id) ?: throw IllegalArgumentException("Track not found")
        if (queue.isEmpty()) queue.add(track)
        else if (queue.none { it.id == id }) queue.add(track)
        startAt(queue.indexOfFirst { it.id == id })
        JSONObject().put("accepted", true)
    }

    fun addToQueue(id: String): JSONObject = onPlayer {
        val track = library.find(id) ?: throw IllegalArgumentException("Track not found")
        if (queue.size >= 20000) throw IllegalArgumentException("Queue full")
        queue.add(track)
        revision++
        notifyChange()
        JSONObject().put("accepted", true)
    }

    fun control(action: String, value: Int?): JSONObject = onPlayer {
        try {
            when (action) {
                "play", "toggle" -> {
                    val currently = prepared && (player?.isPlaying == true)
                    if (action == "toggle" && currently) {
                        player?.pause(); wantsToPlay = false
                    } else if (index < 0 && library.songs.isNotEmpty()) {
                        queue = library.songs.toMutableList()
                        startAt(0)
                    } else {
                        wantsToPlay = true
                        if (prepared) player?.start()
                        else if (!loading && index in queue.indices) startAt(index)
                    }
                }
                "pause" -> {
                    wantsToPlay = false
                    if (prepared && player?.isPlaying == true) player?.pause()
                }
                "next" -> advance()
                "previous" -> if (queue.isNotEmpty()) {
                    startAt((index - 1).coerceAtLeast(0))
                }
                "seek" -> if (prepared) {
                    val duration = player?.duration ?: 0
                    player?.seekTo((value ?: 0).coerceIn(0, duration.coerceAtLeast(0)))
                }
                "volume" -> {
                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, (max * (value ?: 50) / 100).coerceIn(0, max), 0)
                }
                "shuffle" -> shuffle = !shuffle
                "repeat" -> repeat = when (repeat) { "off" -> "all"; "all" -> "one"; else -> "off" }
                else -> throw IllegalArgumentException("Unknown control")
            }
        } catch (e: IllegalStateException) {
            failure = e.message ?: "Playback state error"
        }
        notifyChange()
        JSONObject().put("accepted", true)
    }

    fun queue(offset: Int, limit: Int): JSONObject = onPlayer {
        MusicLibrary.page(queue.drop(offset).take(limit).map { it.json() }, offset, limit, queue.size, prePaged = true)
    }

    fun snapshot(): JSONObject = onPlayer {
        val current = active()
        val playing = prepared && runCatching { player?.isPlaying == true }.getOrDefault(false)
        val position = if (prepared) runCatching { player?.currentPosition ?: 0 }.getOrDefault(0) else 0
        val duration = if (prepared) runCatching { player?.duration ?: 0 }.getOrDefault(0)
            else current?.durationMs?.toInt() ?: 0
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val battery = if (level >= 0 && scale > 0) (100.0 * level / scale).toInt().coerceIn(0, 100)
            else batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = if (sticky != null) {
            val status = sticky.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } else batteryManager.isCharging
        val volMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / volMax
        JSONObject()
            .put("device", JSONObject().put("name", android.os.Build.MODEL)
                .put("batteryPercent", if (battery in 0..100) battery else JSONObject.NULL)
                .put("charging", charging)
                .put("navidromeOnline", navidromeOnline))
            .put("playback", JSONObject()
                .put("isPlaying", playing).put("positionMs", position.coerceAtLeast(0))
                .put("durationMs", duration.coerceAtLeast(0))
                .put("volume", volume).put("shuffle", shuffle).put("repeatMode", repeat)
                .put("current", current?.json() ?: JSONObject.NULL))
            .put("queueRevision", revision)
            .put("error", failure?.let { it } ?: JSONObject.NULL)
    }

    override fun close() {
        if (closed) return
        // Release on player Looper, not on BLE Binder thread or UI thread.
        closed = true
        handler.post { runCatching { player?.release() }; player = null; thread.quitSafely() }
    }
}
