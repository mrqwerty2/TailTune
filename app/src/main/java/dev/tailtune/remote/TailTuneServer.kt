package dev.tailtune.remote

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Same-origin HTTP API and server-push WebSocket used by the iPhone remote.
 * API routes require a random per-install token shown only in the Android app.
 */
class TailTuneServer(
    private val service: PlaybackService,
    port: Int,
    private val accessToken: String
) : NanoWSD(port) {
    private val sockets = CopyOnWriteArraySet<RemoteSocket>()
    private val eventExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "TailTune-WebSocketEvents").apply { isDaemon = true }
    }
    private val requestThreadId = AtomicInteger(0)
    // A bounded queue protects a headless phone from a buggy/malicious client
    // enqueueing requests faster than Navidrome/SQLite can consume them.
    private val requestExecutor = ThreadPoolExecutor(
        SOCKET_REQUEST_THREADS,
        SOCKET_REQUEST_THREADS,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(MAX_PENDING_SOCKET_REQUESTS),
        { runnable ->
            Thread(
                runnable,
                "TailTune-WebSocketRequest-${requestThreadId.incrementAndGet()}"
            ).apply { isDaemon = true }
        },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val pendingEvents = ConcurrentHashMap<String, () -> JSONObject>()
    private val eventDrainScheduled = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val socketAdmissionLock = Any()

    override fun openWebSocket(handshake: IHTTPSession): WebSocket =
        RemoteSocket(handshake)

    override fun serveHttp(session: IHTTPSession): NanoHTTPD.Response {
        if (session.method == Method.OPTIONS) return preflight()

        return try {
            when {
                session.uri == "/" && session.method == Method.GET ->
                    asset("web/index.html", "text/html; charset=utf-8", cache = false)

                session.uri == "/app.js" && session.method == Method.GET ->
                    asset("web/app.js", "application/javascript; charset=utf-8", cache = false)

                session.uri == "/style.css" && session.method == Method.GET ->
                    asset("web/style.css", "text/css; charset=utf-8", cache = false)

                session.uri.startsWith("/api/") && !isAuthorized(session) -> unauthorized()

                session.uri == "/api/health" && session.method == Method.GET ->
                    json(JSONObject().put("ok", true).put("version", BuildConfig.VERSION_NAME))

                session.uri == "/api/bootstrap" && session.method == Method.GET ->
                    json(service.bootstrapJson())

                session.uri == "/api/playlists" && session.method == Method.GET ->
                    json(service.playlistsJson())

                session.uri == "/api/playlist" && session.method == Method.GET -> {
                    val id = requiredQuery(session, "id")
                    json(playlistJson(service.librarySync().loadPlaylist(id)))
                }

                session.uri == "/api/state" && session.method == Method.GET ->
                    json(service.stateJson())

                session.uri == "/api/sync" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    service.librarySync().start(
                        fullRefresh = optionalBoolean(body, "full", false)
                    )
                    json(service.playlistsJson())
                }

                session.uri == "/api/control" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    val action = requiredString(body, "action", MAX_ACTION_LENGTH)
                    val queueChanged = when (action) {
                        "playPlaylist" -> {
                            val playlistId = requiredString(body, "playlistId")
                            val playlist = service.librarySync().loadPlaylist(playlistId)
                            val startIndex = optionalInt(body, "startIndex", 0)
                            require(startIndex >= 0) { "startIndex cannot be negative" }
                            service.playPlaylist(playlist, startIndex)
                            true
                        }
                        "addFromPlaylist" -> {
                            val playlistId = requiredString(body, "playlistId")
                            val playlist = service.librarySync().loadPlaylist(playlistId)
                            val index = requiredInt(body, "index")
                            val song = playlist.songs.getOrNull(index)
                                ?: throw IllegalArgumentException("Song index is out of range")
                            service.addSong(song)
                            true
                        }
                        else -> {
                            service.control(action, body)
                            false
                        }
                    }
                    json(service.stateJson(includeQueue = queueChanged))
                }

                session.uri == "/api/queue" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    service.queueAction(requiredString(body, "action"), body)
                    json(service.stateJson())
                }

                session.uri == "/api/downloads" && session.method == Method.GET ->
                    json(service.downloads().statusJson())

                session.uri == "/api/download" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    val playlistId = requiredString(body, "playlistId")
                    val playlist = service.librarySync().loadPlaylist(
                        playlistId,
                        preferRemote = true
                    )
                    service.downloads().start(playlist)
                    json(service.downloads().statusJson(), Response.Status.ACCEPTED)
                }

                session.uri == "/api/download/remove" && session.method == Method.POST -> {
                    val body = parseJsonBody(session)
                    service.downloads().cancelAndRemove(requiredString(body, "playlistId"))
                    json(service.downloads().statusJson(), Response.Status.ACCEPTED)
                }

                session.uri.startsWith("/api/") -> methodNotAllowedOrNotFound(session)
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        } catch (error: Exception) {
            errorResponse(error)
        }
    }

    fun broadcastSnapshot() = enqueueEvent("snapshot") { service.bootstrapJson() }

    fun broadcastPlayback() = enqueueEvent("playback") { service.playbackEventJson() }

    fun broadcastLibrary() = enqueueEvent("library") { service.playlistsJson() }

    fun broadcastDownloads() = enqueueEvent("downloads") { service.downloads().statusJson() }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        pendingEvents.clear()
        eventExecutor.shutdownNow()
        requestExecutor.shutdownNow()
        val openSockets = synchronized(socketAdmissionLock) {
            sockets.toList().also { sockets.clear() }
        }
        openSockets.forEach { socket ->
            runCatching {
                socket.close(
                    WebSocketFrame.CloseCode.GoingAway,
                    "TailTune server stopped",
                    false
                )
            }
        }
        super.stop()
    }

    /** Keep only the newest unsent event of each type. */
    private fun enqueueEvent(type: String, factory: () -> JSONObject) {
        if (stopped.get() || sockets.isEmpty()) return
        pendingEvents[type] = factory
        if (!eventDrainScheduled.compareAndSet(false, true)) return
        try {
            eventExecutor.execute(::drainEvents)
        } catch (_: RejectedExecutionException) {
            eventDrainScheduled.set(false)
        }
    }

    private fun drainEvents() {
        try {
            while (!stopped.get()) {
                val next = pendingEvents.entries.firstOrNull() ?: break
                if (!pendingEvents.remove(next.key, next.value)) continue
                val payload = runCatching {
                    event(next.key, next.value()).toString()
                }.getOrNull() ?: continue

                sockets.forEach { socket ->
                    if (!socket.safeSend(payload)) sockets.remove(socket)
                }
            }
        } finally {
            eventDrainScheduled.set(false)
            if (pendingEvents.isNotEmpty() && !stopped.get()) {
                enqueueEventDrainOnly()
            }
        }
    }

    private fun enqueueEventDrainOnly() {
        if (!eventDrainScheduled.compareAndSet(false, true)) return
        try {
            eventExecutor.execute(::drainEvents)
        } catch (_: RejectedExecutionException) {
            eventDrainScheduled.set(false)
        }
    }

    private fun event(type: String, data: JSONObject): JSONObject = JSONObject()
        .put("type", type)
        .put("data", data)
        .put("timestamp", System.currentTimeMillis())

    private fun playlistJson(playlist: RemotePlaylist): JSONObject {
        val localFiles = service.getOfflineStore().localFiles(
            playlist.songs.map(RemoteSong::id)
        )
        val songs = JSONArray()
        playlist.songs.forEachIndexed { index, song ->
            songs.put(
                song.toJson(index)
                    .put("offlineAvailable", song.id in localFiles)
            )
        }
        val status = service.getOfflineStore().getStatus(playlist.summary.id)
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
        validateInput(
            session.parameters[key]?.firstOrNull(),
            "query parameter: $key",
            MAX_IDENTIFIER_LENGTH
        )

    private fun requiredString(
        body: JSONObject,
        key: String,
        maxLength: Int = MAX_IDENTIFIER_LENGTH
    ): String {
        if (!body.has(key) || body.isNull(key)) {
            throw IllegalArgumentException("Missing field: $key")
        }
        val value = body.opt(key)
        require(value is String) { "field: $key must be a string" }
        return validateInput(value, "field: $key", maxLength)
    }

    private fun requiredInt(body: JSONObject, key: String): Int {
        if (!body.has(key) || body.isNull(key)) {
            throw IllegalArgumentException("Missing field: $key")
        }
        return exactLong(body.opt(key), "field: $key").also {
            require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                "field: $key is out of range"
            }
        }.toInt()
    }

    private fun optionalInt(body: JSONObject, key: String, defaultValue: Int): Int {
        if (!body.has(key) || body.isNull(key)) return defaultValue
        return requiredInt(body, key)
    }


    private fun optionalBoolean(body: JSONObject, key: String, defaultValue: Boolean): Boolean {
        if (!body.has(key) || body.isNull(key)) return defaultValue
        val value = body.opt(key)
        require(value is Boolean) { "field: $key must be a boolean" }
        return value
    }

    private fun exactLong(value: Any?, label: String): Long {
        return when (value) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$label must be an integer")
        }
    }

    private fun validateInput(value: String?, label: String, maxLength: Int): String {
        val normalized = value?.trim()?.takeIf(String::isNotEmpty)
            ?: throw IllegalArgumentException("Missing $label")
        require(normalized.length <= maxLength) { "$label is too long" }
        require(normalized.none { it.isISOControl() }) { "$label contains control characters" }
        return normalized
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        val announcedLength = session.headers["content-length"]?.toLongOrNull()
        if (announcedLength != null && announcedLength > MAX_REQUEST_BODY_BYTES) {
            throw PayloadTooLargeException("Request body is too large")
        }
        val files = HashMap<String, String>()
        session.parseBody(files)
        val raw = files["postData"].orEmpty()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_REQUEST_BODY_BYTES) {
            throw PayloadTooLargeException("Request body is too large")
        }
        return if (raw.isBlank()) JSONObject() else JSONObject(raw)
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        val headerToken = session.headers["x-tailtune-token"]
            ?.takeIf { it.isNotBlank() }


        val parsedQueryToken = session.parameters["token"]
            ?.firstOrNull()
            ?.takeIf { it.isNotBlank() }

        val rawQueryToken = queryParameter(
            session.queryParameterString,
            "token"
        )

        val suppliedToken =
            headerToken
                ?: parsedQueryToken
                ?: rawQueryToken

        return RemoteAccessToken.matches(service.applicationContext, suppliedToken)
    }

    private fun queryParameter(
        query: String?,
        key: String
    ): String? {
        if (query.isNullOrBlank()) return null

        return query
            .split('&')
            .asSequence()
            .mapNotNull { part ->
                val separator = part.indexOf('=')

                if (separator <= 0) {
                    return@mapNotNull null
                }

                val name = part.substring(0, separator)

                if (name != key) {
                    return@mapNotNull null
                }

                part.substring(separator + 1)
                    .takeIf { it.isNotBlank() }
            }
            .firstOrNull()
    }

    private fun secureEquals(expected: String, supplied: String?): Boolean {
        if (supplied == null || supplied.length != expected.length) return false
        return java.security.MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            supplied.toByteArray(Charsets.UTF_8)
        )
    }

    private fun asset(path: String, mime: String, cache: Boolean): Response =
        newChunkedResponse(Response.Status.OK, mime, service.assets.open(path)).apply {
            addHeader(
                "Cache-Control",
                if (cache) "public, max-age=86400, immutable"
                else "no-cache, no-store, must-revalidate"
            )
            addSecurityHeaders(contentSecurityPolicy = path.endsWith("index.html"))
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
        addSecurityHeaders()
    }

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message).apply {
            addHeader("Cache-Control", "no-store")
            addSecurityHeaders()
        }

    private fun unauthorized(): Response = json(
        JSONObject().put(
            "error",
            "Remote authorization is required. Open the secure URL shown in the TailTune Android app."
        ),
        Response.Status.UNAUTHORIZED
    )

    private fun preflight(): Response = text(Response.Status.NO_CONTENT, "").apply {
        addHeader("Allow", "GET, POST, OPTIONS")
    }

    private fun methodNotAllowedOrNotFound(session: IHTTPSession): Response {
        val known = session.uri in setOf(
            "/api/bootstrap", "/api/playlists", "/api/playlist", "/api/state",
            "/api/sync", "/api/control", "/api/queue", "/api/downloads",
            "/api/download", "/api/download/remove", "/api/health"
        )
        return if (known) {
            text(Response.Status.METHOD_NOT_ALLOWED, "Method not allowed").apply {
                addHeader("Allow", allowedMethods(session.uri))
            }
        } else {
            text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    private fun allowedMethods(uri: String): String = when (uri) {
        "/api/bootstrap", "/api/playlists", "/api/playlist", "/api/state",
        "/api/downloads", "/api/health" -> "GET"
        else -> "POST"
    }

    private fun errorResponse(error: Throwable): Response {
        val root = ErrorSanitizer.rootCause(error)
        val status = when (root) {
            is PayloadTooLargeException -> Response.Status.PAYLOAD_TOO_LARGE
            is IllegalArgumentException, is JSONException -> Response.Status.BAD_REQUEST
            is ServiceUnavailableException, is IOException -> Response.Status.SERVICE_UNAVAILABLE
            is IllegalStateException -> Response.Status.CONFLICT
            else -> Response.Status.INTERNAL_ERROR
        }
        val message = ErrorSanitizer.message(root, MAX_ERROR_MESSAGE_LENGTH)
        return json(JSONObject().put("error", message), status)
    }

    private fun Response.addSecurityHeaders(contentSecurityPolicy: Boolean = false) {
        addHeader("X-Content-Type-Options", "nosniff")
        addHeader("X-Frame-Options", "DENY")
        addHeader("Referrer-Policy", "no-referrer")
        addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
        addHeader("Cross-Origin-Resource-Policy", "same-origin")
        addHeader("X-Robots-Tag", "noindex, nofollow, noarchive")
        if (contentSecurityPolicy) {
            addHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self'; " +
                    "connect-src 'self' ws: wss:; img-src 'self' data:; " +
                    "object-src 'none'; base-uri 'none'; frame-ancestors 'none'"
            )
        }
    }

    private inner class RemoteSocket(
        handshake: IHTTPSession
    ) : WebSocket(handshake) {

        @Volatile
        private var authorized = false

        private val sendLock = Any()

        fun safeSend(payload: String): Boolean = synchronized(sendLock) {
            runCatching {
                if (stopped.get()) return@synchronized false
                send(payload)
                true
            }.getOrDefault(false)
        }

        override fun onOpen() {
            android.util.Log.i(
                "TailTune-Remote",
                "WebSocket opened; waiting for authentication"
            )
        }

        private fun authenticate(message: WebSocketFrame) {
            if (
                message.opCode != WebSocketFrame.OpCode.Text ||
                message.binaryPayload.size > MAX_WEBSOCKET_REQUEST_BYTES
            ) {
                rejectAuthorization()
                return
            }

            val request = runCatching {
                JSONObject(message.textPayload)
            }.getOrNull()

            val suppliedToken = request
                ?.takeIf { it.optString("type") == "auth" }
                ?.optString("token")
                ?.takeIf { it.isNotBlank() }

            android.util.Log.i(
                "TailTune-Remote",
                "WS auth diagnostic: " +
                    "messageType=${request?.optString("type")} " +
                    "suppliedLength=${suppliedToken?.length ?: -1} " +
                    "expectedLength=${accessToken.length}"
            )

            if (!RemoteAccessToken.matches(service.applicationContext, suppliedToken)) {
                android.util.Log.w(
                    "TailTune-Remote",
                    "WebSocket first-message authentication rejected"
                )

                rejectAuthorization()
                return
            }

            val admitted = synchronized(socketAdmissionLock) {
                if (
                    stopped.get() ||
                    sockets.size >= MAX_WEBSOCKET_CLIENTS
                ) {
                    false
                } else {
                    sockets += this
                    true
                }
            }

            if (!admitted) {
                runCatching {
                    close(
                        WebSocketFrame.CloseCode.PolicyViolation,
                        if (stopped.get()) {
                            "TailTune server is stopping"
                        } else {
                            "Too many remote clients"
                        },
                        false
                    )
                }
                return
            }

            authorized = true

            android.util.Log.i(
                "TailTune-Remote",
                "WebSocket client authenticated"
            )

            if (
                !safeSend(
                    JSONObject()
                        .put("type", "authorization_ok")
                        .toString()
                )
            ) {
                sockets -= this
                return
            }

            if (
                !submitSocketTask {
                    if (
                        !safeSend(
                            event(
                                "snapshot",
                                service.bootstrapJson()
                            ).toString()
                        )
                    ) {
                        sockets -= this

                        runCatching {
                            close(
                                WebSocketFrame.CloseCode.InternalServerError,
                                "Snapshot failed",
                                false
                            )
                        }
                    }
                }
            ) {
                sockets -= this

                runCatching {
                    close(
                        WebSocketFrame.CloseCode.PolicyViolation,
                        "TailTune is busy",
                        false
                    )
                }
            }
        }

        private fun rejectAuthorization() {
            runCatching {
                safeSend(
                    JSONObject()
                        .put("type", "authorization_error")
                        .put(
                            "error",
                            "Remote authorization is required"
                        )
                        .toString()
                )

                close(
                    WebSocketFrame.CloseCode.PolicyViolation,
                    "Remote authorization is required",
                    false
                )
            }
        }

        override fun onClose(
            code: WebSocketFrame.CloseCode?,
            reason: String?,
            initiatedByRemote: Boolean
        ) {
            sockets -= this
        }

        override fun onMessage(message: WebSocketFrame) {
            android.util.Log.i(
                "TailTune-Remote",
                "WebSocket message received; authenticated=$authorized"
            )

            if (stopped.get()) return

            if (!authorized) {
                authenticate(message)
                return
            }
if (message.opCode != WebSocketFrame.OpCode.Text) {
                sendSocketError(null, "Only JSON text messages are supported")
                return
            }
            if (message.binaryPayload.size > MAX_WEBSOCKET_REQUEST_BYTES) {
                sendSocketError(null, "WebSocket request is too large")
                return
            }
            val raw = runCatching { message.textPayload }.getOrNull().orEmpty()
            if (raw.isBlank()) {
                sendSocketError(null, "WebSocket request is empty")
                return
            }
            if (!submitSocketTask { handleSocketRequest(raw) }) {
                sendSocketError(null, "TailTune is busy; try again")
            }
        }

        private fun submitSocketTask(block: () -> Unit): Boolean = try {
            requestExecutor.execute {
                try {
                    block()
                } catch (error: Exception) {
                    // Never allow a bad database row, malformed response,
                    // storage failure or other recoverable request exception
                    // to kill the entire TailTune Android process.
                    android.util.Log.e(
                        "TailTune-Remote",
                        "WebSocket worker request failed",
                        error
                    )

                    runCatching {
                        safeSend(
                            JSONObject()
                                .put("type", "server_error")
                                .put(
                                    "error",
                                    "TailTune encountered an internal error"
                                )
                                .toString()
                        )
                    }
                }
            }

            true
        } catch (_: RejectedExecutionException) {
            false
        }

        private fun handleSocketRequest(raw: String) {
            var requestId: String? = null
            try {
                val request = JSONObject(raw)
                require(requiredString(request, "type", 32) == "request") {
                    "WebSocket message type must be request"
                }
                requestId = requiredString(request, "requestId", 128)
                val operation = requiredString(request, "operation", 64)

                val data = when (operation) {
                    "snapshot" -> service.bootstrapJson()
                    "playlist" -> {
                        val playlist = service.librarySync().loadPlaylist(
                            requiredString(request, "playlistId")
                        )
                        playlistJson(playlist)
                    }
                    "playPlaylist" -> {
                        val playlist = service.librarySync().loadPlaylist(
                            requiredString(request, "playlistId")
                        )
                        val startIndex = optionalInt(request, "startIndex", 0)
                        require(startIndex >= 0) { "startIndex cannot be negative" }
                        service.playPlaylist(playlist, startIndex)
                        service.stateJson(includeQueue = true)
                    }
                    "control" -> {
                        service.control(requiredString(request, "action", MAX_ACTION_LENGTH), request)
                        service.stateJson(includeQueue = false)
                    }
                    "queue" -> {
                        service.queueAction(requiredString(request, "action", MAX_ACTION_LENGTH), request)
                        service.stateJson(includeQueue = true)
                    }
                    "sync" -> {
                        service.librarySync().start(
                            fullRefresh = optionalBoolean(request, "full", false)
                        )
                        service.playlistsJson()
                    }
                    "download" -> {
                        val playlist = service.librarySync().loadPlaylist(
                            requiredString(request, "playlistId"),
                            preferRemote = true
                        )
                        service.downloads().start(playlist)
                        service.downloads().statusJson()
                    }
                    "removeDownload" -> {
                        service.downloads().cancelAndRemove(
                            requiredString(request, "playlistId")
                        )
                        service.downloads().statusJson()
                    }
                    "ping" -> JSONObject().put("pong", System.currentTimeMillis())
                    else -> throw IllegalArgumentException("Unknown WebSocket operation: $operation")
                }

                sendSocketResponse(requestId, data)
            } catch (error: Throwable) {
                sendSocketError(requestId, ErrorSanitizer.message(error, MAX_ERROR_MESSAGE_LENGTH))
            }
        }

        private fun sendSocketResponse(requestId: String, data: JSONObject) {
            val payload = JSONObject()
                .put("type", "response")
                .put("requestId", requestId)
                .put("ok", true)
                .put("data", data)
                .toString()
            if (!safeSend(payload)) sockets -= this
        }

        private fun sendSocketError(requestId: String?, message: String) {
            val payload = JSONObject()
                .put("type", "response")
                .put("requestId", requestId ?: JSONObject.NULL)
                .put("ok", false)
                .put("error", message.take(MAX_ERROR_MESSAGE_LENGTH))
                .toString()
            if (!safeSend(payload)) sockets -= this
        }

        override fun onPong(pong: WebSocketFrame) = Unit

        override fun onException(exception: IOException) {
            sockets -= this
        }
    }

    private class PayloadTooLargeException(message: String) : IllegalArgumentException(message)

    companion object {
        private const val MAX_REQUEST_BODY_BYTES = 64 * 1024L
        private const val MAX_ERROR_MESSAGE_LENGTH = 500
        private const val MAX_IDENTIFIER_LENGTH = 2_048
        private const val MAX_ACTION_LENGTH = 64
        private const val MAX_WEBSOCKET_CLIENTS = 8
        private const val MAX_WEBSOCKET_REQUEST_BYTES = 64 * 1024
        private const val SOCKET_REQUEST_THREADS = 2
        private const val MAX_PENDING_SOCKET_REQUESTS = 64
    }
}
