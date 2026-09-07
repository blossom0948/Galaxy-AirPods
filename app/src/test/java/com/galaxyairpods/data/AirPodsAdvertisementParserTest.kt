package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AppleAirPodsParser
import com.galaxyairpods.domain.model.AirPodsModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPodsAdvertisementParserTest {
    @Test
    fun decodesPublicAirPodsProAdvertisement() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e202b668f01000500000000000000000000000000000000".hex(),
        )

        requireNotNull(packet)
        assertEquals(AirPodsModel.AIRPODS_PRO, packet.model)
        assertEquals(60, packet.leftBattery)
        assertEquals(60, packet.rightBattery)
        assertNull(packet.caseBattery)
        assertNull(packet.caseOpen)
    }

    @Test
    fun rejectsNonAirPodsManufacturerData() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0201060303aafe".hex(),
        )

        assertNull(packet)
    }
}

private fun String.hex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
