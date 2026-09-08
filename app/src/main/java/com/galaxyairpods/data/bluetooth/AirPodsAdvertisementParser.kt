package com.galaxyairpods.data.bluetooth

import android.bluetooth.le.ScanResult
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsWearState
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
 * type 0x07. V4 only promotes the verified public proximity profile:
 * valueLength=25 and prefix=0x01. Pairing-mode advertisements and opaque
 * encrypted tails are not battery candidates in this tier.
 */
class AppleAirPodsParser : AirPodsPacketParser {
    override val parserVersion: String = "apple-proximity-public-v4"

    override fun canParse(scanResult: ScanResult): Boolean =
        scanResult.scanRecord
            ?.let { record ->
                manufacturerDataCandidates(record).any { parseManufacturerData(it) != null }
            }
            ?: false

    override fun parse(scanResult: ScanResult): ParsedAirPodsPacket? =
        scanResult.scanRecord
            ?.let { record ->
                manufacturerDataCandidates(record).firstNotNullOfOrNull(::parseManufacturerData)
            }

    companion object {
        const val APPLE_COMPANY_ID = 0x004C

        private const val PROXIMITY_MESSAGE_TYPE = 0x07
        private const val MANUFACTURER_DATA_AD_TYPE = 0xFF
        private const val PLAINTEXT_STATUS_PREFIX = 0x01
        private const val AIRPODS_STATUS_LENGTH = 25

        private val MODEL_CODES = mapOf(
            0x2002 to AirPodsModel.AIRPODS_GEN1,
            0x200F to AirPodsModel.AIRPODS_GEN2,
            0x2013 to AirPodsModel.AIRPODS_GEN3,
            0x2019 to AirPodsModel.AIRPODS_GEN4,
            0x201B to AirPodsModel.AIRPODS_GEN4_ANC,
            0x200E to AirPodsModel.AIRPODS_PRO,
            0x2014 to AirPodsModel.AIRPODS_PRO2,
            0x2024 to AirPodsModel.AIRPODS_PRO2_USBC,
            0x2027 to AirPodsModel.AIRPODS_PRO3,
            0x200A to AirPodsModel.AIRPODS_MAX,
            0x201F to AirPodsModel.AIRPODS_MAX_USBC,
            0x202D to AirPodsModel.AIRPODS_MAX2,
        )

        /** Parses Android's manufacturer-data value without requiring a ScanResult. */
        fun parseManufacturerData(manufacturerData: ByteArray): ParsedAirPodsPacket? {
            val bytes = manufacturerData.withoutCompanyPrefix()
            var cursor = 0
            while (cursor + 1 < bytes.size) {
                val messageType = bytes[cursor].u8()
                val messageLength = bytes[cursor + 1].u8()
                val payloadStart = cursor + 2
                val payloadEnd = payloadStart + messageLength
                if (payloadEnd > bytes.size) return null

                if (messageType == PROXIMITY_MESSAGE_TYPE && messageLength == AIRPODS_STATUS_LENGTH) {
                    val payload = bytes.copyOfRange(payloadStart, payloadEnd)
                    if (payload[0].u8() == PLAINTEXT_STATUS_PREFIX) {
                        return decodePayload(payload)
                    }
                }
                cursor = payloadEnd
            }
            return null
        }

        /**
         * Parses the raw AD structures returned by ScanRecord.bytes.
         *
         * Android normally exposes this through manufacturerSpecificData, but
         * some Samsung Bluetooth stacks have returned a raw record while the
         * manufacturer map was empty. The manufacturer AD structure contains
         * Apple's little-endian company identifier 0x004C.
         */
        fun parseScanRecordBytes(scanRecordBytes: ByteArray): ParsedAirPodsPacket? =
            extractAppleManufacturerData(scanRecordBytes)
                .firstNotNullOfOrNull(::parseManufacturerData)
                ?: parseManufacturerData(scanRecordBytes)

        private fun manufacturerDataCandidates(record: android.bluetooth.le.ScanRecord): List<ByteArray> =
            buildList {
                record.manufacturerSpecificData.get(APPLE_COMPANY_ID)?.let(::add)
                addAll(extractAppleManufacturerData(record.bytes))
            }.distinctBy { it.contentHashCode() }

        private fun extractAppleManufacturerData(scanRecordBytes: ByteArray): List<ByteArray> {
            if (scanRecordBytes.isEmpty()) return emptyList()

            val result = mutableListOf<ByteArray>()
            var offset = 0
            while (offset < scanRecordBytes.size) {
                val fieldLength = scanRecordBytes[offset].u8()
                if (fieldLength == 0) break

                val fieldStart = offset + 1
                val fieldEnd = fieldStart + fieldLength
                if (fieldEnd > scanRecordBytes.size) break

                val fieldType = scanRecordBytes[fieldStart].u8()
                if (fieldType == MANUFACTURER_DATA_AD_TYPE && fieldLength >= 3) {
                    val companyId = scanRecordBytes[fieldStart + 1].u8() or
                        (scanRecordBytes[fieldStart + 2].u8() shl 8)
                    if (companyId == APPLE_COMPANY_ID) {
                        result += scanRecordBytes.copyOfRange(fieldStart + 3, fieldEnd)
                    }
                }
                offset = fieldEnd
            }
            return result
        }

        private fun decodePayload(payload: ByteArray): ParsedAirPodsPacket? {
            if (payload.size < 9) return null

            val modelCode = payload[1].u8() or (payload[2].u8() shl 8)
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
                    parserVersion = "apple-proximity-public-v4",
                    confidence = DataConfidence.LIVE,
                    primaryPodIsLeft = true,
                    wearState = null,
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
            val xorFactor = valuesFlipped xor thisPodInCase
            val primaryInEar = if (xorFactor) status and 0x08 != 0 else status and 0x02 != 0
            val secondaryInEar = if (xorFactor) status and 0x02 != 0 else status and 0x08 != 0
            val wearState = when {
                primaryInEar && secondaryInEar -> AirPodsWearState.BOTH_IN_EAR
                primaryInEar && primaryPodIsLeft -> AirPodsWearState.LEFT_IN_EAR
                primaryInEar && !primaryPodIsLeft -> AirPodsWearState.RIGHT_IN_EAR
                secondaryInEar && primaryPodIsLeft -> AirPodsWearState.RIGHT_IN_EAR
                secondaryInEar && !primaryPodIsLeft -> AirPodsWearState.LEFT_IN_EAR
                // A one-pod-in-case frame can still contain a valid in-ear
                // reading for the other pod. Prefer the proven in-ear side;
                // only use IN_CASE when neither pod is in an ear.
                bothPodsInCase || thisPodInCase || onePodInCase -> AirPodsWearState.IN_CASE
                else -> AirPodsWearState.NONE_IN_EAR
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
                parserVersion = "apple-proximity-public-v4",
                confidence = DataConfidence.LIVE,
                primaryPodIsLeft = primaryPodIsLeft,
                wearState = wearState,
            )
        }

        private fun percentFromNibble(value: Int): Int? = when {
            value in 0..10 -> value * 10
            value in 0x0B..0x0F -> null
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

    fun parseRawScanRecord(scanRecordBytes: ByteArray): ParsedAirPodsPacket? =
        AppleAirPodsParser.parseScanRecordBytes(scanRecordBytes)
}
