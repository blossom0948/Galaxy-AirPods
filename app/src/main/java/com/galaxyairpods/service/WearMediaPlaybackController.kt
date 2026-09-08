package com.galaxyairpods.service

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.view.KeyEvent
import android.util.Log
import com.galaxyairpods.domain.model.AirPodsWearState

/** The only actions the wear policy can request from the active media app. */
internal enum class WearMediaAction {
    NONE,
    PAUSE,
    PLAY,
}

/**
 * Converts stable physical ear-state transitions into media actions.
 *
 * Unknown/conflict frames do not advance the previous stable state. This is
 * important because a short AAP/BLE gap must not pause a video by itself.
 */
internal class WearPlaybackPolicy {
    private var previousMask: Int? = null
    private var resumeAfterRemoval = false

    fun onWearStateChanged(
        state: AirPodsWearState,
        mediaWasPlaying: Boolean,
    ): WearMediaAction {
        val currentMask = state.toEarMask() ?: return WearMediaAction.NONE
        val previous = previousMask
        previousMask = currentMask
        if (previous == null || previous == currentMask) return WearMediaAction.NONE

        val removed = (previous and currentMask.inv()) != 0
        val inserted = (currentMask and previous.inv()) != 0
        return when {
            removed -> {
                resumeAfterRemoval = mediaWasPlaying
                if (mediaWasPlaying) WearMediaAction.PAUSE else WearMediaAction.NONE
            }
            inserted && resumeAfterRemoval -> {
                resumeAfterRemoval = false
                WearMediaAction.PLAY
            }
            inserted -> WearMediaAction.NONE
            else -> WearMediaAction.NONE
        }
    }

    fun reset() {
        previousMask = null
        resumeAfterRemoval = false
    }
}

private fun AirPodsWearState.toEarMask(): Int? = when (this) {
    AirPodsWearState.LEFT_IN_EAR -> 0b01
    AirPodsWearState.RIGHT_IN_EAR -> 0b10
    AirPodsWearState.BOTH_IN_EAR -> 0b11
    AirPodsWearState.NONE_IN_EAR,
    AirPodsWearState.IN_CASE,
    -> 0
    AirPodsWearState.UNKNOWN,
    AirPodsWearState.CONFLICT,
    -> null
}

/**
 * Sends the same global media-button events that a headset button sends. It
 * works with music and video apps without NotificationListener permission and
 * only resumes playback when that app was active immediately before removal.
 */
internal class WearMediaPlaybackController(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val policy = WearPlaybackPolicy()
    private var pausedSession: MediaController? = null

    fun onWearStateChanged(state: AirPodsWearState) {
        val playingSession = currentPlayingSession()
        val mediaWasPlaying = playingSession != null || audioManager?.isMusicActive == true
        val action = policy.onWearStateChanged(
            state = state,
            mediaWasPlaying = mediaWasPlaying,
        )
        Log.i(
            TAG,
            "wear_state state=$state mediaWasPlaying=$mediaWasPlaying action=$action " +
                "session=${playingSession?.packageName ?: "none"}",
        )
        when (action) {
            WearMediaAction.PAUSE -> pause(playingSession)
            WearMediaAction.PLAY -> play()
            WearMediaAction.NONE -> Unit
        }
    }

    fun reset() {
        policy.reset()
        pausedSession = null
    }

    private fun currentPlayingSession(): MediaController? =
        AirPodsMediaNotificationListener.current()
            ?.activeControllers()
            ?.firstOrNull { controller ->
                controller.playbackState?.state == PlaybackState.STATE_PLAYING
            }

    private fun pause(session: MediaController?) {
        if (session != null) {
            runCatching {
                session.transportControls.pause()
                pausedSession = session
                Log.i(TAG, "wear_media_action action=PAUSE path=MEDIA_SESSION package=${session.packageName}")
            }.onFailure {
                dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)
            }
        } else {
            dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)
        }
    }

    private fun play() {
        val session = pausedSession
        if (session != null) {
            runCatching {
                session.transportControls.play()
                pausedSession = null
                Log.i(TAG, "wear_media_action action=PLAY path=MEDIA_SESSION package=${session.packageName}")
            }.onFailure {
                pausedSession = null
                dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)
            }
        } else {
            dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)
        }
    }

    private fun dispatch(keyCode: Int) {
        val manager = audioManager ?: return
        Log.i(TAG, "wear_media_action action=${KeyEvent.keyCodeToString(keyCode)} path=MEDIA_KEY")
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {
        const val TAG = "AirPodsWearMedia"
    }
}
