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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.galaxyairpods.MainActivity
import com.galaxyairpods.data.BleAirPodsRepository
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import com.galaxyairpods.data.bluetooth.AirPodsScannerHub
import com.galaxyairpods.data.bluetooth.AirPodsScannerLease
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.mergeKnownValuesFrom
import com.galaxyairpods.widget.AirPodsWidget
import com.galaxyairpods.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

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
    private lateinit var updateManager: UpdateManager
    private lateinit var mediaPlaybackController: WearMediaPlaybackController
    private var foregroundReady = false
    @Volatile private var wearDetectionEnabled = true
    @Volatile private var automaticMediaControlEnabled = true

    override fun onCreate() {
        super.onCreate()
        dataStore = AirPodsDataStore(this)
        repository = BleAirPodsRepository(dataStore)
        scannerLease = AirPodsScannerHub.acquire(this)
        scanner = scannerLease.scanner
        updateManager = UpdateManager.shared(this)
        mediaPlaybackController = WearMediaPlaybackController(this)

        createNotificationChannel()
        foregroundReady = startForegroundCompat(buildNotification(AirPodsState.empty()))
        if (!foregroundReady) {
            stopSelf()
            return
        }
        observePackets()
        observeClassicAapBattery()
        observeClassicAapWear()
        observeBluetoothConnections()
        observePersistedBatteryFallback()
        observeWearSettings()
        startAutomaticUpdateChecks()
        scannerLease.start(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
    }

    private fun observePersistedBatteryFallback() {
        serviceScope.launch {
            dataStore.latestDisplayState.collect { stored ->
                if (stored == null) return@collect
                val live = repository.state.value
                val displayState = if (live.deviceId == null) {
                    stored
                } else {
                    live.mergeKnownValuesFrom(stored)
                }
                updateNotification(displayState)
                AirPodsWidget.updateState(
                    context = this@AirPodsMonitorService,
                    left = displayState.leftBattery,
                    right = displayState.rightBattery,
                    caseBattery = displayState.caseBattery,
                    modelLabel = displayState.model.label,
                )

            }
        }
    }

    private fun startAutomaticUpdateChecks() {
        serviceScope.launch {
            while (isActive) {
                updateManager.check(automatic = true)
                delay(AUTO_UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun observePackets() {
        serviceScope.launch {
            scanner.validatedPackets.collect { event ->
                val previous = repository.state.value
                repository.applyParsedPacket(
                    deviceId = event.deviceId,
                    packet = event.packet,
                    seenAt = event.seenAt,
                    wearDetectionEnabled = wearDetectionEnabled,
                    deviceProfileId = event.deviceProfileId,
                    capturedAtElapsedMs = event.capturedAtElapsedMs,
                )
                val current = repository.state.value
                handleWearTransition(previous, current)
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
                    (firstDetection ||
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
                when (event.connectionState) {
                    com.galaxyairpods.domain.model.AirPodsConnectionState.DISCONNECTED,
                    com.galaxyairpods.domain.model.AirPodsConnectionState.UNKNOWN,
                    -> mediaPlaybackController.reset()
                    com.galaxyairpods.domain.model.AirPodsConnectionState.ANDROID_CONNECTED -> Unit
                    else -> {
                        // NEARBY_ONLY/CONNECTION_PENDING can be a transient
                        // Samsung profile-poll result while a single bud is
                        // still the active output. Let the route gate decide
                        // whether the saved auto-pause session is still safe.
                        mediaPlaybackController.onConnectionEvidenceChanged(current)
                    }
                }
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

                val connectedNow = event.connectionState ==
                    com.galaxyairpods.domain.model.AirPodsConnectionState.ANDROID_CONNECTED &&
                    previous.connectionState != com.galaxyairpods.domain.model.AirPodsConnectionState.ANDROID_CONNECTED
                val nearbyDetected = event.connectionState !=
                    com.galaxyairpods.domain.model.AirPodsConnectionState.DISCONNECTED &&
                    event.connectionState != com.galaxyairpods.domain.model.AirPodsConnectionState.UNKNOWN &&
                    previous.connectionState != event.connectionState
                // Connection/case events are independent of telemetry. The
                // overlay must be able to show a loading/unknown battery state
                // instead of silently disappearing when no battery sample has
                // arrived yet.
                if ((connectedNow || nearbyDetected) && dataStore.autoPopup.first()) {
                    showOverlayIfPermitted()
                }
            }
        }
    }

    private fun observeClassicAapBattery() {
        serviceScope.launch {
            scanner.classicBatteryEvents.collect { event ->
                repository.applyClassicAapBattery(event)
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

            }
        }
    }

    private fun observeClassicAapWear() {
        serviceScope.launch {
            scanner.classicWearEvents.collect { event ->
                if (!wearDetectionEnabled) return@collect
                val previous = repository.state.value
                repository.applyClassicAapWear(event)
                val current = repository.state.value
                handleWearTransition(previous, current)
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
            }
        }
    }

    private fun observeWearSettings() {
        serviceScope.launch {
            dataStore.wearDetectionEnabled.collect { enabled ->
                wearDetectionEnabled = enabled
                if (!enabled) mediaPlaybackController.reset()
            }
        }
        serviceScope.launch {
            dataStore.automaticMediaControlEnabled.collect { enabled ->
                automaticMediaControlEnabled = enabled
                if (!enabled) mediaPlaybackController.reset()
            }
        }
    }

    private suspend fun handleWearTransition(previous: AirPodsState, current: AirPodsState) {
        if (!wearDetectionEnabled || !automaticMediaControlEnabled) return
        if (previous.deviceProfileId != current.deviceProfileId ||
            (previous.wearState == current.wearState &&
                previous.wearCapturedAt == current.wearCapturedAt &&
                previous.wearCapturedAtElapsedMs == current.wearCapturedAtElapsedMs)
        ) {
            if (previous.deviceProfileId != current.deviceProfileId) {
                mediaPlaybackController.reset()
            }
            return
        }
        mediaPlaybackController.onWearStateChanged(current)
    }

    private fun showOverlayIfPermitted() {
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay unavailable: SYSTEM_ALERT_WINDOW is not granted")
            return
        }
        runCatching {
            // This service is launched by our already-running foreground
            // monitor. Starting a second FGS here is rejected on some Samsung
            // builds when the activity is closed, so keep the popup service
            // short-lived and ordinary.
            startService(Intent(this, AirPodsOverlayService::class.java))
        }.onFailure { error ->
            Log.e(TAG, "Unable to start overlay service", error)
        }
    }

    private fun updateNotification(state: AirPodsState) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: AirPodsState): Notification {
        val batteryText = if (!state.hasAnyBattery) {
            "${state.connectionLabel} · 배터리 정보 대기 중"
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

    private fun startForegroundCompat(notification: Notification): Boolean {
        return runCatching {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        }.onFailure { error ->
            Log.e(TAG, "Unable to promote monitor service to foreground", error)
        }.isSuccess
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundReady) return START_NOT_STICKY
        // The system receiver can wake an already-running service when
        // Bluetooth is turned back on. Re-entering start() is idempotent and
        // restarts the BLE scan if the adapter was unavailable during onCreate.
        if (::scannerLease.isInitialized) {
            scannerLease.start(android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY)
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Samsung's task manager may remove the process when the recent-apps
        // card is swiped even though START_STICKY was requested. Re-arm the
        // monitor immediately; the user's background-detection toggle is
        // respected by the public stopService path.
        runCatching {
            ContextCompat.startForegroundService(
                applicationContext,
                Intent(applicationContext, AirPodsMonitorService::class.java),
            )
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (::scannerLease.isInitialized) scannerLease.close()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val TAG = "AirPodsMonitorService"
        const val CHANNEL_ID = "airpods_detection"
        const val NOTIFICATION_ID = 1001
        const val AUTO_UPDATE_INTERVAL_MS = 30 * 60 * 1000L
    }
}
