package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.AirPodsModel
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPodsModelTest {
    @Test
    fun mapsBluetoothNamesToProductShapes() {
        assertEquals(
            AirPodsModel.AIRPODS_PRO2_USBC,
            AirPodsModel.fromBluetoothName("AirPods Pro 2 USB-C"),
        )
        assertEquals(
            AirPodsModel.AIRPODS_GEN4_ANC,
            AirPodsModel.fromBluetoothName("AirPods 4 ANC"),
        )
        assertEquals(
            AirPodsModel.AIRPODS_MAX2,
            AirPodsModel.fromBluetoothName("AirPods Max 2"),
        )
    }

    @Test
    fun unknownGenerationStillUsesAirPodsShape() {
        assertEquals(
            AirPodsModel.AIRPODS,
            AirPodsModel.fromBluetoothName("AirPods"),
        )
    }
}
