package dev.tailtune.remote.ble

import org.json.JSONObject

class TailTuneBleRpcRouter(private val api: TailTuneBleApi) {
    fun handle(message: ByteArray): ByteArray {
        var requestId = 0L
        return try {
            if (message.size > TailTuneBleConstants.MAX_MESSAGE_BYTES) {
                throw RpcException("message_too_large", "Request is too large")
            }
            val request = JSONObject(message.toString(Charsets.UTF_8))
            val version = request.optInt("v", -1)
            if (version != TailTuneBleConstants.PROTOCOL_VERSION) {
                throw RpcException("bad_version", "Unsupported protocol version")
            }
            if (!request.has("id")) throw RpcException("missing_id", "Request id is required")
            requestId = request.getLong("id")
            if (requestId !in 0..0xffff_ffffL) throw RpcException("bad_id", "Invalid request id")
            val op = request.optString("op", "")
            if (op.isBlank()) throw RpcException("missing_op", "Operation is required")
            val args = request.optJSONObject("args") ?: JSONObject()

            val data = when (op) {
                "snapshot" -> api.snapshot()
                "queue" -> api.queue(offset(args), limit(args, 100))
                "list_playlists" -> api.listPlaylists(offset(args), limit(args, 100))
                "playlist_tracks" -> api.playlistTracks(requiredString(args, "id", 512), offset(args), limit(args, 200))
                "search" -> api.search(requiredString(args, "q", 512), offset(args), limit(args, 100))
                "control" -> {
                    val action = requiredString(args, "action", 64)
                    val allowed = setOf("play", "pause", "toggle", "next", "previous", "seek", "volume", "shuffle", "repeat")
                    if (action !in allowed) throw RpcException("bad_action", "Unsupported playback action")
                    val value = if (args.has("value") && !args.isNull("value")) args.getInt("value") else null
                    if (action == "seek" && value != null && value < 0) throw RpcException("bad_value", "Seek must be non-negative")
                    if (action == "volume" && value != null && value !in 0..100) throw RpcException("bad_value", "Volume must be 0-100")
                    api.control(action, value)
                }
                "play_playlist" -> api.playPlaylist(requiredString(args, "id", 512), args.optInt("index", 0).coerceAtLeast(0))
                "play_track" -> api.playTrack(requiredString(args, "id", 512))
                "queue_add" -> api.queueAdd(requiredString(args, "id", 512))
                else -> throw RpcException("unknown_op", "Unknown operation")
            }
            success(requestId, data).toString().toByteArray(Charsets.UTF_8)
        } catch (failure: RpcException) {
            error(requestId, failure.code, failure.message ?: "Request failed").toString().toByteArray(Charsets.UTF_8)
        } catch (failure: Exception) {
            // Never serialize stack traces, credentials, paths, or implementation details over BLE.
            error(requestId, "server_error", "Request failed")
                .toString().toByteArray(Charsets.UTF_8)
        }
    }

    private fun offset(args: JSONObject): Int = args.optInt("offset", 0).coerceIn(0, 100_000)

    private fun limit(args: JSONObject, defaultValue: Int): Int = args.optInt("limit", defaultValue).coerceIn(1, 200)

    private fun requiredString(args: JSONObject, key: String, maxLength: Int): String {
        val value = args.optString(key, "").trim()
        if (value.isEmpty()) throw RpcException("bad_argument", "$key is required")
        if (value.length > maxLength) throw RpcException("bad_argument", "$key is too long")
        return value
    }

    private fun success(id: Long, data: JSONObject): JSONObject = JSONObject()
        .put("v", TailTuneBleConstants.PROTOCOL_VERSION)
        .put("id", id)
        .put("ok", true)
        .put("data", data)

    private fun error(id: Long, code: String, message: String): JSONObject = JSONObject()
        .put("v", TailTuneBleConstants.PROTOCOL_VERSION)
        .put("id", id)
        .put("ok", false)
        .put("error", JSONObject().put("code", code).put("message", message))

    private class RpcException(val code: String, message: String) : Exception(message)
}
