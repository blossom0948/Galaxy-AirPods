package com.galaxyairpods.data

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsConnectionState
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.ChargingEvidence
import com.galaxyairpods.domain.model.ChargingState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleAirPodsRepositoryTest {
    @Test
    fun partialPacketDoesNotEraseKnownBatteryOrLid() {
        val current = AirPodsState(
            deviceId = "ble-address",
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 80,
            rightBattery = 70,
            caseBattery = 60,
            caseOpen = true,
            connected = true,
            detected = true,
        )
        val partial = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = null,
            rightBattery = 60,
            caseBattery = null,
            leftCharging = null,
            rightCharging = false,
            caseCharging = null,
            leftInCase = null,
            rightInCase = true,
            caseOpen = null,
            parserVersion = "test",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "rotating-ble-address", partial, 123L)

        assertEquals("ble-address", merged.deviceId)
        assertEquals(80, merged.leftBattery)
        assertEquals(60, merged.rightBattery)
        assertEquals(60, merged.caseBattery)
        assertEquals(true, merged.caseOpen)
        assertEquals(true, merged.connected)
        assertEquals(123L, merged.lastSeenAt)
    }

    @Test
    fun freshOutOfCaseEvidenceClearsOldCharging() {
        val now = 1_000_000L
        val current = AirPodsState(
            deviceId = "classic-address",
            model = AirPodsModel.AIRPODS_PRO,
            leftCharging = true,
            leftChargingEvidence = ChargingEvidence(
                state = ChargingState.CHARGING,
                source = "AAP_CLASSIC_EXACT",
                capturedAt = now - 1_000L,
                expiresAt = now + 10_000L,
                proof = "AAP_0x0004",
            ),
            connected = true,
            detected = true,
            connectionState = AirPodsConnectionState.ANDROID_CONNECTED,
        )
        val packet = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = null,
            rightBattery = null,
            caseBattery = null,
            leftCharging = null,
            rightCharging = null,
            caseCharging = null,
            leftInCase = false,
            rightInCase = null,
            caseOpen = true,
            parserVersion = "test",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "ble-address", packet, now)

        assertNull(merged.leftCharging)
        assertEquals(ChargingState.UNKNOWN, merged.leftChargingEvidence.state)
        assertNull(merged.chargingFor(com.galaxyairpods.domain.model.BatterySlot.LEFT, now))
    }

    @Test
    fun freshNotChargingDoesNotReuseOldChargingTrue() {
        val now = 1_000_000L
        val current = AirPodsState(
            deviceId = "ble-address",
            model = AirPodsModel.AIRPODS_PRO,
            rightCharging = true,
            rightChargingEvidence = ChargingEvidence(
                state = ChargingState.CHARGING,
                source = "AAP_CLASSIC_EXACT",
                capturedAt = now - 1_000L,
                expiresAt = now + 10_000L,
                proof = "AAP_0x0004",
            ),
        )
        val packet = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = null,
            rightBattery = 80,
            caseBattery = null,
            leftCharging = null,
            rightCharging = false,
            caseCharging = null,
            leftInCase = null,
            rightInCase = true,
            caseOpen = null,
            parserVersion = "test",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "ble-address", packet, now)

        assertEquals(false, merged.rightCharging)
        assertEquals(false, merged.chargingFor(com.galaxyairpods.domain.model.BatterySlot.RIGHT, now))
        assertEquals(ChargingState.NOT_CHARGING, merged.rightChargingEvidence.state)
    }

    @Test
    fun outOfCaseEvidenceWinsOverAStaleChargingFlag() {
        val now = 1_000_000L
        val current = AirPodsState(
            model = AirPodsModel.AIRPODS_PRO,
            leftCharging = true,
            leftChargingEvidence = ChargingEvidence(
                state = ChargingState.CHARGING,
                source = "AAP_CLASSIC_EXACT",
                capturedAt = now - 1_000L,
                expiresAt = now + 10_000L,
                proof = "AAP_0x0004",
            ),
        )
        val packet = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 50,
            rightBattery = null,
            caseBattery = null,
            // This bit can remain set in a public frame; the out-of-case
            // context is the stronger evidence for a pod.
            leftCharging = true,
            rightCharging = null,
            caseCharging = null,
            leftInCase = false,
            rightInCase = false,
            caseOpen = true,
            parserVersion = "test",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "ble-address", packet, now)

        assertNull(merged.leftCharging)
        assertEquals(ChargingState.UNKNOWN, merged.leftChargingEvidence.state)
    }

    @Test
    fun nearbyPacketNeverCreatesAndroidConnectedState() {
        val packet = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 50,
            rightBattery = 40,
            caseBattery = 30,
            leftCharging = false,
            rightCharging = false,
            caseCharging = false,
            leftInCase = false,
            rightInCase = false,
            caseOpen = null,
            parserVersion = "test",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(AirPodsState.empty(), "ble-address", packet, 1_000L)

        assertEquals(AirPodsConnectionState.NEARBY_ONLY, merged.connectionState)
        assertEquals(false, merged.isAndroidConnected)
    }

    @Test
    fun closedCaseZeroKeepsTheLastKnownPodBattery() {
        val current = AirPodsState(
            deviceId = "ble-address",
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 63,
            rightBattery = 71,
            caseBattery = 44,
            detected = true,
            connectionState = AirPodsConnectionState.NEARBY_ONLY,
            batterySource = "BLE_PUBLIC_COARSE",
            batteryCapturedAt = 900L,
        )
        val packet = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 0,
            rightBattery = 71,
            caseBattery = 44,
            leftCharging = null,
            rightCharging = null,
            caseCharging = null,
            leftInCase = true,
            rightInCase = false,
            caseOpen = false,
            parserVersion = "apple-proximity-public-v4",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "ble-address", packet, 1_000L)

        assertEquals(63, merged.leftBattery)
        assertEquals(71, merged.rightBattery)
        assertEquals(1_000L, merged.batteryCapturedAt)
    }

    @Test
    fun closedCaseZeroWithoutHistoryRemainsUnknownInsteadOfInventingAValue() {
        val packet = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 0,
            rightBattery = null,
            caseBattery = null,
            leftCharging = null,
            rightCharging = null,
            caseCharging = null,
            leftInCase = true,
            rightInCase = null,
            caseOpen = false,
            parserVersion = "apple-proximity-public-v4",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(AirPodsState.empty(), "ble-address", packet, 1_000L)

        assertEquals(null, merged.leftBattery)
        assertEquals(null, merged.batteryCapturedAt)
    }

    @Test
    fun coarseBleDoesNotDowngradeAnExactAapBatterySample() {
        val current = AirPodsState(
            deviceId = "classic-address",
            deviceProfileId = "profile-a",
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 96,
            rightBattery = 83,
            caseBattery = 44,
            batterySource = "AAP_CLASSIC_EXACT",
            batteryCapturedAt = 900L,
            detected = true,
            connectionState = AirPodsConnectionState.ANDROID_CONNECTED,
        )
        val coarse = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 90,
            rightBattery = 80,
            caseBattery = 40,
            leftCharging = false,
            rightCharging = false,
            caseCharging = false,
            leftInCase = false,
            rightInCase = false,
            caseOpen = true,
            parserVersion = "apple-proximity-public-v4",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "ble-address", coarse, 1_000L, deviceProfileId = "profile-a")

        assertEquals(96, merged.leftBattery)
        assertEquals(83, merged.rightBattery)
        assertEquals(44, merged.caseBattery)
        assertEquals("AAP_CLASSIC_EXACT", merged.batterySource)
        assertEquals(900L, merged.batteryCapturedAt)
    }
}
