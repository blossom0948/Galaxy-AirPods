package com.galaxyairpods.service

import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.AirPodsWearState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.Assert.assertTrue

class WearPlaybackPolicyTest {
    @Test
    fun unknownMediaObservationIsNeverTreatedAsPlaying() {
        assertTrue(mediaWasPlayingFromObservation(MediaPlaybackObservation.PLAYING))
        assertFalse(mediaWasPlayingFromObservation(MediaPlaybackObservation.NOT_PLAYING))
        assertFalse(mediaWasPlayingFromObservation(MediaPlaybackObservation.UNKNOWN))
    }

    @Test
    fun freshWearEvidenceRequiresSourceAndMatchingProfile() {
        val base = AirPodsState(
            deviceProfileId = "profile-a",
            wearState = AirPodsWearState.LEFT_IN_EAR,
            wearSource = null,
            wearCapturedAtElapsedMs = 1_000L,
            wearExpiresAtElapsedMs = 2_000L,
            wearDeviceProfileId = "profile-a",
        )

        assertFalse(base.hasFreshWearEvidenceAt(1_500L))
        assertTrue(
            base.copy(wearSource = "BLE_PUBLIC_EAR_STATE")
                .hasFreshWearEvidenceAt(1_500L),
        )
        assertFalse(
            base.copy(
                wearSource = "BLE_PUBLIC_EAR_STATE",
                wearDeviceProfileId = "profile-b",
            ).hasFreshWearEvidenceAt(1_500L),
        )
    }

    @Test
    fun eventDrivenAapMayDefineAChangeAfterAQuietPeriod() {
        assertEquals(
            false,
            shouldResetWearTransition(
                previousCapturedAtElapsedMs = 1_000L,
                currentCapturedAtElapsedMs = 30_000L,
                previousSource = "AAP_CLASSIC_0x0006",
                currentSource = "AAP_CLASSIC_0x0006",
            ),
        )
    }

    @Test
    fun periodicBleGapStartsANewWearBaseline() {
        assertEquals(
            true,
            shouldResetWearTransition(
                previousCapturedAtElapsedMs = 1_000L,
                currentCapturedAtElapsedMs = 30_000L,
                previousSource = "BLE_PUBLIC_EAR_STATE",
                currentSource = "BLE_PUBLIC_EAR_STATE",
            ),
        )
    }

    @Test
    fun removalPausesOnlyWhenMediaWasPlayingAndInsertionResumesIt() {
        val policy = WearPlaybackPolicy()

        assertEquals(
            WearMediaAction.NONE,
            policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.LEFT_IN_EAR, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PLAY,
            policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = false),
        )
    }

    @Test
    fun unknownFramesDoNotCreateFalsePauseOrPlay() {
        val policy = WearPlaybackPolicy()

        policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = true)
        assertEquals(
            WearMediaAction.NONE,
            policy.onWearStateChanged(AirPodsWearState.UNKNOWN, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.NONE_IN_EAR, mediaWasPlaying = true),
        )
    }

    @Test
    fun caseStateIsAnOutOfEarTransition() {
        val policy = WearPlaybackPolicy()

        policy.onWearStateChanged(AirPodsWearState.RIGHT_IN_EAR, mediaWasPlaying = true)
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.IN_CASE, mediaWasPlaying = true),
        )
    }

    @Test
    fun initiallyStoppedMediaIsNeverPlayedWhenPodsAreInserted() {
        val policy = WearPlaybackPolicy()

        assertEquals(
            WearMediaAction.NONE,
            policy.onWearStateChanged(AirPodsWearState.NONE_IN_EAR, mediaWasPlaying = false),
        )
        assertEquals(
            WearMediaAction.NONE,
            policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = false),
        )
    }

    @Test
    fun partialInEarStateUsesTheSamePausePolicyWithoutToggling() {
        val policy = WearPlaybackPolicy()

        policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = true)
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.PARTIAL_IN_EAR, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PLAY,
            policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = false),
        )
    }

    @Test
    fun leftOrRightOnlyEarbudCanPauseAndResumeIndependently() {
        listOf(
            AirPodsWearState.LEFT_IN_EAR,
            AirPodsWearState.RIGHT_IN_EAR,
        ).forEach { singleEarState ->
            val policy = WearPlaybackPolicy()

            assertEquals(
                WearMediaAction.NONE,
                policy.onWearStateChanged(singleEarState, mediaWasPlaying = true),
            )
            assertEquals(
                WearMediaAction.PAUSE,
                policy.onWearStateChanged(AirPodsWearState.NONE_IN_EAR, mediaWasPlaying = true),
            )
            assertEquals(
                WearMediaAction.PLAY,
                policy.onWearStateChanged(singleEarState, mediaWasPlaying = false),
            )
        }
    }

    @Test
    fun oneEarbudInCaseCanPauseAndResumeWithoutTheOtherEarbud() {
        val policy = WearPlaybackPolicy()

        assertEquals(
            WearMediaAction.NONE,
            policy.onWearStateChanged(AirPodsWearState.LEFT_IN_EAR, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.IN_CASE, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PLAY,
            policy.onWearStateChanged(AirPodsWearState.RIGHT_IN_EAR, mediaWasPlaying = false),
        )
    }

    @Test
    fun partialWearEvidenceUsesTheOneEarbudTransition() {
        val policy = WearPlaybackPolicy()

        policy.onWearStateChanged(AirPodsWearState.PARTIAL_IN_EAR, mediaWasPlaying = true)
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.NONE_IN_EAR, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PLAY,
            policy.onWearStateChanged(AirPodsWearState.PARTIAL_IN_EAR, mediaWasPlaying = false),
        )
    }

    @Test
    fun removingTheOnlyLeftOrRightBudPausesAndReinsertingThatSameBudPlays() {
        listOf(
            AirPodsWearState.LEFT_IN_EAR,
            AirPodsWearState.RIGHT_IN_EAR,
        ).forEach { oneBudInEar ->
            val policy = WearPlaybackPolicy()

            assertEquals(
                WearMediaAction.NONE,
                policy.onWearStateChanged(oneBudInEar, mediaWasPlaying = true),
            )
            assertEquals(
                WearMediaAction.PAUSE,
                policy.onWearStateChanged(AirPodsWearState.IN_CASE, mediaWasPlaying = true),
            )
            assertEquals(
                WearMediaAction.PLAY,
                policy.onWearStateChanged(oneBudInEar, mediaWasPlaying = false),
            )
        }
    }

    @Test
    fun conflictAndUnknownNeverAdvanceTheWearTransition() {
        val policy = WearPlaybackPolicy()

        policy.onWearStateChanged(AirPodsWearState.BOTH_IN_EAR, mediaWasPlaying = true)
        assertEquals(
            WearMediaAction.NONE,
            policy.onWearStateChanged(AirPodsWearState.CONFLICT, mediaWasPlaying = true),
        )
        assertEquals(
            WearMediaAction.PAUSE,
            policy.onWearStateChanged(AirPodsWearState.NONE_IN_EAR, mediaWasPlaying = true),
        )
    }
}
