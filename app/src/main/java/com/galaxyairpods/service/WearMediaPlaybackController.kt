package com.galaxyairpods.service

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.AirPodsWearState
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The only actions the wear policy can request from the active media app. */
internal enum class WearMediaAction {
    NONE,
    PAUSE,
    PLAY,
}

/**
 * Converts stable physical ear-state transitions into media actions.
 *
 * This class is deliberately platform-free. It never toggles playback and it
 * only requests PLAY when the removal transition previously proved that media
 * was playing and the controller recorded an automatic pause.
 */
internal class WearPlaybackPolicy {
    private var previousEarCount: Int? = null
    private var resumeAfterRemoval = false

    fun onWearStateChanged(
        state: AirPodsWearState,
        mediaWasPlaying: Boolean,
    ): WearMediaAction {
        val currentCount = state.toEarCount() ?: return WearMediaAction.NONE
        val previous = previousEarCount
        previousEarCount = currentCount
        if (previous == null || previous == currentCount) return WearMediaAction.NONE

        val removed = currentCount < previous
        val inserted = currentCount > previous
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

    fun cancelResume() {
        resumeAfterRemoval = false
    }

    fun reset() {
        previousEarCount = null
        resumeAfterRemoval = false
    }
}

private fun AirPodsWearState.toEarCount(): Int? = when (this) {
    AirPodsWearState.LEFT_IN_EAR,
    AirPodsWearState.RIGHT_IN_EAR,
    AirPodsWearState.PARTIAL_IN_EAR,
    -> 1
    AirPodsWearState.BOTH_IN_EAR -> 2
    AirPodsWearState.NONE_IN_EAR,
    AirPodsWearState.IN_CASE,
    -> 0
    AirPodsWearState.UNKNOWN,
    AirPodsWearState.CONFLICT,
    -> null
}

/**
 * Executes a validated policy action only while the requested AirPods are the
 * current Android Bluetooth audio route.
 */
internal class WearMediaPlaybackController(
    context: Context,
    private val routeGate: AndroidAudioRouteGate = AndroidAudioRouteGate(context),
    private val sessionResolver: MediaSessionResolver = MediaSessionResolver(),
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val policy = WearPlaybackPolicy()
    private val actionMutex = Mutex()
    private var pausedSessionToken: MediaSession.Token? = null
    private var pausedPackageName: String? = null
    private var pausedPositionUpdateTime: Long? = null
    private var lastWearEvidenceCapturedAtElapsedMs: Long? = null

    suspend fun onWearStateChanged(state: AirPodsState) = actionMutex.withLock {
        if (!state.hasFreshWearEvidence()) {
            policy.reset()
            clearPausedSession()
            lastWearEvidenceCapturedAtElapsedMs = null
            Log.i(
                TAG,
                "wear_state ignored state=${state.wearState} reason=STALE_OR_UNVERIFIED_EVIDENCE",
            )
            return@withLock
        }
        val capturedAtElapsedMs = state.wearCapturedAtElapsedMs!!
        val previousCapturedAtElapsedMs = lastWearEvidenceCapturedAtElapsedMs
        if (previousCapturedAtElapsedMs != null &&
            (capturedAtElapsedMs <= previousCapturedAtElapsedMs ||
                capturedAtElapsedMs - previousCapturedAtElapsedMs > MAX_WEAR_EVIDENCE_GAP_MS)
        ) {
            // A state that arrives after a freshness hole is a new observation,
            // not a continuation of the old transition. In particular, it
            // must not turn an old auto-pause token into an unexpected play.
            policy.reset()
            clearPausedSession()
        }
        lastWearEvidenceCapturedAtElapsedMs = capturedAtElapsedMs
        val route = routeGate.check(state)
        val currentSession = sessionResolver.currentPlaying()
        val mediaWasPlaying = route.activeForTarget &&
            (currentSession != null || audioManager?.isMusicActive == true)
        val action = policy.onWearStateChanged(
            state = state.wearState,
            mediaWasPlaying = mediaWasPlaying,
        )
        Log.i(
            TAG,
            "wear_state state=${state.wearState} route=${route.activeForTarget} " +
                "profile=${route.profileConnected} bluetoothOutput=${route.outputTypeIsBluetooth} " +
                "addressMatch=${route.outputAddressMatches} nameMatch=${route.outputNameMatches} " +
                "mediaWasPlaying=$mediaWasPlaying action=$action " +
                "session=${currentSession?.packageName ?: "none"} " +
                "availability=${sessionResolver.availability()}",
        )

        if (!route.activeForTarget) {
            policy.cancelResume()
            return@withLock
        }

        when (action) {
            WearMediaAction.PAUSE -> pause(currentSession)
            WearMediaAction.PLAY -> resumeIfSameSession()
            WearMediaAction.NONE -> Unit
        }
    }

    fun reset() {
        policy.reset()
        clearPausedSession()
        lastWearEvidenceCapturedAtElapsedMs = null
    }

    private suspend fun pause(session: MediaController?) {
        if (session == null) {
            // We can safely pause the global route, but without a session token
            // Android gives us no proof that a later PLAY targets the same app.
            dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)
            policy.cancelResume()
            Log.i(TAG, "wear_media_action action=PAUSE path=MEDIA_KEY verified=false reason=NO_SESSION")
            return
        }

        val token = session.sessionToken
        var path = "MEDIA_SESSION"
        var verified = false
        if (sessionResolver.canPause(session)) {
            verified = runCatching {
                session.transportControls.pause()
            }.isSuccess && waitForNotPlaying(token)
        }
        if (!verified) {
            path = "MEDIA_KEY"
            dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)
            verified = waitForNotPlaying(token)
        }

        if (verified) {
            pausedSessionToken = token
            pausedPackageName = session.packageName
            pausedPositionUpdateTime = sessionResolver.findByToken(token)
                ?.playbackState
                ?.lastPositionUpdateTime
            Log.i(
                TAG,
                "wear_media_action action=PAUSE path=$path verified=true package=${session.packageName}",
            )
        } else {
            clearPausedSession()
            policy.cancelResume()
            Log.w(
                TAG,
                "wear_media_action action=PAUSE path=$path verified=false package=${session.packageName}",
            )
        }
    }

    private suspend fun resumeIfSameSession() {
        val token = pausedSessionToken
        if (token == null) {
            policy.cancelResume()
            Log.i(
                TAG,
                "wear_media_action action=PLAY path=BLOCKED verified=false " +
                    "reason=MEDIA_SESSION_UNAVAILABLE",
            )
            return
        }

        val session = sessionResolver.findByToken(token)
        val playback = session?.playbackState
        val otherPlaying = sessionResolver.currentPlaying()?.let { it.sessionToken != token } == true
        val stateChangedAfterPause = playback?.lastPositionUpdateTime?.let { updateTime ->
            pausedPositionUpdateTime?.let { saved -> updateTime > saved }
        } == true
        if (session == null || playback == null || otherPlaying || stateChangedAfterPause ||
            playback.state != PlaybackState.STATE_PAUSED ||
            (pausedPackageName != null && session.packageName != pausedPackageName)
        ) {
            Log.i(
                TAG,
                "wear_media_action action=PLAY path=BLOCKED verified=false " +
                    "reason=SESSION_CHANGED_OR_NOT_PAUSED package=${session?.packageName ?: "none"}",
            )
            clearPausedSession()
            return
        }

        var path = "MEDIA_SESSION"
        var verified = false
        if (sessionResolver.canPlay(session)) {
            verified = runCatching {
                session.transportControls.play()
            }.isSuccess && waitForPlaying(token)
        }
        if (!verified) {
            path = "MEDIA_KEY"
            dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)
            verified = waitForPlaying(token)
        }

        Log.i(
            TAG,
            "wear_media_action action=PLAY path=$path verified=$verified " +
                "package=${session.packageName}",
        )
        clearPausedSession()
    }

    private suspend fun waitForPlaying(token: MediaSession.Token): Boolean =
        waitForState(token) { it == PlaybackState.STATE_PLAYING }

    private suspend fun waitForNotPlaying(token: MediaSession.Token): Boolean =
        waitForState(token) { it != PlaybackState.STATE_PLAYING }

    private suspend fun waitForState(
        token: MediaSession.Token,
        predicate: (Int) -> Boolean,
    ): Boolean {
        repeat(4) {
            val state = sessionResolver.findByToken(token)?.playbackState?.state
            if (state != null && predicate(state)) return true
            delay(120L)
        }
        return false
    }

    private fun clearPausedSession() {
        pausedSessionToken = null
        pausedPackageName = null
        pausedPositionUpdateTime = null
    }

    private fun AirPodsState.hasFreshWearEvidence(): Boolean {
        if (wearState == AirPodsWearState.UNKNOWN || wearState == AirPodsWearState.CONFLICT) {
            return false
        }
        val profileMatches = deviceProfileId != null &&
            wearDeviceProfileId == deviceProfileId
        val now = SystemClock.elapsedRealtime()
        val elapsedFresh = wearCapturedAtElapsedMs != null &&
            wearExpiresAtElapsedMs != null &&
            now >= wearCapturedAtElapsedMs &&
            now <= wearExpiresAtElapsedMs
        return profileMatches && elapsedFresh
    }

    private fun dispatch(keyCode: Int) {
        val manager = audioManager ?: return
        Log.i(TAG, "wear_media_action action=${KeyEvent.keyCodeToString(keyCode)} path=MEDIA_KEY")
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private companion object {
        const val TAG = "AirPodsWearMedia"
        const val MAX_WEAR_EVIDENCE_GAP_MS = 15_000L
    }
}
