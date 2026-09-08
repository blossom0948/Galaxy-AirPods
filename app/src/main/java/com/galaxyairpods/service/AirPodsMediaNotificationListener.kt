package com.galaxyairpods.service

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Optional exact media-session bridge. The app still has a media-key fallback,
 * so playback control does not depend on this permission, but an enabled
 * notification listener lets Android expose the active music/video session.
 */
class AirPodsMediaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "media_listener connected")
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        Log.i(TAG, "media_listener disconnected")
        super.onListenerDisconnected()
    }

    fun activeControllers(): List<MediaController> = runCatching {
        getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(
                ComponentName(this, AirPodsMediaNotificationListener::class.java),
            )
            .orEmpty()
    }.getOrDefault(emptyList())

    companion object {
        @Volatile
        private var instance: AirPodsMediaNotificationListener? = null

        fun current(): AirPodsMediaNotificationListener? = instance

        private const val TAG = "AirPodsMediaSession"
    }
}
