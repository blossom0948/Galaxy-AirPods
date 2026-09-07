package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AppleAirPodsParser
import com.galaxyairpods.data.sameLogicalAirPods
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
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
    fun decodesOpenCaseAdvertisement() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e2054aab5310000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertEquals(AirPodsModel.AIRPODS_PRO, packet.model)
        assertEquals(100, packet.leftBattery)
        assertEquals(100, packet.rightBattery)
        assertEquals(50, packet.caseBattery)
        assertEquals(true, packet.caseOpen)
        assertEquals(true, packet.leftInCase)
        assertEquals(true, packet.rightInCase)
    }

    @Test
    fun decodesClosedCaseAdvertisement() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e2055aab439000008a6db99e05e1485e5c20b68d7ffc3a1".hex(),
        )

        requireNotNull(packet)
        assertEquals(40, packet.caseBattery)
        assertEquals(false, packet.caseOpen)
    }

    @Test
    fun decodesLegacyStatusPrefixWithKnownModel() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719000e2054aab5310000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertEquals(AirPodsModel.AIRPODS_PRO, packet.model)
        assertEquals(50, packet.caseBattery)
        assertEquals(true, packet.caseOpen)
    }

    @Test
    fun findsStatusMessageAfterOemManufacturerHeader() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "1bff4c000719010e2054aab5310000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertEquals(50, packet.caseBattery)
    }

    @Test
    fun ignoresUnsupportedProximityFrames() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719070e2075aa3001000045121212000000000000000000000000".hex(),
        )

        assertNull(packet)
    }

    @Test
    fun ignoresStaleLidFromPodOutsideCase() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e2010aab5510000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertNull(packet.caseOpen)
    }

    @Test
    fun rejectsNonAirPodsManufacturerData() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0201060303aafe".hex(),
        )

        assertNull(packet)
    }

    @Test
    fun keepsBleAndClassicAddressesInOneLogicalDevice() {
        val state = AirPodsState(
            deviceId = "BLE-RANDOM-ADDRESS",
            model = AirPodsModel.AIRPODS_PRO2,
            leftBattery = 80,
            detected = true,
        )

        assertEquals(
            true,
            sameLogicalAirPods(state, "CLASSIC-BLUETOOTH-ADDRESS", AirPodsModel.AIRPODS_PRO),
        )
    }
}

private fun String.hex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
