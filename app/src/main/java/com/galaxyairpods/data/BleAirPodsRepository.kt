package com.galaxyairpods.data

import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.domain.model.AirPodsState
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
            connected = true,
            detected = true,
            lastSeenAt = seenAt,
            confidence = packet.confidence,
        )
        _state.value = newState
        dataStore.saveState(newState)
    }
}
