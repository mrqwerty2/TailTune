package dev.tailtune.remote.ble

import java.util.UUID

object TailTuneBleConstants {
    val SERVICE_UUID: UUID = UUID.fromString("73F1B100-7A11-4A6A-9B42-54A14C9E0001")
    val RX_UUID: UUID = UUID.fromString("73F1B100-7A11-4A6A-9B42-54A14C9E0002")
    val TX_UUID: UUID = UUID.fromString("73F1B100-7A11-4A6A-9B42-54A14C9E0003")
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val PROTOCOL_VERSION = 1
    const val FRAME_HEADER_BYTES = 12
    const val MAX_MESSAGE_BYTES = 512 * 1024
    const val MAX_CHUNKS = 4096
    const val ASSEMBLY_TIMEOUT_MS = 15_000L
    const val MAX_ACTIVE_ASSEMBLIES = 16
}
