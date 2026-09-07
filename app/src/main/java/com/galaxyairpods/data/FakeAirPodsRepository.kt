package com.galaxyairpods.data

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Preview-only state. It is never used as a BLE result. */
object PreviewFixtures {
    val knownOpen = AirPodsState(
        deviceId = "preview-device",
        model = AirPodsModel.AIRPODS_PRO,
        leftBattery = 82,
        rightBattery = 79,
        caseBattery = 64,
        leftCharging = false,
        rightCharging = false,
        caseCharging = false,
        leftInCase = true,
        rightInCase = true,
        caseOpen = true,
        connected = true,
        detected = true,
        lastSeenAt = System.currentTimeMillis(),
        confidence = DataConfidence.LIVE,
    )

    val lowBattery = knownOpen.copy(
        leftBattery = 18,
        rightBattery = 12,
        caseBattery = 27,
    )

    val charging = knownOpen.copy(
        leftCharging = true,
        rightCharging = true,
        caseCharging = true,
    )

    val firstPairing = knownOpen.copy(
        connected = false,
        confidence = DataConfidence.LIVE,
    )
}

class FakeAirPodsRepository {
    private val _state = MutableStateFlow(PreviewFixtures.knownOpen)
    val state: StateFlow<AirPodsState> = _state.asStateFlow()

    fun setPreviewState(state: AirPodsState) {
        _state.value = state.copy(lastSeenAt = System.currentTimeMillis())
    }

    fun update(transform: (AirPodsState) -> AirPodsState) {
        _state.value = transform(_state.value).copy(lastSeenAt = System.currentTimeMillis())
    }
}
