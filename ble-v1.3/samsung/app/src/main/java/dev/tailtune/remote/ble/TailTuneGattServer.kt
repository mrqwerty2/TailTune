package dev.tailtune.remote.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONObject
import kotlin.math.max

/**
 * BLE peripheral transport for TailTune.
 *
 * Important: Bluetooth binder callbacks only validate/copy packets and send the GATT acknowledgement.
 * JSON/library/player work runs on a dedicated single-thread worker to prevent ANRs and Bluetooth stalls.
 */
class TailTuneGattServer(
    context: Context,
    api: TailTuneBleApi,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val router = TailTuneBleRpcRouter(api)
    private val worker = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(256),
        { runnable -> Thread(runnable, "TailTune-BLE-RPC").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    private fun submit(work: () -> Unit) {
        if (closed.get()) return
        try { worker.execute(work) }
        catch (_: RejectedExecutionException) {
            Log.w(TAG, "BLE work queue full or stopped: dropping packet/event")
        }
    }
    private val closed = AtomicBoolean(false)
    // Requests use 0x00000001...0x7fffffff. Server-pushed events use the high-bit namespace,
    // preventing interleaved response/event fragments from sharing an assembler key on iOS.
    private val nextEventMessageId = AtomicLong(0x8000_0000L)

    private var server: BluetoothGattServer? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null
    private val clients = linkedMapOf<String, ClientState>()

    private data class ClientState(
        val device: BluetoothDevice,
        val assembler: TailTuneBleMessageAssembler = TailTuneBleMessageAssembler(),
        var mtu: Int = 23,
        var subscribed: Boolean = false,
        val outgoing: ArrayDeque<ByteArray> = ArrayDeque(),
        var indicationInFlight: Boolean = false,
    )

    sealed class StartResult {
        data object Started : StartResult()
        data class Unavailable(val reason: String) : StartResult()
    }

    @SuppressLint("MissingPermission")
    fun start(): StartResult {
        if (closed.get()) return StartResult.Unavailable("BLE server is closed")
        val btAdapter = adapter ?: return StartResult.Unavailable("Bluetooth adapter unavailable")
        if (!btAdapter.isEnabled) return StartResult.Unavailable("Bluetooth is disabled")
        if (!hasConnectPermission() || !hasAdvertisePermission()) {
            return StartResult.Unavailable("Bluetooth permissions are not granted")
        }
        if (!btAdapter.isMultipleAdvertisementSupported || btAdapter.bluetoothLeAdvertiser == null) {
            return StartResult.Unavailable("This Android Bluetooth stack does not support BLE peripheral advertising")
        }
        if (server != null) return StartResult.Started

        val opened = manager?.openGattServer(appContext, callback)
            ?: return StartResult.Unavailable("Could not open GATT server")

        val service = BluetoothGattService(
            TailTuneBleConstants.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val rx = BluetoothGattCharacteristic(
            TailTuneBleConstants.RX_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val tx = BluetoothGattCharacteristic(
            TailTuneBleConstants.TX_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        tx.addDescriptor(
            BluetoothGattDescriptor(
                TailTuneBleConstants.CCCD_UUID,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            )
        )
        service.addCharacteristic(rx)
        service.addCharacteristic(tx)
        server = opened
        txCharacteristic = tx
        if (!opened.addService(service)) {
            server = null
            txCharacteristic = null
            opened.close()
            return StartResult.Unavailable("Could not register GATT service")
        }
        Log.i(TAG, "TailTune BLE GATT server starting")
        return StartResult.Started
    }

    fun broadcastState(snapshot: JSONObject) = broadcastEvent("state", snapshot)

    fun broadcastQueueChanged() = broadcastEvent("queue_changed", JSONObject())

    fun broadcastLibraryChanged() = broadcastEvent("library_changed", JSONObject())

    fun broadcastEvent(name: String, data: JSONObject) {
        if (closed.get() || name.length > 64) return
        val message = JSONObject()
            .put("v", TailTuneBleConstants.PROTOCOL_VERSION)
            .put("event", name)
            .put("data", data)
            .toString()
            .toByteArray(Charsets.UTF_8)
        if (message.size > TailTuneBleConstants.MAX_MESSAGE_BYTES) return

        val messageId = allocateEventMessageId()
        submit {
            val states = synchronized(clients) { clients.values.toList() }
            states.forEach { state -> enqueueResponse(state, messageId, message) }
        }
    }

    private fun allocateEventMessageId(): Long {
        while (true) {
            val current = nextEventMessageId.getAndUpdate { value ->
                if (value >= 0xffff_ffffL) 0x8000_0000L else value + 1L
            }
            if (current in 0x8000_0000L..0xffff_ffffL) return current
        }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        synchronized(clients) {
            clients.values.forEach { state -> runCatching { server?.cancelConnection(state.device) } }
            clients.clear()
        }
        runCatching { server?.clearServices() }
        runCatching { server?.close() }
        server = null
        txCharacteristic = null
        worker.shutdownNow()
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val advertiser = adapter?.bluetoothLeAdvertiser ?: run {
            Log.e(TAG, "BLE advertising unavailable")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // Keep the primary advertisement intentionally small. The 128-bit service UUID is sufficient
        // for the iPhone's filtered scan and avoids ADVERTISE_FAILED_DATA_TOO_LARGE on older devices.
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(TailTuneBleConstants.SERVICE_UUID))
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .build()
        advertiser.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "TailTune BLE advertising")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "TailTune BLE advertise failed: $errorCode")
        }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (service.uuid != TailTuneBleConstants.SERVICE_UUID || closed.get()) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                startAdvertising()
            } else {
                Log.e(TAG, "GATT service registration failed: $status")
            }
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (closed.get()) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> synchronized(clients) {
                    clients[device.address] = ClientState(device)
                }
                BluetoothProfile.STATE_DISCONNECTED -> synchronized(clients) {
                    clients.remove(device.address)?.assembler?.reset()
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            synchronized(clients) { clients[device.address]?.mtu = mtu.coerceIn(23, 517) }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (characteristic.uuid != TailTuneBleConstants.RX_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            if (value.size < TailTuneBleConstants.FRAME_HEADER_BYTES || value.size > 517) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH, offset, null)
                return
            }

            val copied = value.copyOf()
            // Acknowledge the ATT write immediately; potentially slow JSON/DB work follows on worker.
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)

            submit {
                if (closed.get()) return@submit
                val state = synchronized(clients) { clients[device.address] } ?: return@submit
                try {
                    val complete = state.assembler.ingest(copied) ?: return@submit
                    val response = router.handle(complete.second)
                    enqueueResponse(state, complete.first, response)
                } catch (failure: Exception) {
                    Log.w(TAG, "Rejected BLE packet: ${failure.message}")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray,
        ) {
            if (descriptor.uuid != TailTuneBleConstants.CCCD_UUID || preparedWrite || offset != 0) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val enable = value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
            if (!enable && !value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, 0, null)
                return
            }
            synchronized(clients) {
                clients[device.address]?.let { state ->
                    synchronized(state) {
                        state.subscribed = enable
                        if (!enable) {
                            state.outgoing.clear()
                            // Keep in-flight state: onNotificationSent may still be outstanding.
                        }
                    }
                }
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            descriptor: BluetoothGattDescriptor,
        ) {
            if (descriptor.uuid != TailTuneBleConstants.CCCD_UUID || offset != 0) {
                server?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            val enabled = synchronized(clients) { clients[device.address]?.subscribed == true }
            val value = if (enabled) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            val state = synchronized(clients) { clients[device.address] } ?: return
            synchronized(state) {
                state.indicationInFlight = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    state.outgoing.clear()
                    return
                }
            }
            pumpIndications(state)
        }
    }

    private fun enqueueResponse(state: ClientState, messageId: Long, message: ByteArray) {
        val maxPacket = max(TailTuneBleConstants.FRAME_HEADER_BYTES + 1, state.mtu - 3)
        val frames = try {
            TailTuneBleFrameCodec.encodeMessage(message, messageId, maxPacket)
        } catch (failure: Exception) {
            Log.w(TAG, "Could not frame BLE response: ${failure.message}")
            return
        }
        synchronized(state) {
            if (!state.subscribed || closed.get()) return
            // Bound queue memory per client. One response can be up to MAX_MESSAGE_BYTES;
            // two full responses is enough headroom without allowing an unbounded slow-client queue.
            val queuedBytes = state.outgoing.sumOf { it.size }
            if (queuedBytes + frames.sumOf { it.size } > TailTuneBleConstants.MAX_MESSAGE_BYTES * 2) {
                // Do not clear indicationInFlight here: Android may still owe us onNotificationSent()
                // for the packet already handed to the controller. Only discard queued packets.
                state.outgoing.clear()
            }
            state.outgoing.addAll(frames)
        }
        pumpIndications(state)
    }

    @SuppressLint("MissingPermission")
    private fun pumpIndications(state: ClientState) {
        val gatt = server ?: return
        val characteristic = txCharacteristic ?: return
        val frame: ByteArray
        synchronized(state) {
            if (closed.get() || !state.subscribed || state.indicationInFlight) return
            frame = state.outgoing.pollFirst() ?: return
            state.indicationInFlight = true
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.notifyCharacteristicChanged(state.device, characteristic, true, frame) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = frame
            @Suppress("DEPRECATION")
            gatt.notifyCharacteristicChanged(state.device, characteristic, true)
        }
        if (!started) {
            synchronized(state) {
                state.indicationInFlight = false
                state.outgoing.clear()
            }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun hasAdvertisePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            appContext.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "TailTune-BLE"
    }
}
