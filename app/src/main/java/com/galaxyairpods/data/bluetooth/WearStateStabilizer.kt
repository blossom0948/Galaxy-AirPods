package com.galaxyairpods.data.bluetooth

import com.galaxyairpods.domain.model.AirPodsWearState

/** Shared consecutive-frame gate for public BLE wear observations. */
internal class WearStateStabilizer(
    private val requiredFrames: Int = 3,
    private val frameWindowMs: Long = 3_000L,
) {
    private var candidate: AirPodsWearState? = null
    private var candidateAt = 0L
    private var count = 0

    fun reset() {
        candidate = null
        candidateAt = 0L
        count = 0
    }

    fun accept(value: AirPodsWearState, seenAt: Long): AirPodsWearState? {
        if (seenAt - candidateAt > frameWindowMs || candidate != value) {
            candidate = value
            count = 1
        } else {
            count += 1
        }
        candidateAt = seenAt
        return value.takeIf { count >= requiredFrames }
    }
}
