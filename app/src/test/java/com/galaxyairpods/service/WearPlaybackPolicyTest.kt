package com.galaxyairpods.service

import com.galaxyairpods.domain.model.AirPodsWearState
import org.junit.Assert.assertEquals
import org.junit.Test

class WearPlaybackPolicyTest {
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
}
