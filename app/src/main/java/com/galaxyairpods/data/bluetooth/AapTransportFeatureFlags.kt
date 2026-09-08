package com.galaxyairpods.data.bluetooth

import android.os.Build

/**
 * V4 transport policy. The hidden Classic socket probe is deliberately
 * isolated behind a narrow Samsung tuple allowlist. It is not a general
 * Android fallback and it never promotes a device to T2 by itself; only a
 * real AAP response followed by a valid 0x0004 packet can do that.
 */
internal object AapTransportFeatureFlags {
    private const val ENABLE_HIDDEN_CLASSIC_PROBE = true

    fun hiddenClassicL2capAllowed(): Boolean = ENABLE_HIDDEN_CLASSIC_PROBE &&
        Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
        Build.MODEL == "SM-F956N" &&
        Build.VERSION.SDK_INT >= 36
}
