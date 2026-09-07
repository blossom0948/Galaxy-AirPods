package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
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
}
