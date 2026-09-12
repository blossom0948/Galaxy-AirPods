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
                    phase = PopupPhase.ENTERING,
                    deviceState = event.state,
                    eventId = current.eventId + 1,
                    animationId = current.animationId + 1,
                    leftRemoved = event.state.leftInCase == false,
                    rightRemoved = event.state.rightInCase == false,
                    errorMessage = null,
                )

                is PopupEvent.CaseClosed -> current.copy(
                    phase = if (current.phase == PopupPhase.HIDDEN) {
                        PopupPhase.HIDDEN
                    } else {
                        PopupPhase.EXITING
                    },
                    deviceState = event.state.copy(caseOpen = false),
                    leftRemoved = event.state.leftInCase == false,
                    rightRemoved = event.state.rightInCase == false,
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

                is PopupEvent.BatteryUpdated -> {
                    // A close event owns the exit animation. Telemetry can
                    // still refresh its content during the short exit window,
                    // but must not turn EXITING back into UPDATED or restart
                    // the animation on every battery sample.
                    val isExiting = current.phase == PopupPhase.EXITING
                    current.copy(
                        phase = when {
                            current.phase == PopupPhase.HIDDEN -> PopupPhase.HIDDEN
                            isExiting -> PopupPhase.EXITING
                            else -> PopupPhase.UPDATED
                        },
                        deviceState = event.state,
                        leftRemoved = event.state.leftInCase?.not() ?: current.leftRemoved,
                        rightRemoved = event.state.rightInCase?.not() ?: current.rightRemoved,
                        eventId = if (isExiting) current.eventId else current.eventId + 1,
                    )
                }

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
        if (_state.value.isVisible && _state.value.phase != PopupPhase.EXITING) {
            dispatch(PopupEvent.BatteryUpdated(state))
            return
        }
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
