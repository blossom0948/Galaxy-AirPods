package com.galaxyairpods.data.bluetooth

import com.galaxyairpods.domain.model.AirPodsWearState

private const val DEFAULT_WEAR_TTL_MS = 15_000L

internal enum class WearEventSource {
    AAP_CLASSIC,
    BLE_PUBLIC,
}

/** A validated, timestamped candidate from one device and one transport. */
internal data class WearEventCandidate(
    val deviceProfileId: String,
    val source: WearEventSource,
    val state: AirPodsWearState,
    val capturedAtElapsedMs: Long,
    val receivedAtElapsedMs: Long,
    val freshnessTtlMs: Long = DEFAULT_WEAR_TTL_MS,
    val identityProof: Boolean = true,
    val parserValid: Boolean = true,
)

internal data class StableWearObservation(
    val deviceProfileId: String,
    val source: WearEventSource,
    val state: AirPodsWearState,
    val capturedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
)

/**
 * Validates raw source output before it is allowed to affect a per-device
 * counter. The stabilizer is responsible for sequence continuity; this
 * boundary is responsible for evidence quality.
 */
internal class EarEventValidator(
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    fun validate(candidate: WearEventCandidate): WearEventCandidate? {
        val nowElapsedMs = clock()
        if (candidate.deviceProfileId.isBlank() ||
            !candidate.identityProof ||
            !candidate.parserValid ||
            candidate.state == AirPodsWearState.UNKNOWN ||
            candidate.state == AirPodsWearState.CONFLICT ||
            candidate.capturedAtElapsedMs <= 0L ||
            candidate.receivedAtElapsedMs <= 0L ||
            candidate.freshnessTtlMs <= 0L ||
            candidate.capturedAtElapsedMs > nowElapsedMs + MAX_CLOCK_SKEW_MS ||
            candidate.receivedAtElapsedMs > nowElapsedMs + MAX_CLOCK_SKEW_MS ||
            candidate.capturedAtElapsedMs > candidate.receivedAtElapsedMs + MAX_CLOCK_SKEW_MS ||
            nowElapsedMs - candidate.capturedAtElapsedMs > candidate.freshnessTtlMs
        ) {
            return null
        }
        return candidate
    }

    private companion object {
        const val MAX_CLOCK_SKEW_MS = 1_000L
    }
}

internal fun mapAapWearState(
    snapshot: AapEarDetectionSnapshot,
    primaryPodIsLeft: Boolean?,
): AirPodsWearState {
    val primaryInEar = snapshot.primary == AapEarStatus.IN_EAR
    val secondaryInEar = snapshot.secondary == AapEarStatus.IN_EAR
    val primaryInCase = snapshot.primary == AapEarStatus.IN_CASE
    val secondaryInCase = snapshot.secondary == AapEarStatus.IN_CASE

    if (primaryInEar && secondaryInEar) return AirPodsWearState.BOTH_IN_EAR
    if (primaryInEar || secondaryInEar) {
        // AAP tells us that one physical pod is in the ear, but it does not
        // identify that pod as left/right.  BLE public status usually supplies
        // the orientation; when it is unavailable, keep the usable partial
        // wear evidence instead of dropping the event and blocking media
        // control.  Never guess a left/right side.
        if (primaryPodIsLeft == null) return AirPodsWearState.PARTIAL_IN_EAR
        val inEarIsLeft = if (primaryInEar) primaryPodIsLeft else !primaryPodIsLeft
        return if (inEarIsLeft) AirPodsWearState.LEFT_IN_EAR
        else AirPodsWearState.RIGHT_IN_EAR
    }
    if (primaryInCase || secondaryInCase) return AirPodsWearState.IN_CASE
    // 0x03 is emitted by some AirPods Pro/Samsung AAP sessions when the
    // corresponding pod is temporarily unavailable. It is known to mean
    // "not in ear" for the purpose of a wear transition, but it must never
    // be interpreted as "in case" or as a battery value.
    val allKnownNotInEar = listOf(snapshot.primary, snapshot.secondary).all {
        it == AapEarStatus.OUT_OF_EAR || it == AapEarStatus.DISCONNECTED
    }
    return if (allKnownNotInEar) {
        AirPodsWearState.NONE_IN_EAR
    } else {
        AirPodsWearState.UNKNOWN
    }
}

/**
 * Stabilizes wear frames independently for each device/source pair.
 *
 * A source switch never borrows a counter from the previous source. Once both
 * sources have produced fresh stable values for the same profile, disagreement
 * is surfaced as CONFLICT instead of silently selecting one transport.
 */
internal class PerDeviceWearStabilizer(
    private val requiredFrames: Int = 3,
    private val maxFrameGapMs: Long = 3_000L,
    private val requiredFramesForSource: (WearEventSource) -> Int = { requiredFrames },
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) {
    private data class Key(val deviceProfileId: String, val source: WearEventSource)

    private data class CandidateProgress(
        val state: AirPodsWearState,
        val firstCapturedAtElapsedMs: Long,
        val lastCapturedAtElapsedMs: Long,
        val count: Int,
    )

    private val progress = mutableMapOf<Key, CandidateProgress>()
    private val stable = mutableMapOf<Key, StableWearObservation>()
    private val validator = EarEventValidator(clock)

    fun reset() {
        progress.clear()
        stable.clear()
    }

    fun resetDevice(deviceProfileId: String) {
        progress.keys.removeAll { it.deviceProfileId == deviceProfileId }
        stable.keys.removeAll { it.deviceProfileId == deviceProfileId }
    }

    fun resetSource(deviceProfileId: String, source: WearEventSource) {
        val key = Key(deviceProfileId, source)
        progress.remove(key)
        stable.remove(key)
    }

    fun accept(candidate: WearEventCandidate): StableWearObservation? {
        val now = clock()
        val key = Key(candidate.deviceProfileId, candidate.source)
        val validated = validator.validate(candidate)
        if (validated == null) {
            progress.remove(key)
            return null
        }

        val previous = progress[key]
        val sourceRequiredFrames = requiredFramesForSource(validated.source).coerceAtLeast(1)
        val next = when {
            previous == null -> CandidateProgress(
                state = validated.state,
                firstCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                lastCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                count = 1,
            )
            validated.capturedAtElapsedMs <= previous.lastCapturedAtElapsedMs -> return null
            validated.capturedAtElapsedMs - previous.lastCapturedAtElapsedMs > maxFrameGapMs ->
                CandidateProgress(
                    state = validated.state,
                    firstCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                    lastCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                    count = 1,
                )
            previous.state != validated.state -> CandidateProgress(
                state = validated.state,
                firstCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                lastCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                count = 1,
            )
            else -> previous.copy(
                lastCapturedAtElapsedMs = validated.capturedAtElapsedMs,
                count = previous.count + 1,
            )
        }
        progress[key] = next
        if (next.count < sourceRequiredFrames) return null

        val expiresAt = validated.capturedAtElapsedMs + validated.freshnessTtlMs
        val other = stable.entries
            .asSequence()
            .filter { (otherKey, _) ->
                otherKey.deviceProfileId == validated.deviceProfileId &&
                    otherKey.source != validated.source
            }
            .map { it.value }
            .filter {
                now >= it.capturedAtElapsedMs && now <= it.expiresAtElapsedMs
            }
            .firstOrNull()
        val resolvedState = if (other != null && other.state != validated.state) {
            // PARTIAL_IN_EAR intentionally omits the physical side. A
            // side-specific frame from the other transport is compatible
            // evidence, not a contradiction, when both say exactly one bud
            // is in an ear.
            if (isWearConflict(other.state, validated.state)) {
                AirPodsWearState.CONFLICT
            } else {
                validated.state
            }
        } else {
            validated.state
        }
        val result = StableWearObservation(
            deviceProfileId = validated.deviceProfileId,
            source = validated.source,
            state = resolvedState,
            capturedAtElapsedMs = validated.capturedAtElapsedMs,
            expiresAtElapsedMs = expiresAt,
        )
        stable[key] = result.copy(state = validated.state)
        return result
    }

    private fun isWearConflict(
        first: AirPodsWearState,
        second: AirPodsWearState,
    ): Boolean {
        if (first == second) return false
        val compatiblePartial =
            (first == AirPodsWearState.PARTIAL_IN_EAR &&
                second in SIDE_SPECIFIC_ONE_EAR_STATES) ||
                (second == AirPodsWearState.PARTIAL_IN_EAR &&
                    first in SIDE_SPECIFIC_ONE_EAR_STATES)
        return !compatiblePartial
    }

    private companion object {
        val SIDE_SPECIFIC_ONE_EAR_STATES = setOf(
            AirPodsWearState.LEFT_IN_EAR,
            AirPodsWearState.RIGHT_IN_EAR,
        )
    }
}
