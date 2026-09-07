package com.galaxyairpods.domain

import com.galaxyairpods.data.PreviewFixtures
import com.galaxyairpods.domain.model.PopupEvent
import com.galaxyairpods.domain.model.PopupPhase
import com.galaxyairpods.domain.popup.PopupMotionController
import org.junit.Assert.assertEquals
import org.junit.Test

class PopupMotionControllerTest {
    @Test
    fun connectedMorphsExistingSurface() {
        val controller = PopupMotionController()
        controller.show(PreviewFixtures.firstPairing)
        controller.dispatch(PopupEvent.Connected)

        assertEquals(PopupPhase.CONNECTED, controller.state.value.phase)
        assertEquals(true, controller.state.value.deviceState.connected)
    }

    @Test
    fun closeEntersExitWithoutSnappingHidden() {
        val controller = PopupMotionController()
        controller.show(PreviewFixtures.knownOpen)
        controller.dispatch(PopupEvent.CaseClosed)

        assertEquals(PopupPhase.EXITING, controller.state.value.phase)
    }
}
