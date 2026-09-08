package com.galaxyairpods.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.galaxyairpods.domain.model.AirPodsState

internal data class AudioRouteEvidence(
    val profileConnected: Boolean,
    val outputTypeIsBluetooth: Boolean,
    val outputAddressMatches: Boolean,
    val outputNameMatches: Boolean,
) {
    val activeForTarget: Boolean
        get() = outputTypeIsBluetooth &&
            // A matching Bluetooth output address is direct evidence that
            // Android is routing audio to this device. Samsung can briefly
            // report A2DP/HEADSET as disconnected while one AirPod remains
            // usable, so do not require both callbacks in the same poll.
            // Name-only matching still requires profile proof to avoid acting
            // on a nearby device.
            (outputAddressMatches || (profileConnected && outputNameMatches))
}

/**
 * Media actions are allowed only when Android reports a matching Bluetooth
 * A2DP/SCO output. A current profile callback is required for name-only
 * matching, but an exact output address is sufficient when Samsung's profile
 * callback is one poll behind. A BLE advertisement, GATT connection, or AAP
 * socket alone cannot pass this gate.
 */
internal class AndroidAudioRouteGate(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    fun check(state: AirPodsState): AudioRouteEvidence {
        val profileConnected = state.isAndroidConnected &&
            (state.a2dpConnected || state.headsetConnected)
        val outputs = runCatching {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        }.getOrDefault(emptyArray())
        val bluetoothOutputs = outputs.filter { it.isBluetoothOutput() }
        val targetAddress = normalize(state.deviceId)
        val targetName = normalize(state.deviceName)
        val outputAddressMatches = bluetoothOutputs.any { output ->
            targetAddress.isNotBlank() && normalize(output.address) == targetAddress
        }
        // Samsung may expose the composite AirPods output with a different
        // address from the bonded profile address when only one bud remains
        // active.  The profile flag is still a target-device proof, so a
        // matching AirPods product name remains valid even when the output
        // address is present but not equal to the profile address.
        val outputNameMatches = bluetoothOutputs.any { output ->
            namesMatch(targetName, normalize(output.productName?.toString()))
        }
        return AudioRouteEvidence(
            profileConnected = profileConnected,
            outputTypeIsBluetooth = bluetoothOutputs.isNotEmpty(),
            outputAddressMatches = outputAddressMatches,
            outputNameMatches = outputNameMatches,
        )
    }

    private fun AudioDeviceInfo.isBluetoothOutput(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        -> true
        else -> false
    }

    private fun namesMatch(target: String, output: String): Boolean {
        if (target.isBlank() || output.isBlank()) return false
        if (!target.contains("airpod") || !output.contains("airpod")) return false
        return target.contains(output) || output.contains(target)
    }

    private fun normalize(value: String?): String = value.orEmpty()
        .lowercase()
        .filter { it.isLetterOrDigit() }
}
