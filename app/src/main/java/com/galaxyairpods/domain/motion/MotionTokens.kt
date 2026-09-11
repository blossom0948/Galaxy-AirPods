package com.galaxyairpods.domain.motion

/**
 * Stable motion values used by the production popup surface.
 */
object MotionTokens {
    const val CardInitialYOffsetRatio = 0.12f
    const val CardInitialScale = 0.94f
    const val CardDamping = 0.90f
    const val CardStiffness = 420f
    const val ScrimAlpha = 0.28f

    const val ProductDelayMs = 55L
    const val EarbudStaggerMs = 64L
    const val ProductInitialScale = 0.955f
    const val ProductInitialYOffsetDp = 9f
    const val ProductDamping = 0.86f
    const val ProductStiffness = 360f

    const val BatteryDelayMs = 180L
    const val BatteryItemStaggerMs = 70L
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
    val scrimAlpha: Float = MotionTokens.ScrimAlpha,
    val productDelayMs: Long = MotionTokens.ProductDelayMs,
    val earbudStaggerMs: Long = MotionTokens.EarbudStaggerMs,
    val productInitialScale: Float = MotionTokens.ProductInitialScale,
    val productInitialYOffsetDp: Float = MotionTokens.ProductInitialYOffsetDp,
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
    }
}
