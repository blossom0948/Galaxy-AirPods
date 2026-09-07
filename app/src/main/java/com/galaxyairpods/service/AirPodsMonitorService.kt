package com.galaxyairpods.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.galaxyairpods.MainActivity
import com.galaxyairpods.data.BleAirPodsRepository
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import com.galaxyairpods.data.bluetooth.AirPodsScannerHub
import com.galaxyairpods.data.bluetooth.AirPodsScannerLease
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
    private lateinit var scannerLease: AirPodsScannerLease

    override fun onCreate() {
        super.onCreate()
        dataStore = AirPodsDataStore(this)
        repository = BleAirPodsRepository(dataStore)
        scannerLease = AirPodsScannerHub.acquire(this)
        scanner = scannerLease.scanner

        createNotificationChannel()
        startForegroundCompat(buildNotification(AirPodsState.empty()))
        observePackets()
        observeBluetoothConnections()
        scannerLease.start(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
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
                val batteryChanged = previous.leftBattery != current.leftBattery ||
                    previous.rightBattery != current.rightBattery ||
                    previous.caseBattery != current.caseBattery
                val chargingChanged = previous.leftCharging != current.leftCharging ||
                    previous.rightCharging != current.rightCharging ||
                    previous.caseCharging != current.caseCharging
                val showPopup = dataStore.autoPopup.first() &&
                    (firstDetection || batteryChanged || chargingChanged ||
                        (caseOpened && dataStore.showOnCaseOpen.first()))

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
        if (!Settings.canDrawOverlays(this)) return
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, AirPodsOverlayService::class.java))
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The system receiver can wake an already-running service when
        // Bluetooth is turned back on. Re-entering start() is idempotent and
        // restarts the BLE scan if the adapter was unavailable during onCreate.
        if (::scannerLease.isInitialized) {
            scannerLease.start(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scannerLease.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "airpods_detection"
        const val NOTIFICATION_ID = 1001
    }
}
