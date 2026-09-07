package com.galaxyairpods.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galaxyairpods.data.FakeAirPodsRepository
import com.galaxyairpods.data.PreviewFixtures
import com.galaxyairpods.data.BleAirPodsRepository
import com.galaxyairpods.data.bluetooth.AirPodsBleScanner
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.PopupEvent
import com.galaxyairpods.domain.model.PopupMode
import com.galaxyairpods.domain.model.PreviewPreset
import com.galaxyairpods.domain.motion.MotionLabSettings
import com.galaxyairpods.domain.popup.PopupMotionController
import com.galaxyairpods.widget.AirPodsWidget
import com.galaxyairpods.service.AirPodsMonitorService
import com.galaxyairpods.service.AirPodsOverlayService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = FakeAirPodsRepository()
    private val dataStore = AirPodsDataStore(application)
    private val liveRepository = BleAirPodsRepository(dataStore)

    val airPodsState: StateFlow<AirPodsState> = combine(
        repository.state,
        dataStore.latestState,
    ) { previewState, storedState ->
        storedState ?: previewState
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        PreviewFixtures.knownOpen,
    )
    val hasPersistedState: StateFlow<Boolean> = dataStore.latestState
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val popupController = PopupMotionController()
    val popupState: StateFlow<com.galaxyairpods.domain.model.PopupUiState> = popupController.state
    val scanner = AirPodsBleScanner(application)

    private val _motionSettings = MutableStateFlow(MotionLabSettings.Default)
    val motionSettings: StateFlow<MotionLabSettings> = _motionSettings.asStateFlow()

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
        false,
    )

    init {
        viewModelScope.launch {
            scanner.validatedPackets.collect { event ->
                liveRepository.applyParsedPacket(event.deviceId, event.packet, event.seenAt)
                val liveState = liveRepository.state.value
                AirPodsWidget.updateState(
                    context = getApplication(),
                    left = liveState.leftBattery,
                    right = liveState.rightBattery,
                    caseBattery = liveState.caseBattery,
                )
                if (event.packet.caseOpen == true && autoPopup.value && showOnCaseOpen.value) {
                    popupController.show(liveState)
                } else if (popupController.state.value.isVisible) {
                    popupController.dispatch(PopupEvent.BatteryUpdated(liveState))
                }
            }
        }
    }

    fun updateMotionSettings(transform: (MotionLabSettings) -> MotionLabSettings) {
        _motionSettings.value = transform(_motionSettings.value)
    }

    fun resetMotionSettings() {
        _motionSettings.value = MotionLabSettings.Default
    }

    fun preview(preset: PreviewPreset) {
        when (preset) {
            PreviewPreset.FIRST_PAIRING -> {
                repository.setPreviewState(PreviewFixtures.firstPairing)
                popupController.show(PreviewFixtures.firstPairing, PopupMode.FIRST_PAIRING)
            }

            PreviewPreset.KNOWN_OPEN -> {
                repository.setPreviewState(PreviewFixtures.knownOpen)
                popupController.show(PreviewFixtures.knownOpen)
            }

            PreviewPreset.CONNECTED -> {
                repository.setPreviewState(PreviewFixtures.knownOpen.copy(connected = false))
                popupController.show(PreviewFixtures.firstPairing, PopupMode.FIRST_PAIRING)
                popupController.dispatch(PopupEvent.Connected)
            }

            PreviewPreset.LEFT_REMOVED -> {
                popupController.show(PreviewFixtures.knownOpen)
                popupController.dispatch(PopupEvent.LeftRemoved)
            }

            PreviewPreset.RIGHT_REMOVED -> {
                popupController.show(PreviewFixtures.knownOpen)
                popupController.dispatch(PopupEvent.RightRemoved)
            }

            PreviewPreset.BOTH_REMOVED -> {
                popupController.show(PreviewFixtures.knownOpen)
                popupController.dispatch(PopupEvent.LeftRemoved)
                popupController.dispatch(PopupEvent.RightRemoved)
            }

            PreviewPreset.CHARGING -> {
                repository.setPreviewState(PreviewFixtures.charging)
                popupController.show(PreviewFixtures.charging)
                popupController.dispatch(PopupEvent.ChargingStarted)
            }

            PreviewPreset.LOW_BATTERY -> {
                repository.setPreviewState(PreviewFixtures.lowBattery)
                popupController.show(PreviewFixtures.lowBattery)
            }

            PreviewPreset.EXIT -> {
                popupController.show(PreviewFixtures.knownOpen)
                popupController.dispatch(PopupEvent.UserDismiss)
            }

            PreviewPreset.INTERRUPT_ENTER_TO_EXIT -> {
                popupController.show(PreviewFixtures.knownOpen)
                viewModelScope.launch {
                    kotlinx.coroutines.delay(180)
                    popupController.dispatch(PopupEvent.UserDismiss)
                }
            }

            PreviewPreset.INTERRUPT_EXIT_TO_ENTER -> {
                popupController.show(PreviewFixtures.knownOpen)
                popupController.dispatch(PopupEvent.UserDismiss)
                viewModelScope.launch {
                    kotlinx.coroutines.delay(180)
                    popupController.dispatch(PopupEvent.CaseOpened(PreviewFixtures.knownOpen))
                }
            }
        }
    }

    fun markPopupBatteryVisible() = popupController.markBatteryVisible()

    fun markPopupIdle() = popupController.markIdle()

    fun dismissPopup() = popupController.dispatch(PopupEvent.UserDismiss)

    fun hidePopup() = popupController.hide()

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
        viewModelScope.launch {
            dataStore.setBackgroundDetection(enabled)
            val context = getApplication<Application>()
            if (enabled) {
                val intent = Intent(context, AirPodsMonitorService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(Intent(context, AirPodsMonitorService::class.java))
            }
        }
    }

    fun testOverlay() {
        val context = getApplication<Application>()
        if (Settings.canDrawOverlays(context)) {
            context.startService(Intent(context, AirPodsOverlayService::class.java))
        }
    }

    fun persistLiveState(state: AirPodsState) {
        // This is the production path for a validated parser result. Preview fixtures
        // intentionally never call it.
        viewModelScope.launch {
            dataStore.saveState(state)
            AirPodsWidget.updateState(
                context = getApplication(),
                left = state.leftBattery,
                right = state.rightBattery,
                caseBattery = state.caseBattery,
            )
        }
    }

    fun saveMotionCandidate(): String {
        val fileName = "motion-candidate-${System.currentTimeMillis()}.json"
        MotionCandidateStore(getApplication()).save(fileName, _motionSettings.value)
        return fileName
    }

    override fun onCleared() {
        scanner.stop()
        super.onCleared()
    }
}
