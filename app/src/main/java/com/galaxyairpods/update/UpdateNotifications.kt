package com.galaxyairpods.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import com.galaxyairpods.MainActivity

internal object UpdateNotifications {
    private const val CHANNEL_ID = "app_updates"
    private const val AVAILABLE_NOTIFICATION_ID = 2000
    private const val INSTALL_PERMISSION_NOTIFICATION_ID = 2001
    private const val CONFIRMATION_NOTIFICATION_ID = 2002

    fun notifyAvailable(context: Context, info: UpdateInfo) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        post(
            context = context,
            id = AVAILABLE_NOTIFICATION_ID,
            title = "AirPods Galaxy ${info.versionName} 업데이트",
            text = "새 버전이 있습니다. 앱의 설정에서 업데이트를 시작하세요.",
            contentIntent = PendingIntent.getActivity(
                context,
                AVAILABLE_NOTIFICATION_ID,
                launchIntent,
                pendingIntentFlags(),
            ),
        )
    }

    fun notifyInstallPermission(context: Context) {
        val settingsIntent = Intent(
            android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
        post(
            context = context,
            id = INSTALL_PERMISSION_NOTIFICATION_ID,
            title = "AirPods Galaxy 업데이트 준비",
            text = "업데이트를 계속하려면 알 수 없는 앱 설치를 허용하세요.",
            contentIntent = PendingIntent.getActivity(
                context,
                INSTALL_PERMISSION_NOTIFICATION_ID,
                settingsIntent,
                pendingIntentFlags(),
            ),
        )
    }

    fun notifyConfirmation(context: Context, confirmationIntent: Intent) {
        post(
            context = context,
            id = CONFIRMATION_NOTIFICATION_ID,
            title = "AirPods Galaxy 업데이트 준비 완료",
            text = "시스템 설치 확인을 눌러 업데이트를 마무리하세요.",
            contentIntent = PendingIntent.getActivity(
                context,
                CONFIRMATION_NOTIFICATION_ID,
                confirmationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                pendingIntentFlags(),
            ),
        )
    }

    fun cancel(context: Context) {
        cancelAvailable(context)
        context.getSystemService(NotificationManager::class.java)?.apply {
            cancel(INSTALL_PERMISSION_NOTIFICATION_ID)
            cancel(CONFIRMATION_NOTIFICATION_ID)
        }
    }

    fun cancelAvailable(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(AVAILABLE_NOTIFICATION_ID)
    }

    private fun post(
        context: Context,
        id: Int,
        title: String,
        text: String,
        contentIntent: PendingIntent,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "앱 업데이트",
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        runCatching {
            manager.notify(
                id,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(contentIntent)
                    .setAutoCancel(true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build(),
            )
        }
    }

    private fun pendingIntentFlags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
}
