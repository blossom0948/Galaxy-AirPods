package com.galaxyairpods.data.bluetooth.legacy

/**
 * Compatibility boundary for Android/OEM battery events that are not part of
 * the verified AirPods telemetry path.
 *
 * V4 keeps this adapter disabled by default. A single value from an HFP or
 * hidden Android API event is never allowed to become separate L/R/Case
 * samples or an exact capability claim.
 */
interface LegacyBatteryAdapter {
    val enabled: Boolean

    fun observe(event: Any?): LegacyBatteryObservation?
}

data class LegacyBatteryObservation(
    val deviceKey: String,
    val singleValue: Int?,
    val capturedAtEpochMs: Long,
)

object LegacyFeatureFlags {
    /** Explicit opt-in is required before this compatibility path can run. */
    const val classicBatteryAdapterEnabled: Boolean = false
}

object DisabledLegacyBatteryAdapter : LegacyBatteryAdapter {
    override val enabled: Boolean = false

    override fun observe(event: Any?): LegacyBatteryObservation? = null
}
