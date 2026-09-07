package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.PopupEvent
import com.galaxyairpods.domain.model.PopupPhase
import com.galaxyairpods.domain.popup.PopupMotionController
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupMotionControllerTest {
    @Test
    fun connectedMorphsExistingSurface() {
        val controller = PopupMotionController()
        controller.show(liveState())
        controller.dispatch(PopupEvent.Connected)

        assertEquals(PopupPhase.CONNECTED, controller.state.value.phase)
        assertEquals(true, controller.state.value.deviceState.connected)
    }

    @Test
    fun closeEntersExitWithoutSnappingHidden() {
        val controller = PopupMotionController()
        controller.show(liveState(caseOpen = true))
        controller.dispatch(PopupEvent.CaseClosed)

        assertEquals(PopupPhase.EXITING, controller.state.value.phase)
    }
}

private fun liveState(caseOpen: Boolean? = null) = AirPodsState(
    deviceId = "test-device",
    model = AirPodsModel.AIRPODS_PRO2,
    leftBattery = 80,
    rightBattery = 70,
    caseBattery = 90,
    caseOpen = caseOpen,
    connected = true,
    detected = true,
    lastSeenAt = 1_000_000L,
    confidence = DataConfidence.LIVE,
)
