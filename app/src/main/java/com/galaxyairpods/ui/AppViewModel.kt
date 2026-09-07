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
import com.galaxyairpods.domain.model.PopupEvent
import com.galaxyairpods.domain.model.mergeKnownValuesFrom
import com.galaxyairpods.domain.motion.MotionLabSettings
import com.galaxyairpods.domain.popup.PopupMotionController
import com.galaxyairpods.permissions.PermissionManager
import com.galaxyairpods.service.AirPodsMonitorService
import com.galaxyairpods.service.AirPodsOverlayService
import com.galaxyairpods.update.UpdateManager
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
    val popupController = PopupMotionController()
    val popupState: StateFlow<com.galaxyairpods.domain.model.PopupUiState> = popupController.state
    val motionSettings: MotionLabSettings = MotionLabSettings.Default

    val airPodsState: StateFlow<AirPodsState> = combine(
        liveRepository.state,
        dataStore.latestState,
        dataStore.modelOverride,
    ) { live, stored, override ->
        val source = if (live.deviceId != null) {
            live.mergeKnownValuesFrom(stored)
        } else {
            stored ?: AirPodsState.empty()
        }
        source.withResolvedConfidence().copy(model = override ?: source.model)
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
                val previous = liveRepository.state.value
                liveRepository.applyParsedPacket(event.deviceId, event.packet, event.seenAt)
                val current = liveRepository.state.value
                val displayState = current.copy(model = modelOverride.value ?: current.model)

                AirPodsWidget.updateState(
                    context = getApplication(),
                    left = current.leftBattery,
                    right = current.rightBattery,
                    caseBattery = current.caseBattery,
                    modelLabel = displayState.model.label,
                )

                if (shouldShowPopup(previous, current, event.packet.caseOpen)) {
                    if (popupController.state.value.isVisible) {
                        popupController.dispatch(PopupEvent.BatteryUpdated(displayState))
                    } else {
                        popupController.show(displayState)
                    }
                } else if (popupController.state.value.isVisible) {
                    popupController.dispatch(PopupEvent.BatteryUpdated(displayState))
                }
            }
        }
        viewModelScope.launch {
            scanner.bluetoothEvents.collect { event ->
                val previous = liveRepository.state.value
                liveRepository.applyBluetoothConnection(event)
                val current = liveRepository.state.value
                val displayState = current.copy(model = modelOverride.value ?: current.model)

                AirPodsWidget.updateState(
                    context = getApplication(),
                    left = current.leftBattery,
                    right = current.rightBattery,
                    caseBattery = current.caseBattery,
                    modelLabel = displayState.model.label,
                )

                if (event.connected && shouldShowPopup(previous, current, null)) {
                    if (popupController.state.value.isVisible) {
                        popupController.dispatch(PopupEvent.BatteryUpdated(displayState))
                    } else {
                        popupController.show(displayState)
                    }
                }
            }
        }
        viewModelScope.launch {
            dataStore.backgroundDetection.collect { enabled ->
                if (enabled) startBackgroundServiceIfReady() else stopBackgroundService()
            }
        }
    }

    fun startScanning() {
        scannerLease.start()
        if (backgroundDetection.value) startBackgroundServiceIfReady()
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

    fun setModelOverride(model: AirPodsModel?) {
        viewModelScope.launch { dataStore.setModelOverride(model) }
    }

    fun setReducedMotion(enabled: Boolean) {
        _reducedMotion.value = enabled
    }

    fun checkForUpdates() {
        viewModelScope.launch { updateManager.check() }
    }

    fun installReadyUpdate(ready: UpdateState.Ready) {
        updateManager.installReady(ready.file)
    }

    fun testOverlay() {
        val context = getApplication<Application>()
        if (Settings.canDrawOverlays(context)) {
            ContextCompat.startForegroundService(context, Intent(context, AirPodsOverlayService::class.java))
        }
    }

    fun dismissPopup() = popupController.dispatch(PopupEvent.UserDismiss)

    fun hidePopup() = popupController.hide()

    fun markPopupBatteryVisible() = popupController.markBatteryVisible()

    fun markPopupIdle() = popupController.markIdle()

    private fun shouldShowPopup(
        previous: AirPodsState,
        current: AirPodsState,
        currentCaseOpen: Boolean?,
    ): Boolean {
        if (!autoPopup.value) return false

        val firstDetection = previous.deviceId == null || previous.deviceId != current.deviceId
        val connectionChanged = !previous.connected && current.connected
        val caseOpened = currentCaseOpen == true && previous.caseOpen != true
        val batteryChanged = previous.leftBattery != current.leftBattery ||
            previous.rightBattery != current.rightBattery ||
            previous.caseBattery != current.caseBattery
        val chargingChanged = previous.leftCharging != current.leftCharging ||
            previous.rightCharging != current.rightCharging ||
            previous.caseCharging != current.caseCharging

        return firstDetection ||
            connectionChanged ||
            (caseOpened && showOnCaseOpen.value) ||
            batteryChanged ||
            chargingChanged
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
