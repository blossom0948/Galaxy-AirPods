package com.galaxyairpods.data

import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.data.bluetooth.BluetoothAirPodsEvent
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
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
        val current = _state.value
        val sameDevice = current.deviceId == deviceId
        val newState = AirPodsState(
            deviceId = deviceId,
            model = packet.model,
            leftBattery = packet.leftBattery,
            rightBattery = packet.rightBattery,
            caseBattery = packet.caseBattery,
            leftCharging = packet.leftCharging,
            rightCharging = packet.rightCharging,
            caseCharging = packet.caseCharging,
            leftInCase = packet.leftInCase,
            rightInCase = packet.rightInCase,
            caseOpen = packet.caseOpen,
            connected = sameDevice && current.connected,
            detected = true,
            deviceName = current.deviceName.takeIf { sameDevice },
            lastSeenAt = seenAt,
            confidence = packet.confidence,
        )
        _state.value = newState
        dataStore.saveState(newState)
    }

    suspend fun applyBluetoothConnection(event: BluetoothAirPodsEvent) {
        val current = _state.value
        val sameDevice = current.deviceId == event.deviceId
        val newState = if (sameDevice) {
            current.copy(
                model = if (current.model == AirPodsModel.UNKNOWN ||
                    current.model == AirPodsModel.AIRPODS
                ) event.model else current.model,
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
