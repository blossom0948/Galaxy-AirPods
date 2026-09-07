package com.galaxyairpods.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import com.galaxyairpods.R
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import android.bluetooth.le.ScanSettings

/**
 * Low-power background detection entry point. Android/Samsung may still stop or
 * throttle this service; the app documents that limitation instead of hiding it.
 */
class AirPodsMonitorService : Service() {
    private lateinit var scanner: AirPodsBleScanner

    override fun onCreate() {
        super.onCreate()
        createChannel()
        scanner = AirPodsBleScanner(this)
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        scanner.start(ScanSettings.SCAN_MODE_LOW_POWER)
    }

    override fun onDestroy() {
        if (::scanner.isInitialized) scanner.stop()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AirPods background detection",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_app_icon)
        .setContentTitle("AirPods Galaxy")
        .setContentText("주변 AirPods 상태 감지 준비됨")
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    companion object {
        private const val CHANNEL_ID = "airpods-detection"
        private const val NOTIFICATION_ID = 401
    }
}
