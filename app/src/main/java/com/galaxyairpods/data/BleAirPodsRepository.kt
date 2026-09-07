package com.galaxyairpods.data

import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.data.bluetooth.BluetoothAirPodsEvent
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import com.galaxyairpods.domain.model.isCompatibleWith
import com.galaxyairpods.domain.repository.AirPodsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Production repository path used once a validated parser returns a packet. */
class BleAirPodsRepository(
    private val dataStore: AirPodsDataStore,
) : AirPodsRepository {
    private val _state = MutableStateFlow(AirPodsState.empty())
    override val state: StateFlow<AirPodsState> = _state.asStateFlow()

    override suspend fun applyParsedPacket(deviceId: String, packet: ParsedAirPodsPacket, seenAt: Long) {
        val newState = mergeParsedState(_state.value, deviceId, packet, seenAt)
        _state.value = newState
        val hasBattery = packet.leftBattery != null || packet.rightBattery != null || packet.caseBattery != null
        dataStore.saveState(
            newState,
            batterySource = AirPodsDataStore.BatterySource.BLE.takeIf { hasBattery },
        )
    }

    suspend fun applyBluetoothConnection(event: BluetoothAirPodsEvent) {
        val current = _state.value
        val sameDevice = sameLogicalAirPods(current, event.deviceId, event.model)
        val newState = if (sameDevice) {
            current.copy(
                deviceId = current.deviceId ?: event.deviceId,
                model = when {
                    current.model == AirPodsModel.UNKNOWN || current.model == AirPodsModel.AIRPODS -> event.model
                    event.model == AirPodsModel.UNKNOWN || event.model == AirPodsModel.AIRPODS -> current.model
                    current.hasAnyBattery && current.model.isCompatibleWith(event.model) -> current.model
                    else -> event.model
                },
                connected = event.connected,
                detected = true,
                deviceName = event.deviceName,
                lastSeenAt = event.seenAt,
                confidence = DataConfidence.LIVE,
            )
        } else {
            AirPodsState(
                deviceId = event.deviceId,
                model = event.model,
                connected = event.connected,
                detected = true,
                deviceName = event.deviceName,
                lastSeenAt = event.seenAt,
                confidence = DataConfidence.LIVE,
            )
        }
        _state.value = newState
        dataStore.saveState(newState)
    }
}

internal fun mergeParsedState(
    current: AirPodsState,
    deviceId: String,
    packet: ParsedAirPodsPacket,
    seenAt: Long,
): AirPodsState {
    val sameDevice = sameLogicalAirPods(current, deviceId, packet.model)
    val model = if (sameDevice && packet.model == AirPodsModel.AIRPODS &&
        current.model != AirPodsModel.UNKNOWN && current.model != AirPodsModel.AIRPODS
    ) {
        current.model
    } else {
        packet.model
    }

    return AirPodsState(
        deviceId = if (sameDevice) current.deviceId ?: deviceId else deviceId,
        model = model,
        // A valid AirPods broadcast can omit a battery/lid field (0xF or an
        // out-of-case frame). Do not erase the last real value when a later
        // packet only contains the other side's status.
        leftBattery = packet.leftBattery ?: current.leftBattery.takeIf { sameDevice },
        rightBattery = packet.rightBattery ?: current.rightBattery.takeIf { sameDevice },
        caseBattery = packet.caseBattery ?: current.caseBattery.takeIf { sameDevice },
        leftCharging = packet.leftCharging ?: current.leftCharging.takeIf { sameDevice },
        rightCharging = packet.rightCharging ?: current.rightCharging.takeIf { sameDevice },
        caseCharging = packet.caseCharging ?: current.caseCharging.takeIf { sameDevice },
        leftInCase = packet.leftInCase ?: current.leftInCase.takeIf { sameDevice },
        rightInCase = packet.rightInCase ?: current.rightInCase.takeIf { sameDevice },
        caseOpen = packet.caseOpen ?: current.caseOpen.takeIf { sameDevice },
        connected = sameDevice && current.connected,
        detected = true,
        deviceName = current.deviceName.takeIf { sameDevice },
        lastSeenAt = seenAt,
        confidence = packet.confidence,
    )
}

internal fun sameLogicalAirPods(
    current: AirPodsState,
    incomingDeviceId: String,
    incomingModel: AirPodsModel,
): Boolean = current.deviceId == incomingDeviceId ||
    (current.detected && current.model.isCompatibleWith(incomingModel))
