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
 * AAP 0x0006 is event-driven, so two valid AAP observations may be separated
 * by a quiet period longer than the public BLE freshness window. Public BLE
 * observations are periodic; a long gap there must start a new baseline.
 */
internal fun shouldResetWearTransition(
    previousCapturedAtElapsedMs: Long?,
    currentCapturedAtElapsedMs: Long,
    previousSource: String?,
    currentSource: String?,
    maxGapMs: Long = 15_000L,
): Boolean {
    if (previousCapturedAtElapsedMs == null) return false
    if (currentCapturedAtElapsedMs <= previousCapturedAtElapsedMs) return true
    if (currentCapturedAtElapsedMs - previousCapturedAtElapsedMs <= maxGapMs) return false
    return previousSource != "AAP_CLASSIC_0x0006" || currentSource != "AAP_CLASSIC_0x0006"
}

/** What the controller can prove about the media app at this instant. */
internal enum class MediaPlaybackObservation {
    PLAYING,
    NOT_PLAYING,
    UNKNOWN,
}

/** UNKNOWN is not proof that a media app is playing. */
internal fun mediaWasPlayingFromObservation(
    observation: MediaPlaybackObservation,
): Boolean = observation == MediaPlaybackObservation.PLAYING

internal fun AirPodsState.hasFreshWearEvidenceAt(nowElapsedMs: Long): Boolean {
    if (wearState == AirPodsWearState.UNKNOWN || wearState == AirPodsWearState.CONFLICT) {
        return false
    }
    val profileMatches = deviceProfileId != null &&
        wearDeviceProfileId == deviceProfileId
    val sourcePresent = !wearSource.isNullOrBlank()
    val elapsedFresh = wearCapturedAtElapsedMs != null &&
        wearExpiresAtElapsedMs != null &&
        nowElapsedMs >= wearCapturedAtElapsedMs &&
        nowElapsedMs <= wearExpiresAtElapsedMs
    return profileMatches && sourcePresent && elapsedFresh
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

    /** Allows the route gate to admit only a safe removal during route lag. */
    fun isRemovalCandidate(state: AirPodsWearState): Boolean {
        val currentCount = state.toEarCount() ?: return false
        return previousEarCount?.let { currentCount < it } == true
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
    private var pausedWithMediaKeyFallback = false
    private var pausedWithMediaKeyAtElapsedMs: Long? = null
    private var lastWearEvidenceCapturedAtElapsedMs: Long? = null
    private var lastWearEvidenceSource: String? = null
    private var lastWearEvidenceState: AirPodsWearState? = null
    private var lastTargetRouteAtElapsedMs: Long? = null
    private var lastMediaWasPlaying = false

    suspend fun onWearStateChanged(state: AirPodsState) = actionMutex.withLock {
        if (!state.hasFreshWearEvidence()) {
            policy.reset()
            clearPausedSession()
            lastWearEvidenceCapturedAtElapsedMs = null
            lastWearEvidenceSource = null
            lastWearEvidenceState = null
            Log.i(
                TAG,
                "wear_state ignored state=${state.wearState} reason=STALE_OR_UNVERIFIED_EVIDENCE",
            )
            return@withLock
        }
        val capturedAtElapsedMs = state.wearCapturedAtElapsedMs!!
        val previousCapturedAtElapsedMs = lastWearEvidenceCapturedAtElapsedMs
        if (capturedAtElapsedMs == previousCapturedAtElapsedMs &&
            state.wearSource == lastWearEvidenceSource &&
            state.wearState == lastWearEvidenceState
        ) {
            // The monitor polls the route independently of wear events. The
            // same sample must be retryable before route proof arrives, but
            // must not trigger duplicate media actions after it is accepted.
            return@withLock
        }
        if (shouldResetWearTransition(
                previousCapturedAtElapsedMs = previousCapturedAtElapsedMs,
                currentCapturedAtElapsedMs = capturedAtElapsedMs,
                previousSource = lastWearEvidenceSource,
                currentSource = state.wearSource,
            )
        ) {
            // Public BLE advertisements are periodic and a gap means the
            // previous state can no longer safely define a transition. AAP
            // 0x0006 is different: it is an event-driven notification and can
            // legitimately remain silent for minutes while the user keeps a
            // single bud in the ear. Keep the in-memory baseline for that
            // source only; persisted wear state is still rejected as stale at
            // the entry point above and never initializes this policy.
            policy.reset()
            clearPausedSession()
            lastWearEvidenceCapturedAtElapsedMs = null
            lastWearEvidenceSource = null
            lastWearEvidenceState = null
        }
        val route = routeGate.check(state)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val routeWasRecentlyActive = lastTargetRouteAtElapsedMs?.let { lastActive ->
            nowElapsedMs - lastActive in 0..ROUTE_DROP_GRACE_MS
        } == true
        if (!route.activeForTarget && !routeWasRecentlyActive) {
            // A nearby-only advertisement must never advance the media policy.
            // Keep the policy anchored to an actually active Android route.
            policy.reset()
            clearPausedSession()
            Log.i(
                TAG,
                "wear_state ignored state=${state.wearState} reason=ANDROID_ROUTE_UNAVAILABLE",
            )
            return@withLock
        }
        val routeCanEvaluate = route.activeForTarget ||
            // A disappearing output can precede the final removal frame. In
            // that narrow grace window allow a proven removal to pause, but
            // defer insertion/resume until the target route is visible again.
            (routeWasRecentlyActive &&
                (route.profileConnected || policy.isRemovalCandidate(state.wearState)))
        if (!routeCanEvaluate) {
            Log.i(
                TAG,
                "wear_state deferred state=${state.wearState} reason=ROUTE_PROOF_PENDING",
            )
            return@withLock
        }
        lastWearEvidenceCapturedAtElapsedMs = capturedAtElapsedMs
        lastWearEvidenceSource = state.wearSource
        lastWearEvidenceState = state.wearState
        val currentSession = sessionResolver.currentPlaying()
        val mediaPlayingNow = currentSession != null || audioManager?.isMusicActive == true
        val mediaObservation = mediaPlaybackObservation(currentSession)
        val mediaWasPlaying = if (route.activeForTarget) {
            // Unknown playback is not evidence of PLAYING. This prevents a
            // missing NotificationListener/AudioManager signal from pausing
            // an already-stopped app and registering a false auto-resume.
            mediaWasPlayingFromObservation(mediaObservation)
        } else {
            // On Samsung, removing the only active bud can make the output
            // disappear before the final OUT_OF_EAR frame is delivered. The
            // route timestamp and the previous playback observation provide a
            // narrow, target-specific grace window for that removal only.
            lastMediaWasPlaying || mediaPlayingNow
        }
        if (route.activeForTarget) {
            lastTargetRouteAtElapsedMs = nowElapsedMs
            lastMediaWasPlaying = mediaPlayingNow
        }
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
                "mediaObservation=$mediaObservation " +
                "routeGrace=$routeWasRecentlyActive " +
                "session=${currentSession?.packageName ?: "none"} " +
                "availability=${sessionResolver.availability()}",
        )

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
        lastWearEvidenceSource = null
        lastWearEvidenceState = null
        lastTargetRouteAtElapsedMs = null
        lastMediaWasPlaying = false
    }

    /**
     * A profile poll can briefly report NEARBY/CONNECTION_PENDING while a
     * single AirPod is still the active Android output.  Do not throw away a
     * pending auto-resume token until the output gate actually proves that the
     * route is gone.  The state-clear path below runs after the short grace
     * window expires.
     */
    suspend fun onConnectionEvidenceChanged(state: AirPodsState) = actionMutex.withLock {
        val route = routeGate.check(state)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        if (!route.activeForTarget) {
            val recentlyActive = lastTargetRouteAtElapsedMs?.let { lastActive ->
                nowElapsedMs - lastActive in 0..ROUTE_DROP_GRACE_MS
            } == true
            if (recentlyActive) {
                // Keep the policy alive for the final one-bud wear frame.
                Log.i(
                    TAG,
                    "wear_media_connection route=false action_state=grace " +
                        "connectionState=${state.connectionState}",
                )
            } else if (state.connectionState == com.galaxyairpods.domain.model.AirPodsConnectionState.ANDROID_CONNECTED &&
                lastTargetRouteAtElapsedMs == null
            ) {
                // The profile callback can arrive before AudioDeviceInfo is
                // updated on service start. Wait for the next route poll,
                // but do not keep an already-expired route alive forever.
                Log.d(TAG, "wear_media_connection route=pending_profile_output")
            } else {
                reset()
                Log.i(
                    TAG,
                    "wear_media_connection route=false action_state=cleared " +
                        "connectionState=${state.connectionState}",
                )
            }
        } else {
            lastTargetRouteAtElapsedMs = nowElapsedMs
            Log.d(
                TAG,
                "wear_media_connection route=true action_state_retained=" +
                    "${state.connectionState}",
            )
        }
    }

    private suspend fun pause(session: MediaController?) {
        if (session == null) {
            // Notification access may be disabled or the media app may expose
            // its session a little later than the wear event.  The explicit
            // PAUSE key is still a valid fallback; retain a guarded fallback
            // marker so a subsequent one-ear insertion can send PLAY exactly
            // once instead of silently losing the resume transition.
            val dispatched = dispatch(KeyEvent.KEYCODE_MEDIA_PAUSE)
            val verified = dispatched && waitForGlobalNotPlaying()
            if (verified) {
                pausedWithMediaKeyFallback = true
                pausedWithMediaKeyAtElapsedMs = SystemClock.elapsedRealtime()
                Log.i(
                    TAG,
                    "wear_media_action action=PAUSE path=MEDIA_KEY " +
                        "verified=$verified reason=MEDIA_SESSION_UNAVAILABLE",
                )
            } else {
                policy.cancelResume()
                clearPausedSession()
                Log.i(
                    TAG,
                    "wear_media_action action=PAUSE path=MEDIA_KEY " +
                        "verified=false reason=NO_SESSION_OR_NO_STATE_CHANGE",
                )
            }
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
            if (!pausedWithMediaKeyFallback) {
                policy.cancelResume()
                Log.i(
                    TAG,
                    "wear_media_action action=PLAY path=BLOCKED verified=false " +
                        "reason=MEDIA_SESSION_UNAVAILABLE",
                )
                return
            }

            // If another session is already playing, the user or another app
            // has taken over the route. Never toggle it with a global key.
            val fallbackPauseAge = pausedWithMediaKeyAtElapsedMs?.let {
                SystemClock.elapsedRealtime() - it
            }
            if (sessionResolver.currentPlaying() != null ||
                (audioManager?.isMusicActive == true &&
                    fallbackPauseAge != null && fallbackPauseAge > MEDIA_KEY_RESUME_GRACE_MS)
            ) {
                Log.i(
                    TAG,
                    "wear_media_action action=PLAY path=BLOCKED verified=false " +
                        "reason=OTHER_SESSION_PLAYING",
                )
                clearPausedSession()
                return
            }

            val dispatched = dispatch(KeyEvent.KEYCODE_MEDIA_PLAY)
            val verified = dispatched && waitForGlobalPlaying()
            Log.i(
                TAG,
                "wear_media_action action=PLAY path=MEDIA_KEY verified=$verified " +
                    "reason=MEDIA_SESSION_UNAVAILABLE",
            )
            clearPausedSession()
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
        waitForState(token) { it.isActivePlaybackState() }

    private suspend fun waitForNotPlaying(token: MediaSession.Token): Boolean =
        waitForState(token) { !it.isActivePlaybackState() }

    private suspend fun waitForGlobalPlaying(): Boolean {
        repeat(6) {
            if (audioManager?.isMusicActive == true) return true
            delay(120L)
        }
        return false
    }

    private suspend fun waitForGlobalNotPlaying(): Boolean {
        repeat(6) {
            if (audioManager?.isMusicActive == false) return true
            delay(120L)
        }
        return false
    }

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
        pausedWithMediaKeyFallback = false
        pausedWithMediaKeyAtElapsedMs = null
    }

    private fun mediaPlaybackObservation(
        currentSession: MediaController?,
    ): MediaPlaybackObservation = when {
        currentSession != null || audioManager?.isMusicActive == true ->
            MediaPlaybackObservation.PLAYING
        // A false AudioManager signal is explicit enough to classify the
        // current route as not playing even when notification access is off.
        audioManager?.isMusicActive == false ||
            sessionResolver.availability() == MediaSessionAvailability.AVAILABLE ->
            MediaPlaybackObservation.NOT_PLAYING
        else -> MediaPlaybackObservation.UNKNOWN
    }

    private fun AirPodsState.hasFreshWearEvidence(): Boolean {
        return hasFreshWearEvidenceAt(SystemClock.elapsedRealtime())
    }

    private fun dispatch(keyCode: Int): Boolean {
        val manager = audioManager ?: return false
        Log.i(TAG, "wear_media_action action=${KeyEvent.keyCodeToString(keyCode)} path=MEDIA_KEY")
        return runCatching {
            // Android treats the pair as one button gesture. Sending only
            // ACTION_DOWN is ignored by some Samsung media stacks.
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            manager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        }.isSuccess
    }

    private companion object {
        const val TAG = "AirPodsWearMedia"
        const val ROUTE_DROP_GRACE_MS = 3_000L
        const val MEDIA_KEY_RESUME_GRACE_MS = 3_000L
    }
}
