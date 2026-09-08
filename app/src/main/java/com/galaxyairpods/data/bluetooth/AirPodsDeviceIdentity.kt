package com.galaxyairpods.data.bluetooth

import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.isMax
import com.galaxyairpods.domain.model.isPro
import java.security.MessageDigest

/**
 * Creates a stable, redacted profile id for joining observations from the
 * Classic and BLE paths. The Bluetooth address itself remains an in-memory
 * transport handle; it is never used as the wear stabilizer key in logs or
 * exported diagnostics.
 */
internal object AirPodsDeviceIdentity {
    fun profileId(deviceAddress: String, model: AirPodsModel): String {
        val input = "${deviceAddress.trim().uppercase()}|${model.familyKey()}"
        return MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }
            .take(20)
    }

    private fun AirPodsModel.familyKey(): String = when {
        isPro -> "PRO"
        isMax -> "MAX"
        else -> "OPEN"
    }
}
