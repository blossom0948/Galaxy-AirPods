package com.galaxyairpods.data.bluetooth

import com.galaxyairpods.domain.model.AirPodsWearState

/**
 * Converts AAP's primary/secondary statuses to physical L/R only when the
 * current profile has supplied the primary-side mapping. A single malformed
 * or transient frame cannot change the user-visible state.
 */
internal class AapWearStabilizer(
    private val requiredFrames: Int = 3,
) {
    private var candidate: AirPodsWearState? = null
    private var candidateAt: Long = 0L
    private var count: Int = 0

    fun reset() {
        candidate = null
        candidateAt = 0L
        count = 0
    }

    fun accept(
        snapshot: AapEarDetectionSnapshot,
        primaryPodIsLeft: Boolean?,
        seenAt: Long,
    ): AirPodsWearState? {
        val mapped = map(snapshot, primaryPodIsLeft) ?: return null
        if (seenAt - candidateAt > FRESH_FRAME_WINDOW_MS || mapped != candidate) {
            candidate = mapped
            count = 1
        } else {
            count += 1
        }
        candidateAt = seenAt
        return mapped.takeIf { count >= requiredFrames }
    }

    private fun map(
        snapshot: AapEarDetectionSnapshot,
        primaryPodIsLeft: Boolean?,
    ): AirPodsWearState? {
        if (primaryPodIsLeft == null) return null
        val primaryInEar = snapshot.primary == AapEarStatus.IN_EAR
        val secondaryInEar = snapshot.secondary == AapEarStatus.IN_EAR
        val primaryInCase = snapshot.primary == AapEarStatus.IN_CASE
        val secondaryInCase = snapshot.secondary == AapEarStatus.IN_CASE

        if ((primaryInEar && secondaryInCase) || (secondaryInEar && primaryInCase)) {
            return AirPodsWearState.CONFLICT
        }
        if (primaryInCase || secondaryInCase) return AirPodsWearState.IN_CASE
        if (primaryInEar && secondaryInEar) return AirPodsWearState.BOTH_IN_EAR
        if (!primaryInEar && !secondaryInEar) return AirPodsWearState.NONE_IN_EAR
        return if (primaryInEar == primaryPodIsLeft) {
            AirPodsWearState.LEFT_IN_EAR
        } else {
            AirPodsWearState.RIGHT_IN_EAR
        }
    }

    private companion object {
        const val FRESH_FRAME_WINDOW_MS = 3_000L
    }
}
