package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.BatterySlot
import com.galaxyairpods.domain.model.ChargingEvidence
import com.galaxyairpods.domain.model.ChargingState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.mergeKnownValuesFrom
import org.junit.Assert.assertEquals
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
}
