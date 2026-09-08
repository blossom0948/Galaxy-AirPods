package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AapEarDetectionSnapshot
import com.galaxyairpods.data.bluetooth.AapEarStatus
import com.galaxyairpods.data.bluetooth.EarEventValidator
import com.galaxyairpods.data.bluetooth.PerDeviceWearStabilizer
import com.galaxyairpods.data.bluetooth.WearEventCandidate
import com.galaxyairpods.data.bluetooth.WearEventSource
import com.galaxyairpods.data.bluetooth.mapAapWearState
import com.galaxyairpods.domain.model.AirPodsWearState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PerDeviceWearStabilizerTest {
    @Test
    fun validatorRejectsUntrustedOrTemporallyInvalidFramesBeforeCounting() {
        var now = 10_000L
        val validator = EarEventValidator(clock = { now })
        val valid = candidate("device-a", 10_000L)

        assertEquals(valid, validator.validate(valid))
        assertNull(
            validator.validate(
                valid.copy(identityProof = false),
            ),
        )
        assertNull(
            validator.validate(
                valid.copy(capturedAtElapsedMs = 9_000L, freshnessTtlMs = 500L),
            ),
        )
        assertNull(
            validator.validate(
                valid.copy(capturedAtElapsedMs = 11_200L, receivedAtElapsedMs = 10_000L),
            ),
        )
        now = 26_000L
        assertNull(validator.validate(valid))
    }

    @Test
    fun requiresThreeFreshConsistentFramesPerDeviceAndSource() {
        var now = 1_000L
        val stabilizer = PerDeviceWearStabilizer(clock = { now })

        assertNull(stabilizer.accept(candidate("device-a", 1_000L)))
        assertNull(stabilizer.accept(candidate("device-a", 1_100L)))
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(candidate("device-a", 1_200L))?.state,
        )

        // A second AirPods profile has an independent counter.
        now = 1_300L
        assertNull(stabilizer.accept(candidate("device-b", 1_300L)))
    }

    @Test
    fun unknownOrConflictResetsTheCandidateCounter() {
        var now = 2_000L
        val stabilizer = PerDeviceWearStabilizer(clock = { now })
        assertNull(stabilizer.accept(candidate("device-a", 2_000L)))
        assertNull(stabilizer.accept(candidate("device-a", 2_100L)))

        assertNull(
            stabilizer.accept(
                candidate("device-a", 2_200L, state = AirPodsWearState.UNKNOWN),
            ),
        )
        assertNull(stabilizer.accept(candidate("device-a", 2_300L)))
        assertNull(stabilizer.accept(candidate("device-a", 2_400L)))
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(candidate("device-a", 2_500L))?.state,
        )
    }

    @Test
    fun staleOutOfOrderAndLargeGapFramesAreNotCounted() {
        var now = 3_000L
        val stabilizer = PerDeviceWearStabilizer(
            maxFrameGapMs = 200L,
            clock = { now },
        )

        assertNull(stabilizer.accept(candidate("device-a", 3_000L)))
        assertNull(stabilizer.accept(candidate("device-a", 2_900L)))
        assertNull(stabilizer.accept(candidate("device-a", 3_100L)))
        now = 3_500L
        assertNull(stabilizer.accept(candidate("device-a", 3_500L)))
        assertNull(stabilizer.accept(candidate("device-a", 3_600L)))
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(candidate("device-a", 3_700L))?.state,
        )

        now = 20_000L
        assertNull(stabilizer.accept(candidate("device-a", 3_800L)))
    }

    @Test
    fun disagreementBetweenFreshSourcesIsConflict() {
        var now = 4_000L
        val stabilizer = PerDeviceWearStabilizer(clock = { now })
        assertNull(stabilizer.accept(candidate("device-a", 4_000L)))
        assertNull(stabilizer.accept(candidate("device-a", 4_100L)))
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(candidate("device-a", 4_200L))?.state,
        )
        now = 5_000L
        repeat(2) { index ->
            assertNull(
                stabilizer.accept(
                    candidate(
                        device = "device-a",
                        capturedAt = 4_400L + index * 100L,
                        source = WearEventSource.AAP_CLASSIC,
                        state = AirPodsWearState.NONE_IN_EAR,
                    ),
                ),
            )
        }
        assertEquals(
            AirPodsWearState.CONFLICT,
            stabilizer.accept(
                candidate(
                    device = "device-a",
                    capturedAt = 4_600L,
                    source = WearEventSource.AAP_CLASSIC,
                    state = AirPodsWearState.NONE_IN_EAR,
                ),
        )?.state,
        )
    }

    @Test
    fun eventDrivenAapChangeNotificationCanBecomeStableAfterOneValidatedFrame() {
        var now = 6_000L
        val stabilizer = PerDeviceWearStabilizer(
            requiredFrames = 3,
            requiredFramesForSource = { source ->
                if (source == WearEventSource.AAP_CLASSIC) 1 else 3
            },
            clock = { now },
        )

        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(
                candidate(
                    device = "device-a",
                    capturedAt = now,
                    source = WearEventSource.AAP_CLASSIC,
                    state = AirPodsWearState.BOTH_IN_EAR,
                ),
            )?.state,
        )

        // BLE still needs the normal three fresh frames.
        now = 6_100L
        assertNull(stabilizer.accept(candidate("device-a", now)))
        now = 6_200L
        assertNull(stabilizer.accept(candidate("device-a", now)))
        now = 6_300L
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            stabilizer.accept(candidate("device-a", now))?.state,
        )
    }

    @Test
    fun mapsAapPrimaryAndSecondaryToPhysicalSides() {
        val primaryOnly = AapEarDetectionSnapshot(
            primary = AapEarStatus.IN_EAR,
            secondary = AapEarStatus.OUT_OF_EAR,
        )

        assertEquals(
            AirPodsWearState.LEFT_IN_EAR,
            mapAapWearState(primaryOnly, primaryPodIsLeft = true),
        )
        assertEquals(
            AirPodsWearState.RIGHT_IN_EAR,
            mapAapWearState(primaryOnly, primaryPodIsLeft = false),
        )
        assertEquals(
            AirPodsWearState.PARTIAL_IN_EAR,
            mapAapWearState(primaryOnly, primaryPodIsLeft = null),
        )
        assertEquals(
            AirPodsWearState.BOTH_IN_EAR,
            mapAapWearState(
                AapEarDetectionSnapshot(
                    primary = AapEarStatus.IN_EAR,
                    secondary = AapEarStatus.IN_EAR,
                ),
                primaryPodIsLeft = null,
            ),
        )
        assertEquals(
            AirPodsWearState.NONE_IN_EAR,
            mapAapWearState(
                AapEarDetectionSnapshot(
                    primary = AapEarStatus.OUT_OF_EAR,
                    secondary = AapEarStatus.OUT_OF_EAR,
                ),
                primaryPodIsLeft = null,
            ),
        )
    }

    @Test
    fun mapsOnePodInCaseAndOtherInEarToThePhysicalInEarSide() {
        assertEquals(
            AirPodsWearState.LEFT_IN_EAR,
            mapAapWearState(
                AapEarDetectionSnapshot(
                    primary = AapEarStatus.IN_EAR,
                    secondary = AapEarStatus.IN_CASE,
                ),
                primaryPodIsLeft = true,
            ),
        )
    }

    @Test
    fun mapsBothPodsInCaseToInCaseWithoutTreatingItAsConflict() {
        assertEquals(
            AirPodsWearState.IN_CASE,
            mapAapWearState(
                AapEarDetectionSnapshot(
                    primary = AapEarStatus.IN_CASE,
                    secondary = AapEarStatus.IN_CASE,
                ),
                primaryPodIsLeft = true,
            ),
        )
    }

    private fun candidate(
        device: String,
        capturedAt: Long,
        state: AirPodsWearState = AirPodsWearState.BOTH_IN_EAR,
        source: WearEventSource = WearEventSource.BLE_PUBLIC,
    ) = WearEventCandidate(
        deviceProfileId = device,
        source = source,
        state = state,
        capturedAtElapsedMs = capturedAt,
        receivedAtElapsedMs = capturedAt,
    )
}
