package com.galaxyairpods.ui

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.ui.components.AirPodsArtworkTechnology
import com.galaxyairpods.ui.components.artworkTechnology
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPodsArtworkCatalogTest {
    @Test
    fun bitmapLayersAreScopedToTheVerifiedUsbCPro2Model() {
        assertEquals(
            AirPodsArtworkTechnology.BITMAP_LAYERS,
            AirPodsModel.AIRPODS_PRO2_USBC.artworkTechnology(),
        )
        assertEquals(
            AirPodsArtworkTechnology.VECTOR_FALLBACK,
            AirPodsModel.AIRPODS_PRO2.artworkTechnology(),
        )
        assertEquals(
            AirPodsArtworkTechnology.VECTOR_FALLBACK,
            AirPodsModel.AIRPODS_MAX2.artworkTechnology(),
        )
    }
}
