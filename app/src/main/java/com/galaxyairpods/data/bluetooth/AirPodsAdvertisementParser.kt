package com.galaxyairpods.data.bluetooth

import android.bluetooth.le.ScanResult
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket

data class ParserMatch(
    val matched: Boolean,
    val parserName: String,
    val resultLabel: String,
)

interface AirPodsPacketParser {
    val parserVersion: String
    fun canParse(scanResult: ScanResult): Boolean
    fun parse(scanResult: ScanResult): ParsedAirPodsPacket?
}

/**
 * Safe registry entry for Apple manufacturer advertisements.
 *
 * This intentionally does not guess byte offsets. The packet layout and battery
 * semantics must be validated against anonymized device vectors first.
 */
class UnvalidatedAppleAirPodsParser : AirPodsPacketParser {
    override val parserVersion: String = "apple-unvalidated-v0"

    override fun canParse(scanResult: ScanResult): Boolean =
        scanResult.scanRecord?.manufacturerSpecificData?.get(APPLE_COMPANY_ID) != null

    override fun parse(scanResult: ScanResult): ParsedAirPodsPacket? = null

    fun diagnosticMatch(scanResult: ScanResult): ParserMatch = if (canParse(scanResult)) {
        ParserMatch(
            matched = true,
            parserName = parserVersion,
            resultLabel = "Apple manufacturer packet / NEEDS_DEVICE_VALIDATION",
        )
    } else {
        ParserMatch(
            matched = false,
            parserName = parserVersion,
            resultLabel = "No validated parser match",
        )
    }

    companion object {
        // Bluetooth SIG company identifier for Apple, Inc.
        const val APPLE_COMPANY_ID = 0x004C
    }
}

class AirPodsParserRegistry(
    private val parsers: List<AirPodsPacketParser> = listOf(UnvalidatedAppleAirPodsParser()),
) {
    fun match(scanResult: ScanResult): ParserMatch {
        val parser = parsers.firstOrNull { it.canParse(scanResult) }
            ?: return ParserMatch(false, "none", "No validated parser match")
        return if (parser is UnvalidatedAppleAirPodsParser) {
            parser.diagnosticMatch(scanResult)
        } else {
            ParserMatch(true, parser.parserVersion, "Parser matched")
        }
    }

    fun parse(scanResult: ScanResult): ParsedAirPodsPacket? =
        parsers.firstOrNull { it.canParse(scanResult) }?.parse(scanResult)
}
