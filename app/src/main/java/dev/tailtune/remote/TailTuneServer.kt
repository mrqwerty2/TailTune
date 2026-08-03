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

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.uri == "/" -> asset("web/index.html", "text/html; charset=utf-8")
                session.uri == "/app.js" -> asset("web/app.js", "application/javascript; charset=utf-8")
                session.uri == "/style.css" -> asset("web/style.css", "text/css; charset=utf-8")

                session.uri == "/api/config" && session.method == Method.GET -> json(
                    JSONObject().put("configured", runCatching { service.currentClient(); true }.getOrDefault(false))
                )

                session.uri == "/api/playlists" && session.method == Method.GET -> {
                    val playlists = JSONArray()
                    service.currentClient().getPlaylists().forEach { playlists.put(it.toJson()) }
                    json(JSONObject().put("playlists", playlists))
                }

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

                session.uri == "/api/refresh" && session.method == Method.POST -> {
                    playlistCache.clear()
                    val playlists = JSONArray()
                    service.currentClient().getPlaylists().forEach { playlists.put(it.toJson()) }
                    json(JSONObject().put("playlists", playlists))
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (error: Exception) {
            val root = rootCause(error)
            json(
                JSONObject()
                    .put("error", root.message ?: root.javaClass.simpleName),
                Response.Status.INTERNAL_ERROR
            )
        }
    }

    private fun loadPlaylist(id: String): RemotePlaylist = playlistCache[id]
        ?: service.currentClient().getPlaylist(id).also { playlistCache[id] = it }

    private fun playlistJson(playlist: RemotePlaylist): JSONObject {
        val songs = JSONArray()
        playlist.songs.forEachIndexed { index, song -> songs.put(song.toJson(index)) }
        return JSONObject()
            .put("playlist", playlist.summary.toJson())
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
