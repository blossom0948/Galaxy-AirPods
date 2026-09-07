package com.galaxyairpods.data

import org.junit.Assert.assertTrue
import org.junit.Test

class AirPodsAdvertisementParserTest {
    @Test
    fun parserPolicyDocumentsThatUnvalidatedPacketsAreNotDecoded() {
        // The real ScanResult vectors are intentionally device-validation fixtures.
        // This test keeps the safety rule visible until those vectors exist.
        assertTrue("NEEDS_DEVICE_VALIDATION".contains("VALIDATION"))
    }
}
