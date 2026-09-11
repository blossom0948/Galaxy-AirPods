package com.galaxyairpods.domain.popup

import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.PopupEvent
import com.galaxyairpods.domain.model.PopupMode
import com.galaxyairpods.domain.model.PopupPhase
import com.galaxyairpods.domain.model.PopupUiState
import com.galaxyairpods.domain.model.AirPodsConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * State engine for a single popup surface. It deliberately never resets content to
 * a hidden/start state when an event arrives while the surface is visible.
 * Compose owns the physical Animatable channels; this controller owns semantics.
 */
class PopupMotionController {
    private val _state = MutableStateFlow(PopupUiState())
    val state: StateFlow<PopupUiState> = _state.asStateFlow()

    fun dispatch(event: PopupEvent) {
        _state.update { current ->
            when (event) {
                is PopupEvent.CaseOpened -> current.copy(
                    phase = if (current.phase == PopupPhase.EXITING) {
                        PopupPhase.ENTERING
                    } else {
                        PopupPhase.ENTERING
                    },
                    deviceState = event.state,
                    eventId = current.eventId + 1,
                    animationId = current.animationId + 1,
                    errorMessage = null,
                )

                PopupEvent.CaseClosed -> current.copy(
                    phase = if (current.phase == PopupPhase.HIDDEN) {
                        PopupPhase.HIDDEN
                    } else {
                        PopupPhase.EXITING
                    },
                    deviceState = current.deviceState.copy(caseOpen = false),
                    eventId = current.eventId + 1,
                )

                PopupEvent.UserDismiss,
                PopupEvent.Timeout,
                -> current.copy(
                    phase = if (current.phase == PopupPhase.HIDDEN) {
                        PopupPhase.HIDDEN
                    } else {
                        PopupPhase.EXITING
                    },
                    eventId = current.eventId + 1,
                )

                PopupEvent.Connected -> current.copy(
                    phase = PopupPhase.CONNECTED,
                    deviceState = current.deviceState.copy(
                        connected = true,
                        connectionState = AirPodsConnectionState.ANDROID_CONNECTED,
                    ),
                    eventId = current.eventId + 1,
                    animationId = current.animationId + 1,
                )

                is PopupEvent.BatteryUpdated -> current.copy(
                    phase = when (current.phase) {
                        PopupPhase.HIDDEN -> PopupPhase.HIDDEN
                        else -> PopupPhase.UPDATED
                    },
                    deviceState = event.state,
                    leftRemoved = event.state.leftInCase?.not() ?: current.leftRemoved,
                    rightRemoved = event.state.rightInCase?.not() ?: current.rightRemoved,
                    eventId = current.eventId + 1,
                )

                PopupEvent.LeftRemoved -> current.copy(
                    phase = keepVisiblePhase(current.phase),
                    leftRemoved = true,
                    deviceState = current.deviceState.copy(leftInCase = false),
                    eventId = current.eventId + 1,
                )

                PopupEvent.RightRemoved -> current.copy(
                    phase = keepVisiblePhase(current.phase),
                    rightRemoved = true,
                    deviceState = current.deviceState.copy(rightInCase = false),
                    eventId = current.eventId + 1,
                )

                PopupEvent.LeftInserted -> current.copy(
                    phase = keepVisiblePhase(current.phase),
                    leftRemoved = false,
                    deviceState = current.deviceState.copy(leftInCase = true),
                    eventId = current.eventId + 1,
                )

                PopupEvent.RightInserted -> current.copy(
                    phase = keepVisiblePhase(current.phase),
                    rightRemoved = false,
                    deviceState = current.deviceState.copy(rightInCase = true),
                    eventId = current.eventId + 1,
                )

                PopupEvent.ChargingStarted -> current.copy(
                    phase = keepVisiblePhase(current.phase),
                    showChargingEmphasis = true,
                    eventId = current.eventId + 1,
                )

                PopupEvent.ChargingStopped -> current.copy(
                    phase = keepVisiblePhase(current.phase),
                    showChargingEmphasis = false,
                    eventId = current.eventId + 1,
                )
            }
        }
    }

    fun show(state: AirPodsState, mode: PopupMode = PopupMode.KNOWN_DEVICE_STATUS) {
        _state.update { current ->
            current.copy(
                phase = PopupPhase.ENTERING,
                mode = mode,
                deviceState = state,
                eventId = current.eventId + 1,
                animationId = current.animationId + 1,
                leftRemoved = state.leftInCase == false,
                rightRemoved = state.rightInCase == false,
                errorMessage = null,
            )
        }
    }

    fun markBatteryVisible() {
        _state.update { current ->
            if (current.phase == PopupPhase.ENTERING || current.phase == PopupPhase.SHOWING_DEVICE) {
                current.copy(phase = PopupPhase.SHOWING_BATTERY)
            } else {
                current
            }
        }
    }

    fun markIdle() {
        _state.update { current ->
            if (current.phase == PopupPhase.EXITING) current else current.copy(phase = PopupPhase.IDLE_VISIBLE)
        }
    }

    fun hide() {
        _state.update { PopupUiState(eventId = it.eventId + 1) }
    }

    fun setDragging(isDragging: Boolean) {
        _state.update { current ->
            if (isDragging && current.isVisible) current.copy(phase = PopupPhase.DRAGGING)
            else if (!isDragging && current.phase == PopupPhase.DRAGGING) current.copy(phase = PopupPhase.IDLE_VISIBLE)
            else current
        }
    }

    private fun keepVisiblePhase(phase: PopupPhase): PopupPhase = when (phase) {
        PopupPhase.HIDDEN -> PopupPhase.HIDDEN
        PopupPhase.EXITING -> PopupPhase.EXITING
        else -> PopupPhase.UPDATED
    }
}
