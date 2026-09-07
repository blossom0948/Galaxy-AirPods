package com.galaxyairpods.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice

/**
 * Reads the battery value cached by Android's Bluetooth stack.
 *
 * Android keeps this API hidden even though Settings uses it for the
 * `android.bluetooth.device.action.BATTERY_LEVEL_CHANGED` feature. Samsung's
 * stack can have a valid value here while it does not deliver the broadcast
 * or the HFP vendor event to third-party apps, so this is an important
 * fallback for already-connected headsets.
 */
internal object BluetoothBatteryReader {
    @SuppressLint("MissingPermission", "PrivateApi")
    fun read(device: BluetoothDevice): Int? {
        val method = runCatching {
            BluetoothDevice::class.java.getDeclaredMethod("getBatteryLevel")
        }.getOrNull() ?: return null

        return runCatching {
            (method.invoke(device) as? Int)?.takeIf { it in 0..100 }
        }.getOrNull()
    }
}
