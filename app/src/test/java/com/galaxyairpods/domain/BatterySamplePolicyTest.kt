package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.BatterySample
import com.galaxyairpods.domain.model.BatterySamplePolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class BatterySamplePolicyTest {
    @Test
    fun freshCoarseCaseBeatsOlderExactCase() {
        val now = 1_000_000L
        val selected = BatterySamplePolicy.choose(
            current = BatterySample(40, BatterySamplePolicy.BLE_PUBLIC_COARSE, now),
            previous = BatterySample(
                44,
                BatterySamplePolicy.AAP_EXACT,
                now - 11_000L,
            ),
            now = now,
        )

        assertEquals(40, selected.value)
        assertEquals(BatterySamplePolicy.BLE_PUBLIC_COARSE, selected.source)
    }

    @Test
    fun outOfOrderOldSaveCannotOverwriteFreshCase() {
        val now = 1_000_000L
        val selected = BatterySamplePolicy.choose(
            // This is the older screen/service collector finishing later.
            current = BatterySample(44, BatterySamplePolicy.AAP_EXACT, now - 11_000L),
            // This is the value already committed by the other collector.
            previous = BatterySample(40, BatterySamplePolicy.BLE_PUBLIC_COARSE, now),
            now = now,
        )

        assertEquals(40, selected.value)
        assertEquals(BatterySamplePolicy.BLE_PUBLIC_COARSE, selected.source)
    }

    @Test
    fun freshExactCaseBeatsFreshCoarseCase() {
        val now = 1_000_000L
        val selected = BatterySamplePolicy.choose(
            current = BatterySample(40, BatterySamplePolicy.BLE_PUBLIC_COARSE, now),
            previous = BatterySample(
                44,
                BatterySamplePolicy.AAP_EXACT,
                now - 1_000L,
            ),
            now = now,
        )

        assertEquals(44, selected.value)
        assertEquals(BatterySamplePolicy.AAP_EXACT, selected.source)
    }

    @Test
    fun emptyIncomingSampleCannotEraseStoredCase() {
        val selected = BatterySamplePolicy.choose(
            current = BatterySample(null, null, null),
            previous = BatterySample(44, BatterySamplePolicy.BLE_PUBLIC_COARSE, 1_000L),
            now = 1_000L,
        )

        assertEquals(44, selected.value)
        assertEquals(BatterySamplePolicy.BLE_PUBLIC_COARSE, selected.source)
    }
}
