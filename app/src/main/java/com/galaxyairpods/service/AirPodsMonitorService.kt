package com.galaxyairpods.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.galaxyairpods.MainActivity
import com.galaxyairpods.data.BleAirPodsRepository
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.widget.AirPodsWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the BLE scanner alive after the activity is not visible.
 *
 * Android requires a foreground service for this kind of continuous Bluetooth
 * observation. The service only publishes state that came from the real parser;
 * it never creates a fallback battery value.
 */
class AirPodsMonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: AirPodsDataStore
    private lateinit var repository: BleAirPodsRepository
    private lateinit var scanner: AirPodsBleScanner

    override fun onCreate() {
        super.onCreate()
        dataStore = AirPodsDataStore(this)
        repository = BleAirPodsRepository(dataStore)
        scanner = AirPodsBleScanner(this)

        createNotificationChannel()
        startForegroundCompat(buildNotification(AirPodsState.empty()))
        observePackets()
        observeBluetoothConnections()
        scanner.start(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_POWER)
    }

    private fun observePackets() {
        serviceScope.launch {
            scanner.validatedPackets.collect { event ->
                val previous = repository.state.value
                repository.applyParsedPacket(event.deviceId, event.packet, event.seenAt)
                val current = repository.state.value
                val override = dataStore.modelOverride.first()
                val displayState = current.copy(model = override ?: current.model)

                updateNotification(displayState)
                AirPodsWidget.updateState(
                    context = this@AirPodsMonitorService,
                    left = displayState.leftBattery,
                    right = displayState.rightBattery,
                    caseBattery = displayState.caseBattery,
                    modelLabel = displayState.model.label,
                )

                val firstDetection = previous.deviceId == null || previous.deviceId != current.deviceId
                val caseOpened = event.packet.caseOpen == true && previous.caseOpen != true
                val showPopup = dataStore.autoPopup.first() &&
                    (firstDetection || (caseOpened && dataStore.showOnCaseOpen.first()))

                if (showPopup) {
                    showOverlayIfPermitted()
                }
            }
        }
    }

    private fun observeBluetoothConnections() {
        serviceScope.launch {
            scanner.bluetoothEvents.collect { event ->
                val previous = repository.state.value
                repository.applyBluetoothConnection(event)
                val current = repository.state.value
                val override = dataStore.modelOverride.first()
                val displayState = current.copy(model = override ?: current.model)

                updateNotification(displayState)
                AirPodsWidget.updateState(
                    context = this@AirPodsMonitorService,
                    left = displayState.leftBattery,
                    right = displayState.rightBattery,
                    caseBattery = displayState.caseBattery,
                    modelLabel = displayState.model.label,
                )

                val connectedNow = event.connected && !previous.connected
                if (connectedNow) showOverlayIfPermitted()
            }
        }
    }

    private fun showOverlayIfPermitted() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        runCatching {
            startService(Intent(this, AirPodsOverlayService::class.java))
        }
    }

    private fun updateNotification(state: AirPodsState) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: AirPodsState): Notification {
        val batteryText = if (!state.hasAnyBattery) {
            when {
                state.connected -> "연결됨 · 배터리 정보 대기 중"
                state.detected -> "페어링됨 · 연결 대기"
                else -> "AirPods 검색 중"
            }
        } else {
            listOf(
                state.leftBattery?.let { "L " + it + "%" },
                state.rightBattery?.let { "R " + it + "%" },
                state.caseBattery?.let { "Case " + it + "%" },
            ).filterNotNull().joinToString(" · ")
        }

        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(state.model.label)
            .setContentText(batteryText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AirPods 감지",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "AirPods 배터리 상태를 감지하는 중입니다."
            },
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scanner.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "airpods_detection"
        const val NOTIFICATION_ID = 1001
    }
}
