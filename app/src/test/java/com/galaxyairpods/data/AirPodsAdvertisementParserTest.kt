package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AppleAirPodsParser
import com.galaxyairpods.data.sameLogicalAirPods
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.AirPodsWearState
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
    fun rejectsLegacyStatusPrefixWithoutPublicProof() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719000e2054aab5310000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        assertNull(packet)
    }

    @Test
    fun rejectsPairingModeAdvertisementFromPublicTier() {
        // 07 0E + prefix/model/address/unknown/right/left/case/color.
        val packet = AppleAirPodsParser.parseManufacturerData(
            "070e000e200000000000000005060400".hex(),
        )

        assertNull(packet)
    }

    @Test
    fun rejectsRawAdHeaderWhenOnlyManufacturerValueIsExpected() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "1bff4c000719010e2054aab5310000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        assertNull(packet)
    }

    @Test
    fun parsesAppleManufacturerDataFromRawScanRecord() {
        val rawScanRecord = (
            "0201061eff4c000719010e2054aab5310000e00ca78a604bd37df4604f2c73e9a7f4"
        ).hex()

        val packet = AppleAirPodsParser.parseScanRecordBytes(rawScanRecord)

        requireNotNull(packet)
        assertEquals(AirPodsModel.AIRPODS_PRO, packet.model)
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
    fun onePodInCaseIsNotReportedAsBothOutOfEar() {
        // status=0x10 means one pod is in the case. The lid byte is not used
        // here because V4 only trusts it with the stronger in-case bits.
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e2010aab5510000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertEquals(AirPodsWearState.IN_CASE, packet.wearState)
    }

    @Test
    fun onePodInCaseWithOtherPodInEarKeepsTheInEarSide() {
        // primary=L, one pod in case, primary still in ear.
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e2032aab5510000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertEquals(AirPodsWearState.LEFT_IN_EAR, packet.wearState)
        assertEquals(false, packet.leftInCase)
        assertEquals(true, packet.rightInCase)
    }

    @Test
    fun treatsReservedPublicBatteryNibblesAsUnknown() {
        val packet = AppleAirPodsParser.parseManufacturerData(
            "0719010e2054bbb5310000e00ca78a604bd37df4604f2c73e9a7f4".hex(),
        )

        requireNotNull(packet)
        assertNull(packet.leftBattery)
        assertNull(packet.rightBattery)
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

    @Test
    fun doesNotJoinDifferentProfileIdsForNearbyTelemetry() {
        val state = AirPodsState(
            deviceId = "classic-address",
            deviceProfileId = "profile-a",
            model = AirPodsModel.AIRPODS_PRO,
            detected = true,
        )

        assertEquals(
            false,
            sameLogicalAirPods(
                current = state,
                incomingDeviceId = "rotating-ble-address",
                incomingModel = AirPodsModel.AIRPODS_PRO,
                incomingProfileId = "profile-b",
                allowModelFallback = false,
            ),
        )
    }
}

private fun String.hex(): ByteArray =
    chunked(2).map { it.toInt(16).toByte() }.toByteArray()
