package com.galaxyairpods.service

import android.media.session.MediaController
import android.media.session.PlaybackState

internal enum class MediaSessionAvailability {
    AVAILABLE,
    MEDIA_SESSION_UNAVAILABLE,
}

internal data class MediaSessionCandidate(
    val packageName: String,
    val state: Int,
    val actions: Long,
    val lastPositionUpdateTime: Long,
)

internal fun selectBestPlayingSession(
    candidates: List<MediaSessionCandidate>,
): MediaSessionCandidate? = candidates
    .asSequence()
    .filter { it.state == PlaybackState.STATE_PLAYING }
    .maxWithOrNull(
        compareBy<MediaSessionCandidate> {
            it.actions and PlaybackState.ACTION_PAUSE
        }.thenBy { it.lastPositionUpdateTime }
            .thenBy { it.packageName },
    )

/**
 * Resolves sessions only through the connected NotificationListenerService.
 * There is deliberately no direct MediaSessionManager query from the monitor
 * service: Android requires the listener component for this access path.
 */
internal class MediaSessionResolver(
    private val listenerProvider: () -> AirPodsMediaNotificationListener? =
        { AirPodsMediaNotificationListener.current() },
) {
    fun availability(): MediaSessionAvailability =
        if (listenerProvider() == null) {
            MediaSessionAvailability.MEDIA_SESSION_UNAVAILABLE
        } else {
            MediaSessionAvailability.AVAILABLE
        }

    fun activeControllers(): List<MediaController> = listenerProvider()
        ?.activeControllers()
        .orEmpty()

    fun findByToken(token: android.media.session.MediaSession.Token): MediaController? =
        activeControllers().firstOrNull { it.sessionToken == token }

    fun currentPlaying(): MediaController? {
        val listener = listenerProvider() ?: return null
        val controllers = listener.activeControllers()
        val mediaKeyController = listener.mediaKeyEventSessionToken()?.let { token ->
            controllers.firstOrNull { it.sessionToken == token }
        }
        if (mediaKeyController?.playbackState?.state == PlaybackState.STATE_PLAYING) {
            return mediaKeyController
        }
        val selected = selectBestPlayingSession(
            controllers.mapNotNull { controller ->
                controller.playbackState?.let { playback ->
                    MediaSessionCandidate(
                        packageName = controller.packageName,
                        state = playback.state,
                        actions = playback.actions,
                        lastPositionUpdateTime = playback.lastPositionUpdateTime,
                    )
                }
            },
        ) ?: return null
        return controllers.firstOrNull {
            it.packageName == selected.packageName &&
                it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                it.playbackState?.lastPositionUpdateTime == selected.lastPositionUpdateTime
        }
    }

    fun canPause(controller: MediaController): Boolean =
        // ACTION_PLAY_PAUSE is a toggle. The wear policy only uses explicit
        // pause/play commands so a duplicate frame can never invert playback.
        controller.playbackState?.actions?.supports(PlaybackState.ACTION_PAUSE) == true

    fun canPlay(controller: MediaController): Boolean =
        controller.playbackState?.actions?.supports(PlaybackState.ACTION_PLAY) == true

    private fun Long.supports(action: Long): Boolean = (this and action) != 0L
}
