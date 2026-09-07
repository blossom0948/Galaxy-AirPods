package com.galaxyairpods.data.bluetooth

import android.bluetooth.le.ScanResult
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import com.galaxyairpods.domain.model.isMax

interface AirPodsPacketParser {
    val parserVersion: String
    fun canParse(scanResult: ScanResult): Boolean
    fun parse(scanResult: ScanResult): ParsedAirPodsPacket?
}

/**
 * Decoder for Apple's public AirPods proximity-pairing advertisement.
 *
 * Android exposes the Apple company identifier (0x004C) as the key of the
 * manufacturer-data map and the remaining bytes as the value. The value is a
 * Continuity message: [type, length, payload]. AirPods status broadcasts use
 * type 0x07. Paired AirPods normally send a 0x01/25-byte frame, while the
 * case's pairing advertisement is a separate 0x00/14-byte frame with the
 * three battery bytes at different offsets. Both public formats are decoded;
 * encrypted bytes are intentionally ignored.
 */
class AppleAirPodsParser : AirPodsPacketParser {
    override val parserVersion: String = "apple-proximity-v1"

    override fun canParse(scanResult: ScanResult): Boolean =
        scanResult.scanRecord
            ?.manufacturerSpecificData
            ?.get(APPLE_COMPANY_ID)
            ?.let { parseManufacturerData(it) != null }
            ?: false

    override fun parse(scanResult: ScanResult): ParsedAirPodsPacket? =
        scanResult.scanRecord
            ?.manufacturerSpecificData
            ?.get(APPLE_COMPANY_ID)
            ?.let(::parseManufacturerData)

    companion object {
        const val APPLE_COMPANY_ID = 0x004C

        private const val PROXIMITY_MESSAGE_TYPE = 0x07
        private const val PLAINTEXT_STATUS_PREFIX = 0x01
        private const val LEGACY_STATUS_PREFIX = 0x00
        private const val AIRPODS_STATUS_LENGTH = 25
        private const val PAIRING_MODE_PREFIX = 0x00
        private const val PAIRING_MODE_LENGTH = 14

        private val MODEL_CODES = mapOf(
            0x0220 to AirPodsModel.AIRPODS_GEN1,
            0x0F20 to AirPodsModel.AIRPODS_GEN2,
            0x1320 to AirPodsModel.AIRPODS_GEN3,
            0x1920 to AirPodsModel.AIRPODS_GEN4,
            0x1B20 to AirPodsModel.AIRPODS_GEN4_ANC,
            0x0E20 to AirPodsModel.AIRPODS_PRO,
            0x1420 to AirPodsModel.AIRPODS_PRO2,
            0x2420 to AirPodsModel.AIRPODS_PRO2_USBC,
            0x2720 to AirPodsModel.AIRPODS_PRO3,
            0x0A20 to AirPodsModel.AIRPODS_MAX,
            0x1F20 to AirPodsModel.AIRPODS_MAX_USBC,
            0x2D20 to AirPodsModel.AIRPODS_MAX2,
        )

        /** Parses Android's manufacturer-data value without requiring a ScanResult. */
        fun parseManufacturerData(manufacturerData: ByteArray): ParsedAirPodsPacket? {
            val bytes = manufacturerData.withoutCompanyPrefix()
            // ScanRecord normally gives us only the manufacturer value, but
            // a few OEM Bluetooth stacks leave an AD/company header in it.
            // Locate a complete 07 message instead of assuming offset 0.
            for (cursor in 0 until (bytes.size - 1).coerceAtLeast(0)) {
                if (bytes[cursor].u8() != PROXIMITY_MESSAGE_TYPE) continue

                val messageLength = bytes[cursor + 1].u8()
                val payloadStart = cursor + 2
                val payloadEnd = payloadStart + messageLength
                if (payloadEnd > bytes.size) continue

                when (messageLength) {
                    AIRPODS_STATUS_LENGTH -> {
                        val payload = bytes.copyOfRange(payloadStart, payloadEnd)
                        val prefix = payload[0].u8()
                        val modelCode = (payload[1].u8() shl 8) or payload[2].u8()
                        val legacyFrame = prefix == LEGACY_STATUS_PREFIX && MODEL_CODES.containsKey(modelCode)
                        if (prefix == PLAINTEXT_STATUS_PREFIX || legacyFrame) {
                            return decodePayload(payload)
                        }
                    }

                    PAIRING_MODE_LENGTH -> {
                        val payload = bytes.copyOfRange(payloadStart, payloadEnd)
                        if (payload[0].u8() == PAIRING_MODE_PREFIX) {
                            return decodePairingPayload(payload)
                        }
                    }
                }
            }
            return null
        }

        /**
         * Decodes Apple's unencrypted pairing advertisement. Its absolute
         * bytes 0xC, 0xD and 0xE contain right, left and case battery levels.
         */
        private fun decodePairingPayload(payload: ByteArray): ParsedAirPodsPacket? {
            if (payload.size < PAIRING_MODE_LENGTH) return null

            val modelCode = (payload[1].u8() shl 8) or payload[2].u8()
            val model = MODEL_CODES[modelCode] ?: return null
            return ParsedAirPodsPacket(
                model = model,
                leftBattery = percentFromNibble(payload[11].u8()),
                rightBattery = percentFromNibble(payload[10].u8()),
                caseBattery = percentFromNibble(payload[12].u8()),
                leftCharging = null,
                rightCharging = null,
                caseCharging = null,
                leftInCase = null,
                rightInCase = null,
                caseOpen = null,
                parserVersion = "apple-proximity-pairing-v1",
                confidence = DataConfidence.LIVE,
            )
        }

        private fun decodePayload(payload: ByteArray): ParsedAirPodsPacket? {
            if (payload.size < 9) return null

            val modelCode = (payload[1].u8() shl 8) or payload[2].u8()
            val model = MODEL_CODES[modelCode] ?: AirPodsModel.AIRPODS
            val status = payload[3].u8()
            val podBattery = payload[4].u8()
            val flags = payload[5].u8() shr 4
            val caseBattery = percentFromNibble(payload[5].u8() and 0x0F)

            if (model.isMax) {
                return ParsedAirPodsPacket(
                    model = model,
                    leftBattery = percentFromNibble(podBattery and 0x0F),
                    rightBattery = null,
                    caseBattery = null,
                    leftCharging = flags and 0x01 != 0,
                    rightCharging = null,
                    caseCharging = null,
                    leftInCase = false,
                    rightInCase = null,
                    caseOpen = null,
                    parserVersion = "apple-proximity-v1",
                    confidence = DataConfidence.LIVE,
                )
            }

            val primaryPodIsLeft = status and 0x20 != 0
            val valuesFlipped = !primaryPodIsLeft
            val lowerPodBattery = percentFromNibble(podBattery and 0x0F)
            val upperPodBattery = percentFromNibble(podBattery shr 4)
            val leftBattery = if (valuesFlipped) upperPodBattery else lowerPodBattery
            val rightBattery = if (valuesFlipped) lowerPodBattery else upperPodBattery

            val leftCharging = if (valuesFlipped) flags and 0x02 != 0 else flags and 0x01 != 0
            val rightCharging = if (valuesFlipped) flags and 0x01 != 0 else flags and 0x02 != 0
            val caseCharging = flags and 0x04 != 0

            val thisPodInCase = status and 0x40 != 0
            val onePodInCase = status and 0x10 != 0
            val bothPodsInCase = status and 0x04 != 0
            val caseContext = thisPodInCase || onePodInCase || bothPodsInCase
            // A frame with only bit 4 set is emitted by the pod outside the case
            // and carries a stale lid byte. Only an in-case pod (bit 6) or a
            // frame with both pods in the case (bit 2) can reliably report the lid.
            val lidReadingReliable = thisPodInCase || bothPodsInCase
            val caseOpen = if (caseContext && lidReadingReliable) {
                ((payload[6].u8() shr 3) and 0x01) == 0
            } else {
                null
            }

            val leftInCase = when {
                bothPodsInCase -> true
                !onePodInCase -> false
                thisPodInCase -> primaryPodIsLeft
                else -> !primaryPodIsLeft
            }
            val rightInCase = when {
                bothPodsInCase -> true
                !onePodInCase -> false
                else -> !leftInCase
            }

            return ParsedAirPodsPacket(
                model = model,
                leftBattery = leftBattery,
                rightBattery = rightBattery,
                caseBattery = caseBattery,
                leftCharging = leftCharging,
                rightCharging = rightCharging,
                caseCharging = caseCharging,
                leftInCase = leftInCase,
                rightInCase = rightInCase,
                caseOpen = caseOpen,
                parserVersion = "apple-proximity-v1",
                confidence = DataConfidence.LIVE,
            )
        }

        private fun percentFromNibble(value: Int): Int? = when {
            value == 0x0F -> null
            value in 0..10 -> value * 10
            value in 11..14 -> 100
            else -> null
        }

        private fun ByteArray.withoutCompanyPrefix(): ByteArray =
            if (size >= 2 &&
                ((this[0].u8() == 0x4C && this[1].u8() == 0x00) ||
                    (this[0].u8() == 0x00 && this[1].u8() == 0x4C))
            ) {
                copyOfRange(2, size)
            } else {
                this
            }

        private fun Byte.u8(): Int = toInt() and 0xFF
    }
}

class AirPodsParserRegistry(
    private val parsers: List<AirPodsPacketParser> = listOf(AppleAirPodsParser()),
) {
    fun parse(scanResult: ScanResult): ParsedAirPodsPacket? =
        parsers.firstOrNull { it.canParse(scanResult) }?.parse(scanResult)
}
