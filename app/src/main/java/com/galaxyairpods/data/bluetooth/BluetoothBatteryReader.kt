package com.galaxyairpods.data.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Build
import java.nio.charset.StandardCharsets

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
    data class Snapshot(
        val main: Int? = null,
        val left: Int? = null,
        val right: Int? = null,
        val caseBattery: Int? = null,
    ) {
        val hasValue: Boolean
            get() = main != null || left != null || right != null || caseBattery != null

        val bestAvailable: Int?
            get() = main ?: left ?: right ?: caseBattery
    }

    private const val BATTERY_LEVEL_UNKNOWN = -1

    @SuppressLint("MissingPermission", "PrivateApi")
    fun read(device: BluetoothDevice): Int? = readSnapshot(device).bestAvailable

    /**
     * Reads every battery field the framework may have cached for an untethered
     * headset. Samsung exposes these fields on some releases even when the
     * public app-facing battery broadcast is never sent.
     */
    @SuppressLint("MissingPermission", "PrivateApi")
    fun readSnapshot(device: BluetoothDevice): Snapshot {
        val main = invokeBatteryLevel(device)
        val metadata = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mapOf(
                MetadataKey.MAIN to readMetadata(device, MetadataKey.MAIN.id),
                MetadataKey.LEFT to readMetadata(device, MetadataKey.LEFT.id),
                MetadataKey.RIGHT to readMetadata(device, MetadataKey.RIGHT.id),
                MetadataKey.CASE to readMetadata(device, MetadataKey.CASE.id),
            )
        } else {
            emptyMap()
        }

        return Snapshot(
            main = main ?: metadata[MetadataKey.MAIN],
            left = metadata[MetadataKey.LEFT],
            right = metadata[MetadataKey.RIGHT],
            caseBattery = metadata[MetadataKey.CASE],
        )
    }

    @SuppressLint("MissingPermission", "PrivateApi")
    private fun invokeBatteryLevel(device: BluetoothDevice): Int? {
        val method = runCatching {
            BluetoothDevice::class.java.getDeclaredMethod("getBatteryLevel").apply {
                isAccessible = true
            }
        }.getOrNull() ?: return null

        return runCatching {
            (method.invoke(device) as? Int)
                ?.takeIf { it in 0..100 && it != BATTERY_LEVEL_UNKNOWN }
        }.getOrNull()
    }

    @SuppressLint("PrivateApi")
    private fun readMetadata(device: BluetoothDevice, key: Int): Int? {
        val method = runCatching {
            BluetoothDevice::class.java.getDeclaredMethod("getMetadata", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
        }.getOrNull() ?: return null

        val raw = runCatching { method.invoke(device, key) as? ByteArray }.getOrNull() ?: return null
        val text = raw.toString(StandardCharsets.UTF_8).trim('\u0000', ' ', '\t', '\n', '\r')
        return text.toIntOrNull()?.takeIf { it in 0..100 }
            ?: raw.firstOrNull()?.toInt()?.takeIf { it in 0..100 }
    }

    private enum class MetadataKey(val id: Int) {
        // BluetoothDevice.METADATA_MAIN_BATTERY
        MAIN(18),
        // BluetoothDevice.METADATA_UNTETHERED_LEFT_BATTERY
        LEFT(10),
        // BluetoothDevice.METADATA_UNTETHERED_RIGHT_BATTERY
        RIGHT(11),
        // BluetoothDevice.METADATA_UNTETHERED_CASE_BATTERY
        CASE(12),
    }
}
