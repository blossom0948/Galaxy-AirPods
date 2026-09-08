package com.galaxyairpods.data.bluetooth

import com.galaxyairpods.domain.model.AirPodsConnectionState

/**
 * Pure connection-state policy. Proximity is deliberately not equivalent to
 * an Android audio connection; callers must provide the profile/AAP evidence
 * they actually observed in the current freshness window.
 */
internal data class BluetoothConnectionEvidence(
    val a2dpConnected: Boolean = false,
    val headsetConnected: Boolean = false,
    val aapReady: Boolean = false,
    val nearbyFresh: Boolean = false,
    val paired: Boolean = false,
    val knownBluetoothDevice: Boolean = false,
)

internal fun resolveAirPodsConnectionState(
    evidence: BluetoothConnectionEvidence,
): AirPodsConnectionState = when {
    evidence.a2dpConnected || evidence.headsetConnected || evidence.aapReady ->
        AirPodsConnectionState.ANDROID_CONNECTED
    evidence.nearbyFresh && evidence.paired ->
        AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING
    evidence.nearbyFresh -> AirPodsConnectionState.NEARBY_ONLY
    evidence.paired || evidence.knownBluetoothDevice -> AirPodsConnectionState.DISCONNECTED
    else -> AirPodsConnectionState.UNKNOWN
}
