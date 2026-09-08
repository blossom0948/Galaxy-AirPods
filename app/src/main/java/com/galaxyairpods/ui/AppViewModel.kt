package com.galaxyairpods.ui

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyairpods.data.BleAirPodsRepository
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import com.galaxyairpods.data.bluetooth.AirPodsScannerHub
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.mergeKnownValuesFrom
import com.galaxyairpods.domain.motion.MotionLabSettings
import com.galaxyairpods.permissions.PermissionManager
import com.galaxyairpods.service.AirPodsMonitorService
import com.galaxyairpods.service.AirPodsOverlayService
import com.galaxyairpods.update.UpdateManager
import com.galaxyairpods.update.UpdateInfo
import com.galaxyairpods.update.UpdateState
import com.galaxyairpods.widget.AirPodsWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStore = AirPodsDataStore(application)
    private val liveRepository = BleAirPodsRepository(dataStore)
    private val updateManager = UpdateManager.shared(application)

    private val scannerLease = AirPodsScannerHub.acquire(application)
    val scanner: AirPodsBleScanner = scannerLease.scanner
    val scanStatus: StateFlow<String> = scanner.status
    val motionSettings: MotionLabSettings = MotionLabSettings.Default

    val airPodsState: StateFlow<AirPodsState> = combine(
        liveRepository.state,
        dataStore.latestState,
        dataStore.modelOverride,
        dataStore.wearDetectionEnabled,
    ) { live, stored, override, wearEnabled ->
        val source = if (live.deviceId != null) {
            live.mergeKnownValuesFrom(stored)
        } else {
            stored ?: AirPodsState.empty()
        }
        source.withResolvedConfidence()
            .copy(model = override ?: source.model)
            .let { if (wearEnabled) it else it.withoutWearDetection() }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        AirPodsState.empty(),
    )

    val hasPersistedState: StateFlow<Boolean> = dataStore.latestState
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoPopup: StateFlow<Boolean> = dataStore.autoPopup.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )
    val showOnCaseOpen: StateFlow<Boolean> = dataStore.showOnCaseOpen.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )
    val popupDuration: StateFlow<Int> = dataStore.popupDuration.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        5,
    )
    val backgroundDetection: StateFlow<Boolean> = dataStore.backgroundDetection.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )
    val wearDetectionEnabled: StateFlow<Boolean> = dataStore.wearDetectionEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )
    val automaticMediaControlEnabled: StateFlow<Boolean> = dataStore.automaticMediaControlEnabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        true,
    )
    val modelOverride: StateFlow<AirPodsModel?> = dataStore.modelOverride.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        null,
    )
    val updateState: StateFlow<UpdateState> = updateManager.state

    private val _reducedMotion = MutableStateFlow(false)
    val reducedMotion: StateFlow<Boolean> = _reducedMotion

    init {
        startAutomaticUpdateChecks()
        viewModelScope.launch {
            scanner.validatedPackets.collect { event ->
                liveRepository.applyParsedPacket(
                    deviceId = event.deviceId,
                    packet = event.packet,
                    seenAt = event.seenAt,
                    wearDetectionEnabled = wearDetectionEnabled.value,
                    deviceProfileId = event.deviceProfileId,
                    capturedAtElapsedMs = event.capturedAtElapsedMs,
                )
                val current = liveRepository.state.value
                val displayState = displayState(current)

                AirPodsWidget.updateState(
                    context = getApplication(),
                    left = current.leftBattery,
                    right = current.rightBattery,
                    caseBattery = current.caseBattery,
                    modelLabel = displayState.model.label,
                )
            }
        }
        viewModelScope.launch {
            scanner.bluetoothEvents.collect { event ->
                liveRepository.applyBluetoothConnection(event)
                val current = liveRepository.state.value
                val displayState = displayState(current)

                AirPodsWidget.updateState(
                    context = getApplication(),
                    left = current.leftBattery,
                    right = current.rightBattery,
                    caseBattery = current.caseBattery,
                    modelLabel = displayState.model.label,
                )
            }
        }
        viewModelScope.launch {
            scanner.classicBatteryEvents.collect { event ->
                liveRepository.applyClassicAapBattery(event)
                val current = liveRepository.state.value
                val displayState = displayState(current)

                AirPodsWidget.updateState(
                    context = getApplication(),
                    left = current.leftBattery,
                    right = current.rightBattery,
                    caseBattery = current.caseBattery,
                    modelLabel = displayState.model.label,
                )
            }
        }
        viewModelScope.launch {
            scanner.classicWearEvents.collect { event ->
                if (!wearDetectionEnabled.value) return@collect
                liveRepository.applyClassicAapWear(event)
            }
        }
        viewModelScope.launch {
            combine(
                dataStore.backgroundDetection,
                dataStore.wearDetectionEnabled,
                dataStore.automaticMediaControlEnabled,
            ) { backgroundEnabled, wearEnabled, mediaControlEnabled ->
                backgroundEnabled || (wearEnabled && mediaControlEnabled)
            }.collect { monitorRequired ->
                if (monitorRequired) startBackgroundServiceIfReady() else stopBackgroundService()
            }
        }
    }

    fun startScanning() {
        scannerLease.start()
        if (backgroundDetection.value ||
            (wearDetectionEnabled.value && automaticMediaControlEnabled.value)
        ) {
            startBackgroundServiceIfReady()
        }
    }

    fun setAutoPopup(enabled: Boolean) {
        viewModelScope.launch { dataStore.setAutoPopup(enabled) }
    }

    fun setShowOnCaseOpen(enabled: Boolean) {
        viewModelScope.launch { dataStore.setShowOnCaseOpen(enabled) }
    }

    fun setPopupDuration(seconds: Int) {
        viewModelScope.launch { dataStore.setPopupDuration(seconds) }
    }

    fun setBackgroundDetection(enabled: Boolean) {
        viewModelScope.launch { dataStore.setBackgroundDetection(enabled) }
    }

    fun setWearDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setWearDetectionEnabled(enabled) }
    }

    fun setAutomaticMediaControlEnabled(enabled: Boolean) {
        viewModelScope.launch { dataStore.setAutomaticMediaControlEnabled(enabled) }
    }

    fun setModelOverride(model: AirPodsModel?) {
        viewModelScope.launch { dataStore.setModelOverride(model) }
    }

    fun setReducedMotion(enabled: Boolean) {
        _reducedMotion.value = enabled
    }

    fun checkForUpdates() {
        viewModelScope.launch { updateManager.check() }
    }

    fun startUpdate(info: UpdateInfo) {
        viewModelScope.launch { updateManager.downloadAndInstall(info) }
    }

    fun installReadyUpdate(ready: UpdateState.Ready) {
        updateManager.installReady(ready.file)
    }

    fun testOverlay() {
        val context = getApplication<Application>()
        if (Settings.canDrawOverlays(context)) {
            context.startService(Intent(context, AirPodsOverlayService::class.java))
        }
    }

    private fun displayState(state: AirPodsState): AirPodsState {
        val modelled = state.copy(model = modelOverride.value ?: state.model)
        return if (wearDetectionEnabled.value) modelled else modelled.withoutWearDetection()
    }

    private fun startBackgroundServiceIfReady() {
        val context = getApplication<Application>()
        if (!PermissionManager.allGranted(context, PermissionManager.bluetoothPermissions())) return
        val intent = Intent(context, AirPodsMonitorService::class.java)
        runCatching {
            context.startForegroundService(intent)
        }
    }

    private fun stopBackgroundService() {
        getApplication<Application>().stopService(Intent(getApplication(), AirPodsMonitorService::class.java))
    }

    private fun startAutomaticUpdateChecks() {
        viewModelScope.launch {
            while (isActive) {
                updateManager.check(automatic = true)
                delay(AUTO_UPDATE_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val AUTO_UPDATE_INTERVAL_MS = 30 * 60 * 1000L
    }

    override fun onCleared() {
        scannerLease.close()
        super.onCleared()
    }
}
