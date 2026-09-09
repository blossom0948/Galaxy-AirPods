package com.galaxyairpods.data.bluetooth

/**
 * Small, strict AAP (AirPods Accessory Protocol) codec used by the classic
 * BR/EDR probe.  This file deliberately contains no socket or Android code so
 * the wire parser can be tested without a device.
 */
internal object AapBatteryProtocol {
    const val CLASSIC_PSM = 0x1001
    const val MESSAGE_PACKET_TYPE = 0x0004
    const val BATTERY_COMMAND = 0x0004
    const val EAR_DETECTION_COMMAND = 0x0006

    val handshake: ByteArray = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    val keyRequest: ByteArray = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x30, 0x00, 0x05, 0x00,
    )

    /** Default notification profile, sent once after the connect response. */
    val notificationProfiles: List<Pair<String, ByteArray>> = listOf(
        "FF" to byteArrayOf(
            0x04, 0x00, 0x04, 0x00, 0x0F, 0x00,
            // FF FF FE FF is the AACP subscribe-all mask.  FF FF FF FF is
            // accepted by some firmware as a no-op but does not reliably
            // enable 0x0006 ear-detection notifications on Samsung routes.
            0xFF.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0xFF.toByte(),
        ),
    )

    fun parseFrame(raw: ByteArray): AapFrame? {
        if (raw.size < 4) return null
        val packetType = littleEndian16(raw, 0)
        val service = littleEndian16(raw, 2)
        return when (packetType) {
            0x0001 -> {
                if (raw.size < 18) return null
                AapFrame.ConnectResponse(
                    length = raw.size,
                    service = service,
                    status = littleEndian16(raw, 4),
                )
            }
            MESSAGE_PACKET_TYPE -> {
                if (raw.size < 6) return null
                AapFrame.Message(
                    length = raw.size,
                    command = littleEndian16(raw, 4),
                    payload = raw.copyOfRange(6, raw.size),
                )
            }
            else -> AapFrame.Other(packetType = packetType, length = raw.size)
        }
    }

    /**
     * Decodes command 0x0004 exactly.  Unknown components are ignored, but a
     * malformed frame or invalid percentage is rejected in full rather than
     * being turned into a guessed battery value.
     */
    fun parseBattery(frame: AapFrame.Message): AapBatterySnapshot? {
        if (frame.command != BATTERY_COMMAND) return null
        val payload = frame.payload
        if (payload.isEmpty()) return null

        val count = payload[0].u8()
        if (count > MAX_COMPONENTS) return null
        val expectedPayloadLength = 1 + count * COMPONENT_SIZE
        if (payload.size != expectedPayloadLength) return null

        var left: AapBatteryReading? = null
        var right: AapBatteryReading? = null
        var case: AapBatteryReading? = null
        var offset = 1
        repeat(count) {
            val component = payload[offset].u8()
            val percent = payload[offset + 2].u8()
            val status = payload[offset + 3].u8()
            offset += COMPONENT_SIZE

            // 0x7F and 0xFF are observed invalid/disconnected markers; all
            // values over 100 are invalid for a percentage.
            if (percent > 100 || percent == 0x7F || percent == 0xFF) return@repeat
            if (status !in VALID_STATUSES) return@repeat
            val reading = AapBatteryReading(percent, status.toCharging())
            when (component) {
                COMPONENT_LEFT -> left = reading
                COMPONENT_RIGHT -> right = reading
                COMPONENT_CASE -> case = reading
                // 0x01 means one/unknown earbud. It must not be copied to L/R.
            }
        }

        if (left == null && right == null && case == null) return null
        return AapBatterySnapshot(
            left = left,
            right = right,
            case = case,
            componentCount = count,
        )
    }

    /**
     * AAP ear detection is a fixed two-byte payload after the message header:
     * primary pod status, then secondary pod status. The public observation is
     * a variable-length stream in practice, so accept only the exact frame
     * length after the message parser has validated the header.
     */
    fun parseEarDetection(frame: AapFrame.Message): AapEarDetectionSnapshot? {
        if (frame.command != EAR_DETECTION_COMMAND || frame.payload.size != 2) return null
        val primary = parseEarStatus(frame.payload[0].u8()) ?: return null
        val secondary = parseEarStatus(frame.payload[1].u8()) ?: return null
        return AapEarDetectionSnapshot(primary = primary, secondary = secondary)
    }

    private fun parseEarStatus(value: Int): AapEarStatus? = when (value) {
        0x00 -> AapEarStatus.IN_EAR
        0x01 -> AapEarStatus.OUT_OF_EAR
        0x02 -> AapEarStatus.IN_CASE
        else -> null
    }

    private fun Int.toCharging(): Boolean? = when (this) {
        STATUS_CHARGING, STATUS_OPTIMIZED -> true
        STATUS_NOT_CHARGING, STATUS_DISCONNECTED -> false
        STATUS_UNKNOWN -> null
        else -> null
    }

    private fun littleEndian16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].u8()) or (bytes[offset + 1].u8() shl 8)

    private fun Byte.u8(): Int = toInt() and 0xFF

    private const val MAX_COMPONENTS = 64
    private const val COMPONENT_SIZE = 5
    private const val COMPONENT_LEFT = 0x04
    private const val COMPONENT_RIGHT = 0x02
    private const val COMPONENT_CASE = 0x08
    private const val STATUS_UNKNOWN = 0x00
    private const val STATUS_CHARGING = 0x01
    private const val STATUS_NOT_CHARGING = 0x02
    private const val STATUS_DISCONNECTED = 0x04
    private const val STATUS_OPTIMIZED = 0x05
    private val VALID_STATUSES = setOf(
        STATUS_UNKNOWN,
        STATUS_CHARGING,
        STATUS_NOT_CHARGING,
        STATUS_DISCONNECTED,
        STATUS_OPTIMIZED,
    )
}

internal sealed interface AapFrame {
    val length: Int

    data class ConnectResponse(
        override val length: Int,
        val service: Int,
        val status: Int,
    ) : AapFrame

    data class Message(
        override val length: Int,
        val command: Int,
        val payload: ByteArray,
    ) : AapFrame

    data class Other(
        val packetType: Int,
        override val length: Int,
    ) : AapFrame
}

internal data class AapBatteryReading(
    val percent: Int,
    val charging: Boolean?,
)

internal data class AapBatterySnapshot(
    val left: AapBatteryReading?,
    val right: AapBatteryReading?,
    val case: AapBatteryReading?,
    val componentCount: Int,
) {
    val valuesPresent: Boolean
        get() = left != null || right != null || case != null
}

internal enum class AapEarStatus {
    IN_EAR,
    OUT_OF_EAR,
    IN_CASE,
}

internal data class AapEarDetectionSnapshot(
    val primary: AapEarStatus,
    val secondary: AapEarStatus,
)
