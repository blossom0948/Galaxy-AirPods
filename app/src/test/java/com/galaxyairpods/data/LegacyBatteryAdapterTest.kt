package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.legacy.DisabledLegacyBatteryAdapter
import com.galaxyairpods.data.bluetooth.legacy.LegacyFeatureFlags
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class LegacyBatteryAdapterTest {
    @Test
    fun legacyBatteryPathIsDisabledByDefault() {
        assertFalse(LegacyFeatureFlags.classicBatteryAdapterEnabled)
        assertFalse(DisabledLegacyBatteryAdapter.enabled)
        assertNull(DisabledLegacyBatteryAdapter.observe(Any()))
    }
}
