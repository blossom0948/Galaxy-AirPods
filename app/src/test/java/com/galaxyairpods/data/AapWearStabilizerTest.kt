package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AapEarDetectionSnapshot
import com.galaxyairpods.data.bluetooth.AapEarStatus
import com.galaxyairpods.data.bluetooth.AapWearStabilizer
import com.galaxyairpods.domain.model.AirPodsWearState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AapWearStabilizerTest {
    @Test
    fun requiresThreeConsistentFramesBeforePublishing() {
        val stabilizer = AapWearStabilizer()
        val bothInEar = AapEarDetectionSnapshot(AapEarStatus.IN_EAR, AapEarStatus.IN_EAR)

        assertNull(stabilizer.accept(bothInEar, primaryPodIsLeft = true, seenAt = 100L))
        assertNull(stabilizer.accept(bothInEar, primaryPodIsLeft = true, seenAt = 200L))
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(bothInEar, primaryPodIsLeft = true, seenAt = 300L),
        )
    }

    @Test
    fun mapsPrimaryAndSecondaryToPhysicalSide() {
        val stabilizer = AapWearStabilizer(requiredFrames = 1)
        val primaryOnly = AapEarDetectionSnapshot(AapEarStatus.IN_EAR, AapEarStatus.OUT_OF_EAR)

        assertEquals(
            AirPodsWearState.LEFT_IN_EAR,
            stabilizer.accept(primaryOnly, primaryPodIsLeft = true, seenAt = 100L),
        )
        assertEquals(
            AirPodsWearState.RIGHT_IN_EAR,
            stabilizer.accept(primaryOnly, primaryPodIsLeft = false, seenAt = 200L),
        )
    }

    @Test
    fun refusesPhysicalMappingWithoutPrimarySideEvidence() {
        val stabilizer = AapWearStabilizer(requiredFrames = 1)
        val primaryOnly = AapEarDetectionSnapshot(AapEarStatus.IN_EAR, AapEarStatus.OUT_OF_EAR)

        assertNull(stabilizer.accept(primaryOnly, primaryPodIsLeft = null, seenAt = 100L))
    }
}
