package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.BluetoothConnectionEvidence
import com.galaxyairpods.data.bluetooth.resolveAirPodsConnectionState
import com.galaxyairpods.domain.model.AirPodsConnectionState
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPodsConnectionResolverTest {
    @Test
    fun profileConnectionWinsOverNearbyOrPairedEvidence() {
        assertEquals(
            AirPodsConnectionState.ANDROID_CONNECTED,
            resolveAirPodsConnectionState(
                BluetoothConnectionEvidence(
                    a2dpConnected = true,
                    nearbyFresh = true,
                    paired = true,
                ),
            ),
        )
        assertEquals(
            AirPodsConnectionState.ANDROID_CONNECTED,
            resolveAirPodsConnectionState(BluetoothConnectionEvidence(aapReady = true)),
        )
    }

    @Test
    fun nearbyPairedDeviceIsNotReportedAsAndroidConnected() {
        assertEquals(
            AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING,
            resolveAirPodsConnectionState(
                BluetoothConnectionEvidence(nearbyFresh = true, paired = true),
            ),
        )
    }

    @Test
    fun nearbyUnpairedDeviceUsesNearbyOnly() {
        assertEquals(
            AirPodsConnectionState.NEARBY_ONLY,
            resolveAirPodsConnectionState(BluetoothConnectionEvidence(nearbyFresh = true)),
        )
    }

    @Test
    fun knownDeviceWithoutFreshEvidenceIsDisconnected() {
        assertEquals(
            AirPodsConnectionState.DISCONNECTED,
            resolveAirPodsConnectionState(
                BluetoothConnectionEvidence(paired = true),
            ),
        )
    }

    @Test
    fun missingIdentityAndEvidenceIsUnknown() {
        assertEquals(
            AirPodsConnectionState.UNKNOWN,
            resolveAirPodsConnectionState(BluetoothConnectionEvidence()),
        )
    }
}
