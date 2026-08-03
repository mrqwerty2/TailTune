package dev.tailtune.remote

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class TailTuneServer(
    private val service: PlaybackService,
    port: Int
) : NanoHTTPD(port) {

    private val playlistCache = ConcurrentHashMap<String, RemotePlaylist>()

    @Volatile
    private var navidromeOnline: Boolean = false

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/" -> asset("web/index.html", "text/html; charset=utf-8")
                session.uri == "/app.js" -> asset("web/app.js", "application/javascript; charset=utf-8")
                session.uri == "/style.css" -> asset("web/style.css", "text/css; charset=utf-8")

                session.uri == "/api/config" && session.method == Method.GET -> json(
                    JSONObject()
                        .put("configured", service.currentClientOrNull() != null)
                        .put("offlinePlaylistCount", service.offlineStore().allStatuses().size)
                )

                session.uri == "/api/playlists" && session.method == Method.GET -> json(playlistsJson())

                session.uri == "/api/playlist" && session.method == Method.GET -> {
                    val id = requiredQuery(session, "id")
                    json(playlistJson(loadPlaylist(id)))
                }

                session.uri == "/api/state" && session.method == Method.GET -> json(service.stateJson())

                session.uri == "/api/control" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    when (val action = body.optString("action")) {
                        "playPlaylist" -> {
                            val playlist = loadPlaylist(body.getString("playlistId"))
                            service.playPlaylist(playlist, body.optInt("startIndex", 0))
                        }
                        "addFromPlaylist" -> {
                            val playlist = loadPlaylist(body.getString("playlistId"))
                            val index = body.getInt("index")
                            val song = playlist.songs.getOrNull(index)
                                ?: throw IllegalArgumentException("Song index is out of range")
                            service.addSong(song)
                        }
                        else -> service.control(action, body)
                    }
                    json(service.stateJson())
                }

                session.uri == "/api/queue" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    service.queueAction(body.getString("action"), body)
                    json(service.stateJson())
                }

                session.uri == "/api/downloads" && session.method == Method.GET -> {
                    json(service.downloads().statusJson())
                }

                session.uri == "/api/download" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    val playlist = loadPlaylist(body.getString("playlistId"), preferRemote = true)
                    service.downloads().start(playlist)
                    json(service.downloads().statusJson())
                }

                session.uri == "/api/download/remove" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    val playlistId = body.getString("playlistId")
                    service.downloads().cancelAndRemove(playlistId)
                    playlistCache.remove(playlistId)
                    json(service.downloads().statusJson())
                }

                session.uri == "/api/refresh" && session.method == Method.POST -> {
                    playlistCache.clear()
                    json(playlistsJson())
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (error: Exception) {
            val root = rootCause(error)
            json(
                JSONObject().put("error", root.message ?: root.javaClass.simpleName),
                Response.Status.INTERNAL_ERROR
            )
        }
    }

    private fun playlistsJson(): JSONObject {
        val offlineById = service.offlineStore().listOfflinePlaylists()
            .associateBy { it.summary.id }
        val remoteResult = runCatching { service.currentClient().getPlaylists() }
        val online = remoteResult.isSuccess
        navidromeOnline = online
        val warning = remoteResult.exceptionOrNull()?.let(::rootCause)?.message

        val merged = linkedMapOf<String, PlaylistSummary>()
        remoteResult.getOrDefault(emptyList()).forEach { merged[it.id] = it }
        offlineById.values.forEach { merged.putIfAbsent(it.summary.id, it.summary) }

        val array = JSONArray()
        merged.values.sortedBy { it.name.lowercase() }.forEach { summary ->
            val status = service.offlineStore().getStatus(summary.id)
            array.put(
                summary.toJson()
                    .put("downloadedCount", status?.downloadedCount ?: 0)
                    .put("offlineTotal", status?.totalCount ?: summary.songCount)
                    .put("offlineComplete", status?.complete ?: false)
                    .put("offlineKnown", status != null)
            )
        }

        return JSONObject()
            .put("playlists", array)
            .put("online", online)
            .put("warning", warning ?: JSONObject.NULL)
            .put("storage", service.offlineStore().storageJson())
    }

    private fun loadPlaylist(id: String, preferRemote: Boolean = false): RemotePlaylist {
        if (!preferRemote) playlistCache[id]?.let { return it }
        val offlinePlaylist = service.offlineStore().getPlaylist(id)
        if (!preferRemote && !navidromeOnline && offlinePlaylist != null) {
            playlistCache[id] = offlinePlaylist
            return offlinePlaylist
        }

        val remoteAttempt = runCatching { service.currentClient().getPlaylist(id) }
        val playlist = remoteAttempt.getOrNull()
            ?: offlinePlaylist
            ?: throw IllegalStateException(
                remoteAttempt.exceptionOrNull()?.let(::rootCause)?.message
                    ?: "Playlist is not available offline"
            )

        playlistCache[id] = playlist
        return playlist
    }

    private fun playlistJson(playlist: RemotePlaylist): JSONObject {
        val songs = JSONArray()
        playlist.songs.forEachIndexed { index, song ->
            songs.put(
                song.toJson(index)
                    .put("offlineAvailable", service.offlineStore().isSongAvailable(song.id))
            )
        }
        val status = service.offlineStore().getStatus(playlist.summary.id)
        return JSONObject()
            .put(
                "playlist",
                playlist.summary.toJson()
                    .put("downloadedCount", status?.downloadedCount ?: 0)
                    .put("offlineTotal", status?.totalCount ?: playlist.songs.size)
                    .put("offlineComplete", status?.complete ?: false)
            )
            .put("songs", songs)
    }

    private fun requiredQuery(session: IHTTPSession, key: String): String =
        session.parameters[key]?.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Missing query parameter: $key")

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        val files = HashMap<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private fun asset(path: String, mime: String): Response =
        newChunkedResponse(Response.Status.OK, mime, service.assets.open(path)).apply {
            addHeader("Cache-Control", "no-store")
        }

    private fun json(
        value: JSONObject,
        status: Response.Status = Response.Status.OK
    ): Response = newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Access-Control-Allow-Origin", "*")
    }

    private fun rootCause(error: Throwable): Throwable {
        var result = error
        while (result.cause != null && result.cause !== result) result = result.cause!!
        return result
    }
}
