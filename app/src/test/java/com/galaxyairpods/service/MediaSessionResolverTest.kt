package com.galaxyairpods.service

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSessionResolverTest {
    @Test
    fun choosesTheMostRecentlyUpdatedPlayingSessionInsteadOfIndexZero() {
        val selected = selectBestPlayingSession(
            listOf(
                MediaSessionCandidate(
                    packageName = "com.example.first",
                    state = PlaybackState.STATE_PLAYING,
                    actions = PlaybackState.ACTION_PAUSE,
                    lastPositionUpdateTime = 100L,
                ),
                MediaSessionCandidate(
                    packageName = "com.example.current",
                    state = PlaybackState.STATE_PLAYING,
                    actions = PlaybackState.ACTION_PAUSE,
                    lastPositionUpdateTime = 200L,
                ),
            ),
        )

        assertEquals("com.example.current", selected?.packageName)
    }

    @Test
    fun ignoresPausedSessionsWhenFindingCurrentPlayback() {
        assertNull(
            selectBestPlayingSession(
                listOf(
                    MediaSessionCandidate(
                        packageName = "com.example.paused",
                        state = PlaybackState.STATE_PAUSED,
                        actions = PlaybackState.ACTION_PLAY,
                        lastPositionUpdateTime = 300L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun prefersExplicitPauseSupportBeforeRecency() {
        val selected = selectBestPlayingSession(
            listOf(
                MediaSessionCandidate(
                    packageName = "com.example.toggle-only",
                    state = PlaybackState.STATE_PLAYING,
                    actions = PlaybackState.ACTION_PLAY_PAUSE,
                    lastPositionUpdateTime = 500L,
                ),
                MediaSessionCandidate(
                    packageName = "com.example.pause",
                    state = PlaybackState.STATE_PLAYING,
                    actions = PlaybackState.ACTION_PAUSE,
                    lastPositionUpdateTime = 400L,
                ),
            ),
        )

        assertEquals("com.example.pause", selected?.packageName)
    }

    @Test
    fun bufferingMediaIsStillAValidPauseTarget() {
        val selected = selectBestPlayingSession(
            listOf(
                MediaSessionCandidate(
                    packageName = "com.example.video",
                    state = PlaybackState.STATE_BUFFERING,
                    actions = PlaybackState.ACTION_PAUSE,
                    lastPositionUpdateTime = 800L,
                ),
            ),
        )

        assertEquals("com.example.video", selected?.packageName)
    }
}
