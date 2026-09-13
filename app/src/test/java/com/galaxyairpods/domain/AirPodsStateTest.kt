package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsConnectionState
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.AirPodsWearState
import com.galaxyairpods.domain.model.BatterySlot
import com.galaxyairpods.domain.model.ChargingEvidence
import com.galaxyairpods.domain.model.ChargingState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.mergeKnownValuesFrom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AirPodsStateTest {
    @Test
    fun nullBatteryIsDifferentFromZero() {
        val unknown = AirPodsState(leftBattery = null)
        val empty = AirPodsState(leftBattery = 0)

        assertEquals(null, unknown.leftBattery)
        assertEquals(0, empty.leftBattery)
    }

    @Test
    fun liveDataBecomesStaleAfterWindow() {
        val now = 1_000_000L
        val state = AirPodsState(
            lastSeenAt = now - 20 * 60 * 1000L,
            confidence = DataConfidence.LIVE,
        )

        assertEquals(DataConfidence.STALE, state.withResolvedConfidence(now).confidence)
    }

    @Test
    fun liveProfileKeepsPersistedBatteryFallback() {
        val live = AirPodsState(
            deviceId = "classic-address",
            model = AirPodsModel.AIRPODS_PRO,
            rightBattery = 70,
            connected = true,
            detected = true,
        )
        val stored = AirPodsState(
            deviceId = "ble-rotating-address",
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 80,
            caseBattery = 60,
            detected = true,
        )

        val merged = live.mergeKnownValuesFrom(stored)

        assertEquals(80, merged.leftBattery)
        assertEquals(70, merged.rightBattery)
        assertEquals(60, merged.caseBattery)
        assertEquals(true, merged.connected)
    }

    @Test
    fun freshCoarseLiveCaseReplacesStaleExactPersistedCase() {
        val now = System.currentTimeMillis()
        val live = AirPodsState(
            deviceId = "classic-address",
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 96,
            rightBattery = 83,
            caseBattery = 40,
            batterySource = "BLE_PUBLIC_COARSE",
            batteryCapturedAt = now,
            caseBatterySource = "BLE_PUBLIC_COARSE",
            caseBatteryCapturedAt = now,
            connected = true,
            detected = true,
        )
        val stored = AirPodsState(
            deviceId = "classic-address",
            model = AirPodsModel.AIRPODS_PRO,
            caseBattery = 44,
            caseBatterySource = "AAP_CLASSIC_EXACT",
            caseBatteryCapturedAt = now - 20 * 60 * 1000L,
            detected = true,
        )

        val merged = live.mergeKnownValuesFrom(stored)

        assertEquals(40, merged.caseBattery)
        assertEquals("BLE_PUBLIC_COARSE", merged.caseBatterySource)
        assertEquals(now, merged.caseBatteryCapturedAt)
    }

    @Test
    fun freshExactCaseStillWinsOverFreshCoarseCase() {
        val now = System.currentTimeMillis()
        val live = AirPodsState(
            deviceId = "classic-address",
            model = AirPodsModel.AIRPODS_PRO,
            caseBattery = 40,
            caseBatterySource = "BLE_PUBLIC_COARSE",
            caseBatteryCapturedAt = now,
        )
        val stored = AirPodsState(
            deviceId = "classic-address",
            model = AirPodsModel.AIRPODS_PRO,
            caseBattery = 44,
            caseBatterySource = "AAP_CLASSIC_EXACT",
            caseBatteryCapturedAt = now - 1_000L,
        )

        val merged = live.mergeKnownValuesFrom(stored)

        assertEquals(44, merged.caseBattery)
        assertEquals("AAP_CLASSIC_EXACT", merged.caseBatterySource)
        assertEquals(now - 1_000L, merged.caseBatteryCapturedAt)
    }

    @Test
    fun staleChargingDoesNotSurviveResolution() {
        val now = 1_000_000L
        val state = AirPodsState(
            leftBattery = 50,
            batteryCapturedAt = now,
            confidence = DataConfidence.LIVE,
            leftCharging = true,
            leftChargingEvidence = ChargingEvidence(
                state = ChargingState.CHARGING,
                source = "AAP_CLASSIC_EXACT",
                capturedAt = now - 30_000L,
                expiresAt = now - 1L,
                proof = "AAP_0x0004",
            ),
        )

        val resolved = state.withResolvedConfidence(now)

        assertEquals(null, resolved.chargingFor(BatterySlot.LEFT, now))
        assertEquals(null, resolved.leftCharging)
    }

    @Test
    fun liveConnectionChargingAndWearEvidenceNeverComeFromBatteryFallback() {
        val now = System.currentTimeMillis()
        val elapsedNow = 1_000L
        val live = AirPodsState(
            deviceProfileId = "profile-a",
            connectionState = AirPodsConnectionState.NEARBY_ONLY,
            connected = false,
            detected = false,
            a2dpConnected = false,
            headsetConnected = false,
            aapReady = false,
            leftCharging = null,
            wearState = AirPodsWearState.UNKNOWN,
        )
        val fallback = AirPodsState(
            deviceProfileId = "profile-a",
            connected = true,
            detected = true,
            connectionState = AirPodsConnectionState.ANDROID_CONNECTED,
            a2dpConnected = true,
            headsetConnected = true,
            aapReady = true,
            leftCharging = true,
            leftChargingEvidence = ChargingEvidence(
                state = ChargingState.CHARGING,
                source = "AAP_CLASSIC_EXACT",
                capturedAt = now,
                expiresAt = now + 10_000L,
                proof = "AAP_0x0004",
            ),
            wearState = AirPodsWearState.BOTH_IN_EAR,
            wearSource = "AAP_CLASSIC_0x0006",
            wearCapturedAtElapsedMs = elapsedNow,
            wearExpiresAtElapsedMs = elapsedNow + 10_000L,
            wearDeviceProfileId = "profile-a",
        )

        val merged = live.mergeKnownValuesFrom(fallback)

        assertFalse(merged.connected)
        assertFalse(merged.detected)
        assertFalse(merged.a2dpConnected)
        assertFalse(merged.headsetConnected)
        assertFalse(merged.aapReady)
        assertEquals(null, merged.leftCharging)
        assertEquals(ChargingState.UNKNOWN, merged.leftChargingEvidence.state)
        assertEquals(AirPodsWearState.UNKNOWN, merged.wearState)
        assertEquals(null, merged.wearSource)
    }
}
