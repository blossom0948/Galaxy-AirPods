package com.galaxyairpods.domain.model

enum class PopupMode {
    FIRST_PAIRING,
    KNOWN_DEVICE_STATUS,
}

enum class PopupPhase {
    HIDDEN,
    DETECTED,
    ENTERING,
    SHOWING_DEVICE,
    SHOWING_BATTERY,
    CONNECTED,
    UPDATED,
    IDLE_VISIBLE,
    DRAGGING,
    EXITING,
}

sealed interface PopupEvent {
    data class CaseOpened(val state: AirPodsState) : PopupEvent
    data object CaseClosed : PopupEvent
    data object Connected : PopupEvent
    data class BatteryUpdated(val state: AirPodsState) : PopupEvent
    data object LeftRemoved : PopupEvent
    data object RightRemoved : PopupEvent
    data object LeftInserted : PopupEvent
    data object RightInserted : PopupEvent
    data object ChargingStarted : PopupEvent
    data object ChargingStopped : PopupEvent
    data object UserDismiss : PopupEvent
    data object Timeout : PopupEvent
}

data class PopupUiState(
    val phase: PopupPhase = PopupPhase.HIDDEN,
    val mode: PopupMode = PopupMode.KNOWN_DEVICE_STATUS,
    val deviceState: AirPodsState = AirPodsState.empty(),
    val eventId: Long = 0L,
    val leftRemoved: Boolean = false,
    val rightRemoved: Boolean = false,
    val showChargingEmphasis: Boolean = false,
    val errorMessage: String? = null,
) {
    val isVisible: Boolean
        get() = phase != PopupPhase.HIDDEN
}

enum class PreviewPreset(val label: String) {
    FIRST_PAIRING("FIRST_PAIRING"),
    KNOWN_OPEN("KNOWN_OPEN"),
    CONNECTED("CONNECTED"),
    LEFT_REMOVED("LEFT_REMOVED"),
    RIGHT_REMOVED("RIGHT_REMOVED"),
    BOTH_REMOVED("BOTH_REMOVED"),
    CHARGING("CHARGING"),
    LOW_BATTERY("LOW_BATTERY"),
    EXIT("EXIT"),
    INTERRUPT_ENTER_TO_EXIT("ENTER → EXIT"),
    INTERRUPT_EXIT_TO_ENTER("EXIT → ENTER"),
}
