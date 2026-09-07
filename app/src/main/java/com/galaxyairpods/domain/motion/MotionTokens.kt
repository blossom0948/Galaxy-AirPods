package com.galaxyairpods.domain.motion

import com.galaxyairpods.domain.model.PreviewPreset

/**
 * Reverse-engineering baseline for Galaxy implementation.
 * NOT Apple's private/internal animation constants.
 * Tune against current iOS reference footage and Preview Lab.
 */
object MotionTokens {
    const val CardInitialYOffsetRatio = 0.22f
    const val CardInitialScale = 0.972f
    const val CardDamping = 0.90f
    const val CardStiffness = 420f

    const val ProductDelayMs = 55L
    const val ProductInitialScale = 0.955f
    const val ProductInitialYOffsetDp = 9f
    const val ProductDamping = 0.86f
    const val ProductStiffness = 360f

    const val BatteryDelayMs = 200L
    const val BatteryItemStaggerMs = 35L
    const val BatteryInitialYOffsetDp = 6f

    const val ExitYOffsetRatio = 0.16f
    const val ExitTargetScale = 0.99f
    const val ExitDurationMs = 220
    const val BatteryFillDurationMs = 520
}

data class MotionLabSettings(
    val cardDamping: Float = MotionTokens.CardDamping,
    val cardStiffness: Float = MotionTokens.CardStiffness,
    val cardInitialY: Float = MotionTokens.CardInitialYOffsetRatio,
    val cardInitialScale: Float = MotionTokens.CardInitialScale,
    val productDelayMs: Long = MotionTokens.ProductDelayMs,
    val productDamping: Float = MotionTokens.ProductDamping,
    val productStiffness: Float = MotionTokens.ProductStiffness,
    val batteryDelayMs: Long = MotionTokens.BatteryDelayMs,
    val batteryStaggerMs: Long = MotionTokens.BatteryItemStaggerMs,
    val exitY: Float = MotionTokens.ExitYOffsetRatio,
    val exitScale: Float = MotionTokens.ExitTargetScale,
    val playbackSpeed: Float = 1f,
) {
    companion object {
        val Default = MotionLabSettings()

        fun forPreset(preset: PreviewPreset): MotionLabSettings = when (preset) {
            PreviewPreset.EXIT,
            PreviewPreset.INTERRUPT_ENTER_TO_EXIT,
            PreviewPreset.INTERRUPT_EXIT_TO_ENTER,
            -> Default.copy(exitY = 0.18f)
            else -> Default
        }
    }
}
