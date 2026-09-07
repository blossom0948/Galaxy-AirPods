package com.galaxyairpods.domain

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.isCompatibleWith
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

    @Test
    fun bleAndClassicProfilesCanRepresentTheSameAirPodsFamily() {
        assertEquals(
            true,
            AirPodsModel.AIRPODS_PRO2.isCompatibleWith(AirPodsModel.AIRPODS_PRO),
        )
        assertEquals(
            true,
            AirPodsModel.AIRPODS_PRO2.isCompatibleWith(AirPodsModel.AIRPODS),
        )
        assertEquals(
            false,
            AirPodsModel.AIRPODS_PRO2.isCompatibleWith(AirPodsModel.AIRPODS_GEN3),
        )
    }
}
