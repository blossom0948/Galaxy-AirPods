package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.parseIphoneAccessoryBattery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AirPodsHfpBatteryTest {
    @Test
    fun parsesAndroidStringArguments() {
        assertEquals(50, parseIphoneAccessoryBattery(arrayOf("2", "1", "4", "2", "7")))
    }

    @Test
    fun parsesArgumentsWithoutPairCount() {
        assertEquals(100, parseIphoneAccessoryBattery(listOf("1", "9")))
    }

    @Test
    fun rejectsUnknownBatterySentinel() {
        assertNull(parseIphoneAccessoryBattery(listOf("1", "1", "15")))
    }
}
