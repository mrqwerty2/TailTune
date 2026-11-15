package dev.tailtune.standalone

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

/** App-private Navidrome downloads only; no fixed 2 GB limit or automatic eviction. */
class NavidromeCache(context: Context) {
    private val directory = File(context.filesDir, "navidrome").also { it.mkdirs() }
    private val musicDirectory = File(directory, "music").also { it.mkdirs() }
    private val locks = ConcurrentHashMap<String, Any>()
    private val maxSongBytes = 1024L * 1024 * 1024 // protect from corrupt/malicious HTTP response
    private val freeReserveBytes = 256L * 1024 * 1024 // leave usable device storage

    fun indexVersion(): String = "${manifest().lastModified()}:${manifest().length()}:" +
        "${playlistManifest().lastModified()}:${playlistManifest().length()}"

    fun fileFor(song: RemoteSong): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(song.id.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
        val suffix = song.suffix.lowercase().takeIf {
            it in setOf("mp3", "m4a", "flac", "ogg", "opus", "aac", "wav", "wma", "alac", "bin")
        } ?: "bin"
        return File(musicDirectory, "$hash.$suffix")
    }

    fun downloaded(song: RemoteSong) = fileFor(song).let { it.isFile && it.length() > 0 }
    data class Usage(val bytes: Long, val count: Int, val freeBytes: Long)
    fun usage(): Usage {
        val files = musicDirectory.listFiles()?.filter { it.isFile && !it.name.endsWith(".partial") } ?: emptyList()
        return Usage(files.sumOf { it.length() }, files.size, StatFs(musicDirectory.absolutePath).availableBytes)
    }

    private fun manifest() = File(directory, "catalog.json")
    private fun playlistManifest() = File(directory, "playlists.json")

    fun loadCatalog(): List<RemoteSong> = try {
        val file = manifest()
        if (!file.isFile || file.length() > 9_000_000) emptyList() else {
            val songs = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("songs") ?: JSONArray()
            (0 until minOf(songs.length(), 10000)).mapNotNull { songs.optJSONObject(it)?.let(RemoteSong::fromJson) }
        }
    } catch (_: Exception) { emptyList() }

    fun loadPlaylists(): List<RemotePlaylist> = try {
        val file = playlistManifest()
        if (!file.isFile || file.length() > 4_000_000) emptyList() else {
            val playlists = JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("playlists") ?: JSONArray()
            (0 until minOf(playlists.length(), 250)).mapNotNull {
                playlists.optJSONObject(it)?.let(RemotePlaylist::fromJson)
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun atomicWrite(dest: File, contents: String, maxBytes: Int) {
        val bytes = contents.toByteArray(Charsets.UTF_8)
        require(bytes.size <= maxBytes) { "Navidrome index too large" }
        val temp = File(directory, "${dest.name}.partial")
        try {
            temp.writeBytes(bytes)
            if (!temp.renameTo(dest)) throw IOException("Cannot save offline library")
        } finally { temp.delete() }
    }

    @Synchronized fun saveCatalog(list: List<RemoteSong>, playlists: List<RemotePlaylist>) {
        atomicWrite(manifest(), JSONObject().put("songs", JSONArray(list.take(10000).map { it.toJson() })).toString(), 9_000_000)
        atomicWrite(playlistManifest(), JSONObject().put("playlists", JSONArray(playlists.map { it.toJson() })).toString(), 4_000_000)
    }

    data class Transfer(val done: Long, val total: Long, val bytesPerSecond: Double)

    /** Reports progress roughly twice per second and leaves no partial download behind on failure. */
    @Synchronized fun download(client: NavidromeClient, song: RemoteSong, onProgress: (Transfer) -> Unit = {}): File {
        val lock = locks.getOrPut(song.id) { Any() }
        synchronized(lock) {
            val target = fileFor(song)
            if (target.isFile && target.length() > 0) return target
            val conn = client.openDownload(song.id)
            val part = File(musicDirectory, "${target.name}.partial")
            try {
                val expected = conn.contentLengthLong
                if (expected > maxSongBytes) throw IOException("Track exceeds 1 GB safety limit")
                if (availableSpace() <= freeReserveBytes + (if (expected > 0) expected else 0L))
                    throw IOException("Not enough Samsung storage. Remove old downloads in TailTune.")
                val started = SystemClock.elapsedRealtime()
                var lastReport = started
                var total = 0L
                conn.inputStream.use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (Thread.currentThread().isInterrupted) throw IOException("Download cancelled")
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            if (total > maxSongBytes || (total % (2 * 1024 * 1024) < count && availableSpace() < freeReserveBytes))
                                throw IOException("Storage running low. Delete some offline tracks.")
                            output.write(buffer, 0, count)
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastReport >= 500) {
                                onProgress(Transfer(total, expected, total * 1000.0 / (now - started).coerceAtLeast(1)))
                                lastReport = now
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (total == 0L || (expected > 0 && expected != total)) throw IOException("Incomplete Navidrome download")
                if (!part.renameTo(target)) throw IOException("Cannot save downloaded track")
                onProgress(Transfer(total, total, total * 1000.0 / (SystemClock.elapsedRealtime() - started).coerceAtLeast(1)))
                return target
            } finally {
                conn.disconnect()
                part.delete()
            }
        }
    }

    private fun availableSpace(): Long = StatFs(musicDirectory.absolutePath).availableBytes

    @Synchronized fun remove(song: RemoteSong): Boolean {
        val lock = locks.getOrPut(song.id) { Any() }
        return synchronized(lock) {
            val file = fileFor(song)
            !file.exists() || file.delete()
        }
    }

    /** Only app-private Navidrome audio is deleted; Navidrome server songs are untouched. */
    @Synchronized fun clearDownloads(): Int {
        var count = 0
        musicDirectory.listFiles()?.filter { it.isFile && !it.name.endsWith(".partial") }?.forEach { file ->
            if (file.delete()) count++
        }
        return count
    }
}
