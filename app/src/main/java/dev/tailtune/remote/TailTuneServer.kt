package dev.tailtune.remote

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

class TailTuneServer(
    private val service: PlaybackService,
    port: Int
) : NanoWSD(port) {

    private val sockets = CopyOnWriteArraySet<RemoteSocket>()
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-WebSocketEvents").apply { isDaemon = true }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = RemoteSocket(handshake)

    override fun serveHttp(session: IHTTPSession): NanoHTTPD.Response {
        if (session.method == Method.OPTIONS) return corsPreflight()

        return try {
            when {
                session.uri == "/" -> asset("web/index.html", "text/html; charset=utf-8", cache = false)
                session.uri == "/app.js" -> asset("web/app.js", "application/javascript; charset=utf-8", cache = true)
                session.uri == "/style.css" -> asset("web/style.css", "text/css; charset=utf-8", cache = true)

                session.uri == "/api/bootstrap" && session.method == Method.GET -> {
                    json(service.bootstrapJson())
                }

                session.uri == "/api/playlists" && session.method == Method.GET -> {
                    json(service.playlistsJson())
                }

                session.uri == "/api/playlist" && session.method == Method.GET -> {
                    val id = requiredQuery(session, "id")
                    json(playlistJson(service.librarySync().loadPlaylist(id)))
                }

                session.uri == "/api/state" && session.method == Method.GET -> {
                    json(service.stateJson())
                }

                session.uri == "/api/sync" && session.method == Method.POST -> {
                    service.librarySync().start(force = true)
                    json(service.playlistsJson())
                }

                session.uri == "/api/control" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    when (val action = body.optString("action")) {
                        "playPlaylist" -> {
                            val playlist = service.librarySync().loadPlaylist(body.getString("playlistId"))
                            service.playPlaylist(playlist, body.optInt("startIndex", 0))
                        }
                        "addFromPlaylist" -> {
                            val playlist = service.librarySync().loadPlaylist(body.getString("playlistId"))
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
                    val playlist = service.librarySync().loadPlaylist(
                        body.getString("playlistId"),
                        preferRemote = true
                    )
                    service.downloads().start(playlist)
                    json(service.downloads().statusJson())
                }

                session.uri == "/api/download/remove" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    service.downloads().cancelAndRemove(body.getString("playlistId"))
                    json(service.downloads().statusJson())
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

    fun broadcastSnapshot() = enqueueEvent {
        event("snapshot", service.bootstrapJson())
    }

    fun broadcastPlayback() = enqueueEvent {
        event("playback", service.stateJson())
    }

    fun broadcastLibrary() = enqueueEvent {
        event("library", service.playlistsJson())
    }

    fun broadcastDownloads() = enqueueEvent {
        event("downloads", service.downloads().statusJson())
    }

    override fun stop() {
        sockets.clear()
        eventExecutor.shutdownNow()
        super.stop()
    }

    private fun enqueueEvent(factory: () -> JSONObject) {
        if (sockets.isEmpty()) return
        eventExecutor.submit {
            val payload = runCatching(factory).getOrNull()?.toString() ?: return@submit
            sockets.forEach { socket ->
                try {
                    socket.send(payload)
                } catch (_: IOException) {
                    sockets.remove(socket)
                }
            }
        }
    }

    private fun event(type: String, data: JSONObject): JSONObject = JSONObject()
        .put("type", type)
        .put("data", data)
        .put("timestamp", System.currentTimeMillis())

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

    private fun asset(path: String, mime: String, cache: Boolean): Response =
        newChunkedResponse(Response.Status.OK, mime, service.assets.open(path)).apply {
            addHeader(
                "Cache-Control",
                if (cache) "public, max-age=86400, immutable" else "no-cache, no-store, must-revalidate"
            )
            addHeader("X-Content-Type-Options", "nosniff")
        }

    private fun json(
        value: JSONObject,
        status: Response.Status = Response.Status.OK
    ): Response = newFixedLengthResponse(
        status,
        "application/json; charset=utf-8",
        value.toString()
    ).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun corsPreflight(): Response = newFixedLengthResponse(
        Response.Status.NO_CONTENT,
        "text/plain",
        ""
    ).apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "Content-Type")
    }

    private fun rootCause(error: Throwable): Throwable {
        var result = error
        while (result.cause != null && result.cause !== result) result = result.cause!!
        return result
    }

    private inner class RemoteSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            sockets += this
            runCatching { send(event("snapshot", service.bootstrapJson()).toString()) }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            sockets -= this
        }

        override fun onMessage(message: WebSocketFrame) {
            // Commands continue to use the HTTP API. WebSocket is intentionally
            // server-push only, which keeps reconnect behavior simple on iOS.
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            sockets -= this
        }
    }
}
