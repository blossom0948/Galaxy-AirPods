package com.galaxyairpods.data.bluetooth

/** Result of parsing one or more reads from a Classic AAP channel. */
internal data class AapAccumulatedFrames(
    val frames: List<ByteArray>,
    val malformedRecords: Int,
)

/**
 * Bounded accumulator for adapters that expose the L2CAP channel with stream
 * semantics. Known AAP commands have deterministic lengths; unknown commands
 * are rejected because no safe boundary can be inferred for them.
 */
internal class AapFrameAccumulator(
    private val maxBufferBytes: Int = 8 * 1024,
) {
    private var buffer = ByteArray(0)

    fun reset() {
        buffer = ByteArray(0)
    }

    fun append(bytes: ByteArray): AapAccumulatedFrames {
        if (bytes.isEmpty()) return AapAccumulatedFrames(emptyList(), 0)

        // Android's Classic BluetoothSocket commonly preserves the L2CAP
        // record boundary.  Prefer that boundary when the complete read is a
        // single packet.  In particular, an unknown notification must not be
        // scanned byte-by-byte: doing so can manufacture false AAP headers
        // and status values from its payload.
        if (buffer.isEmpty()) {
            val directLength = expectedLength(bytes)
            if (directLength == bytes.size) {
                return AapAccumulatedFrames(listOf(bytes.copyOf()), 0)
            }
            if (isMalformedKnownFrame(bytes)) {
                return AapAccumulatedFrames(emptyList(), 1)
            }
            if (directLength < 0 && hasPacketHeader(bytes)) {
                return AapAccumulatedFrames(listOf(bytes.copyOf()), 0)
            }
        }

        if (buffer.size + bytes.size > maxBufferBytes) {
            buffer = ByteArray(0)
            return AapAccumulatedFrames(emptyList(), 1)
        }
        buffer += bytes

        val frames = mutableListOf<ByteArray>()
        var malformedRecords = 0
        while (buffer.size >= HEADER_SIZE) {
            val expectedLength = expectedLength(buffer)
            if (expectedLength < 0) {
                buffer = buffer.copyOfRange(1, buffer.size)
                malformedRecords++
                continue
            }
            if (expectedLength == 0 || buffer.size < expectedLength) break
            frames += buffer.copyOf(expectedLength)
            buffer = buffer.copyOfRange(expectedLength, buffer.size)
        }
        return AapAccumulatedFrames(frames, malformedRecords)
    }

    private fun hasPacketHeader(bytes: ByteArray): Boolean {
        if (bytes.size < HEADER_SIZE) return false
        val packetType = littleEndian16(bytes, 0)
        return packetType in PLAUSIBLE_PACKET_TYPES
    }

    private fun isMalformedKnownFrame(bytes: ByteArray): Boolean {
        if (bytes.size < BATTERY_COUNT_OFFSET + 1) return false
        if (littleEndian16(bytes, 0) != MESSAGE_PACKET_TYPE) return false
        if (littleEndian16(bytes, 4) != AapBatteryProtocol.BATTERY_COMMAND) return false
        return bytes[BATTERY_COUNT_OFFSET].u8() > MAX_COMPONENTS
    }

    private fun expectedLength(bytes: ByteArray): Int {
        val packetType = littleEndian16(bytes, 0)
        return when (packetType) {
            CONNECT_RESPONSE_PACKET_TYPE -> CONNECT_RESPONSE_LENGTH
            MESSAGE_PACKET_TYPE -> {
                if (bytes.size < MESSAGE_HEADER_SIZE) return 0
                val command = littleEndian16(bytes, 4)
                when (command) {
                    AapBatteryProtocol.BATTERY_COMMAND -> {
                        if (bytes.size < BATTERY_COUNT_OFFSET + 1) return 0
                        val count = bytes[BATTERY_COUNT_OFFSET].u8()
                        if (count > MAX_COMPONENTS) -1
                        else BATTERY_HEADER_SIZE + count * COMPONENT_SIZE
                    }
                    AapBatteryProtocol.EAR_DETECTION_COMMAND -> EAR_MESSAGE_LENGTH
                    else -> -1
                }
            }
            // There is no safe length field for an unknown command. Treat it
            // as malformed and resynchronise instead of feeding arbitrary
            // bytes to the battery/ear parsers.
            else -> -1
        }
    }

    private fun littleEndian16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].u8()) or (bytes[offset + 1].u8() shl 8)

    private fun Byte.u8(): Int = toInt() and 0xFF

    private companion object {
        const val HEADER_SIZE = 4
        const val MESSAGE_HEADER_SIZE = 6
        const val CONNECT_RESPONSE_PACKET_TYPE = 0x0001
        const val CONNECT_RESPONSE_LENGTH = 18
        const val BATTERY_COUNT_OFFSET = 6
        const val BATTERY_HEADER_SIZE = 7
        const val COMPONENT_SIZE = 5
        const val MAX_COMPONENTS = 64
        const val EAR_MESSAGE_LENGTH = 8
        const val MESSAGE_PACKET_TYPE = 0x0004
        val PLAUSIBLE_PACKET_TYPES = 0..4
    }
}
