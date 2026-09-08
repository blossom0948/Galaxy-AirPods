package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.legacy.parseIphoneAccessoryBattery
import com.galaxyairpods.data.bluetooth.legacy.parseXEventBattery
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
    fun parsesCommaSeparatedArguments() {
        assertEquals(70, parseIphoneAccessoryBattery("[1, 1, 6]"))
    }

    @Test
    fun rejectsUnknownBatterySentinel() {
        assertNull(parseIphoneAccessoryBattery(listOf("1", "1", "15")))
    }

    @Test
    fun parsesXEventBattery() {
        assertEquals(37, parseXEventBattery(arrayOf("BATTERY", "3", "8", "0", "0")))
    }

    @Test
    fun parsesPrimitiveXEventArguments() {
        assertEquals(37, parseXEventBattery(intArrayOf(3, 8, 0, 0)))
    }

    @Test
    fun rejectsMalformedXEventBattery() {
        assertNull(parseXEventBattery(listOf("BATTERY", "3", "1")))
    }
}
