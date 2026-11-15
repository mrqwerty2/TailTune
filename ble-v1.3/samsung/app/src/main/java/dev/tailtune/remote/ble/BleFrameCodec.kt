package dev.tailtune.remote.ble

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlin.math.ceil

class TailTuneBleProtocolException(message: String) : Exception(message)

data class TailTuneBleFrame(
    val messageId: Long,
    val chunkIndex: Int,
    val chunkCount: Int,
    val payload: ByteArray,
    val flags: Int = 0,
) {
    init {
        require(messageId in 0..0xffff_ffffL)
        require(chunkIndex in 0..0xffff)
        require(chunkCount in 1..0xffff)
        require(chunkIndex < chunkCount)
    }

    fun encode(): ByteArray {
        val buffer = ByteBuffer.allocate(TailTuneBleConstants.FRAME_HEADER_BYTES + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.put('T'.code.toByte())
        buffer.put('T'.code.toByte())
        buffer.put(TailTuneBleConstants.PROTOCOL_VERSION.toByte())
        buffer.put(flags.toByte())
        buffer.putInt(messageId.toInt())
        buffer.putShort(chunkIndex.toShort())
        buffer.putShort(chunkCount.toShort())
        buffer.put(payload)
        return buffer.array()
    }

    companion object {
        fun decode(packet: ByteArray): TailTuneBleFrame {
            if (packet.size < TailTuneBleConstants.FRAME_HEADER_BYTES) {
                throw TailTuneBleProtocolException("frame too short")
            }
            val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
            if (buffer.get() != 'T'.code.toByte() || buffer.get() != 'T'.code.toByte()) {
                throw TailTuneBleProtocolException("bad magic")
            }
            val version = buffer.get().toInt() and 0xff
            if (version != TailTuneBleConstants.PROTOCOL_VERSION) {
                throw TailTuneBleProtocolException("unsupported protocol version")
            }
            val flags = buffer.get().toInt() and 0xff
            if (flags != 0) throw TailTuneBleProtocolException("unsupported flags")
            val messageId = buffer.int.toLong() and 0xffff_ffffL
            val chunkIndex = buffer.short.toInt() and 0xffff
            val chunkCount = buffer.short.toInt() and 0xffff
            if (chunkCount == 0 || chunkCount > TailTuneBleConstants.MAX_CHUNKS || chunkIndex >= chunkCount) {
                throw TailTuneBleProtocolException("invalid chunk metadata")
            }
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            return TailTuneBleFrame(messageId, chunkIndex, chunkCount, payload, flags)
        }
    }
}

object TailTuneBleFrameCodec {
    fun encodeMessage(message: ByteArray, messageId: Long, maxPacketBytes: Int): List<ByteArray> {
        if (message.size > TailTuneBleConstants.MAX_MESSAGE_BYTES) {
            throw TailTuneBleProtocolException("message too large")
        }
        val payloadLimit = maxPacketBytes - TailTuneBleConstants.FRAME_HEADER_BYTES
        if (payloadLimit <= 0) throw TailTuneBleProtocolException("packet limit too small")

        val chunkCount = maxOf(1, ceil(message.size.toDouble() / payloadLimit.toDouble()).toInt())
        if (chunkCount > TailTuneBleConstants.MAX_CHUNKS || chunkCount > 0xffff) {
            throw TailTuneBleProtocolException("too many chunks")
        }

        return (0 until chunkCount).map { index ->
            val start = index * payloadLimit
            val end = minOf(start + payloadLimit, message.size)
            val payload = if (start < end) message.copyOfRange(start, end) else ByteArray(0)
            TailTuneBleFrame(messageId, index, chunkCount, payload).encode()
        }
    }
}

class TailTuneBleMessageAssembler(
    private val timeoutMs: Long = TailTuneBleConstants.ASSEMBLY_TIMEOUT_MS,
) {
    private data class Assembly(
        val chunkCount: Int,
        val chunks: Array<ByteArray?>,
        var receivedBytes: Int,
        var lastUpdateMs: Long,
    )

    private val assemblies = LinkedHashMap<Long, Assembly>()

    @Synchronized
    fun reset() = assemblies.clear()

    @Synchronized
    fun ingest(packet: ByteArray, nowMs: Long = monotonicMs()): Pair<Long, ByteArray>? {
        purgeExpired(nowMs)
        val frame = TailTuneBleFrame.decode(packet)

        var assembly = assemblies[frame.messageId]
        if (assembly == null) {
            if (assemblies.size >= TailTuneBleConstants.MAX_ACTIVE_ASSEMBLIES) {
                val oldest = assemblies.minByOrNull { it.value.lastUpdateMs }?.key
                if (oldest != null) assemblies.remove(oldest)
            }
            assembly = Assembly(
                chunkCount = frame.chunkCount,
                chunks = arrayOfNulls(frame.chunkCount),
                receivedBytes = 0,
                lastUpdateMs = nowMs,
            )
            assemblies[frame.messageId] = assembly
        }

        if (assembly.chunkCount != frame.chunkCount) {
            assemblies.remove(frame.messageId)
            throw TailTuneBleProtocolException("inconsistent chunk count")
        }

        val previous = assembly.chunks[frame.chunkIndex]
        if (previous != null) {
            if (!previous.contentEquals(frame.payload)) {
                assemblies.remove(frame.messageId)
                throw TailTuneBleProtocolException("conflicting duplicate chunk")
            }
        } else {
            val nextSize = assembly.receivedBytes + frame.payload.size
            if (nextSize > TailTuneBleConstants.MAX_MESSAGE_BYTES) {
                assemblies.remove(frame.messageId)
                throw TailTuneBleProtocolException("message too large")
            }
            assembly.chunks[frame.chunkIndex] = frame.payload
            assembly.receivedBytes = nextSize
        }
        assembly.lastUpdateMs = nowMs

        if (assembly.chunks.any { it == null }) return null

        val out = ByteArrayOutputStream(assembly.receivedBytes)
        assembly.chunks.forEach { chunk -> out.write(chunk ?: ByteArray(0)) }
        assemblies.remove(frame.messageId)
        return frame.messageId to out.toByteArray()
    }

    private fun purgeExpired(nowMs: Long) {
        val iterator = assemblies.entries.iterator()
        while (iterator.hasNext()) {
            if (nowMs - iterator.next().value.lastUpdateMs > timeoutMs) iterator.remove()
        }
    }

    private fun monotonicMs(): Long = System.nanoTime() / 1_000_000L
}
