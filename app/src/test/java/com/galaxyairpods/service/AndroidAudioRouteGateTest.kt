package com.galaxyairpods.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAudioRouteGateTest {
    @Test
    fun routeRequiresProfileBluetoothOutputAndIdentityMatch() {
        assertTrue(
            AudioRouteEvidence(
                profileConnected = true,
                outputTypeIsBluetooth = true,
                outputAddressMatches = true,
                outputNameMatches = false,
            ).activeForTarget,
        )
        assertTrue(
            AudioRouteEvidence(
                profileConnected = true,
                outputTypeIsBluetooth = true,
                outputAddressMatches = false,
                outputNameMatches = true,
            ).activeForTarget,
        )
        assertTrue(
            AudioRouteEvidence(
                profileConnected = false,
                outputTypeIsBluetooth = true,
                outputAddressMatches = true,
                outputNameMatches = true,
            ).activeForTarget,
        )
        assertFalse(
            AudioRouteEvidence(
                profileConnected = true,
                outputTypeIsBluetooth = false,
                outputAddressMatches = true,
                outputNameMatches = true,
            ).activeForTarget,
        )
    }

    @Test
    fun exactBluetoothOutputAddressAllowsOneEarbudWhenProfilePollLags() {
        assertTrue(
            AudioRouteEvidence(
                profileConnected = false,
                outputTypeIsBluetooth = true,
                outputAddressMatches = true,
                outputNameMatches = false,
            ).activeForTarget,
        )
    }

    @Test
    fun nameOnlyMatchStillRequiresProfileProof() {
        assertFalse(
            AudioRouteEvidence(
                profileConnected = false,
                outputTypeIsBluetooth = true,
                outputAddressMatches = false,
                outputNameMatches = true,
            ).activeForTarget,
        )
    }

    @Test
    fun profileAndAirPodsNameMatchStillPassesWhenSamsungReportsAnotherOutputAddress() {
        // On a one-ear route the profile address and AudioDeviceInfo address
        // can differ.  The connected A2DP/HEADSET profile plus matching
        // AirPods product name is enough to identify the active route.
        assertTrue(
            AudioRouteEvidence(
                profileConnected = true,
                outputTypeIsBluetooth = true,
                outputAddressMatches = false,
                outputNameMatches = true,
            ).activeForTarget,
        )
    }
}
