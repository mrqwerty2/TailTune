package dev.tailtune.remote.ble

import org.json.JSONObject

/**
 * Thin adapter between BLE RPC and the existing TailTune playback/library layer.
 * Implementations must return JSON in the schema documented in PROTOCOL.md.
 * Calls are made on TailTuneGattServer's dedicated worker thread, never a Bluetooth binder thread.
 */
interface TailTuneBleApi {
    fun snapshot(): JSONObject
    fun queue(offset: Int, limit: Int): JSONObject
    fun listPlaylists(offset: Int, limit: Int): JSONObject
    fun playlistTracks(id: String, offset: Int, limit: Int): JSONObject
    fun search(query: String, offset: Int, limit: Int): JSONObject
    fun control(action: String, value: Int?): JSONObject
    fun playPlaylist(id: String, index: Int): JSONObject
    fun playTrack(id: String): JSONObject
    fun queueAdd(id: String): JSONObject
}

/** Useful for wiring existing PlaybackService functions without coupling the BLE package to internals. */
class CallbackTailTuneBleApi(
    private val snapshotBlock: () -> JSONObject,
    private val queueBlock: (Int, Int) -> JSONObject,
    private val playlistsBlock: (Int, Int) -> JSONObject,
    private val playlistTracksBlock: (String, Int, Int) -> JSONObject,
    private val searchBlock: (String, Int, Int) -> JSONObject,
    private val controlBlock: (String, Int?) -> JSONObject,
    private val playPlaylistBlock: (String, Int) -> JSONObject,
    private val playTrackBlock: (String) -> JSONObject,
    private val queueAddBlock: (String) -> JSONObject,
) : TailTuneBleApi {
    override fun snapshot() = snapshotBlock()
    override fun queue(offset: Int, limit: Int) = queueBlock(offset, limit)
    override fun listPlaylists(offset: Int, limit: Int) = playlistsBlock(offset, limit)
    override fun playlistTracks(id: String, offset: Int, limit: Int) = playlistTracksBlock(id, offset, limit)
    override fun search(query: String, offset: Int, limit: Int) = searchBlock(query, offset, limit)
    override fun control(action: String, value: Int?) = controlBlock(action, value)
    override fun playPlaylist(id: String, index: Int) = playPlaylistBlock(id, index)
    override fun playTrack(id: String) = playTrackBlock(id)
    override fun queueAdd(id: String) = queueAddBlock(id)
}
