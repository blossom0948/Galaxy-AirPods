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
    fun repeatedShowUpdatesOnePopupWithoutRestartingTheProductClock() {
        val controller = PopupMotionController()
        controller.show(liveState())
        val id = controller.state.value.animationId
        controller.show(liveState().copy(leftBattery = 64))
        assertEquals(id, controller.state.value.animationId)
        assertEquals(64, controller.state.value.deviceState.leftBattery)
    }

    @Test
    fun connectedMorphsExistingSurface() {
        val controller = PopupMotionController()
        controller.show(liveState())
        controller.dispatch(PopupEvent.Connected)

        assertEquals(PopupPhase.CONNECTED, controller.state.value.phase)
        assertEquals(true, controller.state.value.deviceState.connected)
    }

    @Test
    fun caseOpenedStartsNewMotionAndKeepsPerBudRemoval() {
        val controller = PopupMotionController()
        controller.show(liveState(caseOpen = false))
        val previousAnimationId = controller.state.value.animationId

        controller.dispatch(
            PopupEvent.CaseOpened(
                liveState(caseOpen = true).copy(leftInCase = false, rightInCase = true),
            ),
        )

        assertEquals(previousAnimationId + 1, controller.state.value.animationId)
        assertEquals(true, controller.state.value.leftRemoved)
        assertEquals(false, controller.state.value.rightRemoved)
        assertEquals(PopupPhase.ENTERING, controller.state.value.phase)
    }

    @Test
    fun closeEntersExitWithoutSnappingHidden() {
        val controller = PopupMotionController()
        controller.show(liveState(caseOpen = true))
        controller.dispatch(PopupEvent.CaseClosed(liveState(caseOpen = false)))

        assertEquals(PopupPhase.EXITING, controller.state.value.phase)
    }

    @Test
    fun batteryUpdateKeepsEntranceAnimationIdentity() {
        val controller = PopupMotionController()
        controller.show(liveState())
        val animationId = controller.state.value.animationId

        controller.dispatch(PopupEvent.BatteryUpdated(liveState().copy(leftBattery = 79)))

        assertEquals(animationId, controller.state.value.animationId)
        assertEquals(PopupPhase.UPDATED, controller.state.value.phase)
    }

    @Test
    fun batteryUpdateSynchronizesBudRemovalFlags() {
        val controller = PopupMotionController()
        controller.show(liveState(caseOpen = true))

        controller.dispatch(
            PopupEvent.BatteryUpdated(
                liveState(caseOpen = true).copy(leftInCase = false, rightInCase = true),
            ),
        )

        assertEquals(true, controller.state.value.leftRemoved)
        assertEquals(false, controller.state.value.rightRemoved)
    }

    @Test
    fun caseClosedCarriesClosedVisualStateIntoExit() {
        val controller = PopupMotionController()
        controller.show(liveState(caseOpen = true))
        controller.dispatch(PopupEvent.CaseClosed(liveState(caseOpen = false)))

        assertEquals(PopupPhase.EXITING, controller.state.value.phase)
        assertEquals(false, controller.state.value.deviceState.caseOpen)
        assertEquals(80, controller.state.value.deviceState.leftBattery)
    }

    @Test
    fun batteryUpdateDuringExitDoesNotCancelCloseAnimation() {
        val controller = PopupMotionController()
        controller.show(liveState(caseOpen = true))
        controller.dispatch(PopupEvent.CaseClosed(liveState(caseOpen = false)))
        val exitEventId = controller.state.value.eventId

        controller.dispatch(
            PopupEvent.BatteryUpdated(
                liveState(caseOpen = false).copy(leftBattery = 79),
            ),
        )

        assertEquals(PopupPhase.EXITING, controller.state.value.phase)
        assertEquals(exitEventId, controller.state.value.eventId)
        assertEquals(79, controller.state.value.deviceState.leftBattery)
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
