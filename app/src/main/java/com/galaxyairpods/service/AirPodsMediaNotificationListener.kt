package com.galaxyairpods.service

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.os.Build
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Optional exact media-session bridge. The app still has a media-key fallback,
 * so playback control does not depend on this permission, but an enabled
 * notification listener lets Android expose the active music/video session.
 */
class AirPodsMediaNotificationListener : NotificationListenerService() {
    @Volatile
    private var listenerConnected = false

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        instance = this
        Log.i(TAG, "media_listener connected")
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        if (instance === this) instance = null
        Log.i(TAG, "media_listener disconnected")
        super.onListenerDisconnected()
    }

    fun activeControllers(): List<MediaController> {
        if (!listenerConnected) return emptyList()
        return runCatching {
            getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(
                    ComponentName(this, AirPodsMediaNotificationListener::class.java),
                )
                .orEmpty()
        }.onFailure {
            Log.w(TAG, "media_sessions unavailable", it)
        }.getOrDefault(emptyList())
    }

    /**
     * API 33+ exposes the session that receives hardware/media-key events.
     * The resolver compares this token with the active-session list before it
     * chooses a controller, so a fallback key cannot target an unrelated app.
     */
    fun mediaKeyEventSessionToken(): MediaSession.Token? {
        if (!listenerConnected || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            getSystemService(MediaSessionManager::class.java)?.mediaKeyEventSession
        }.onFailure {
            Log.w(TAG, "media_key_session unavailable", it)
        }.getOrNull()
    }

    companion object {
        @Volatile
        private var instance: AirPodsMediaNotificationListener? = null

        fun current(): AirPodsMediaNotificationListener? = instance

        private const val TAG = "AirPodsMediaSession"
    }
}
