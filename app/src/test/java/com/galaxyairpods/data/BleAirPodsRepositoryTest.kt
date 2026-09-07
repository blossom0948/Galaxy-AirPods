package com.galaxyairpods.data

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import org.junit.Assert.assertEquals
import org.junit.Test

class BleAirPodsRepositoryTest {
    @Test
    fun partialPacketDoesNotEraseKnownBatteryOrLid() {
        val current = AirPodsState(
            deviceId = "ble-address",
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = 80,
            rightBattery = 70,
            caseBattery = 60,
            caseOpen = true,
            connected = true,
            detected = true,
        )
        val partial = ParsedAirPodsPacket(
            model = AirPodsModel.AIRPODS_PRO,
            leftBattery = null,
            rightBattery = 60,
            caseBattery = null,
            leftCharging = null,
            rightCharging = false,
            caseCharging = null,
            leftInCase = null,
            rightInCase = false,
            caseOpen = null,
            parserVersion = "test",
            confidence = DataConfidence.LIVE,
        )

        val merged = mergeParsedState(current, "rotating-ble-address", partial, 123L)

        assertEquals("ble-address", merged.deviceId)
        assertEquals(80, merged.leftBattery)
        assertEquals(60, merged.rightBattery)
        assertEquals(60, merged.caseBattery)
        assertEquals(true, merged.caseOpen)
        assertEquals(true, merged.connected)
        assertEquals(123L, merged.lastSeenAt)
    }
}
