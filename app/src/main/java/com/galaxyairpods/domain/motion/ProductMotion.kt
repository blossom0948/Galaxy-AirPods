package com.galaxyairpods.domain.motion

/** One presentation clock. Sensor updates cannot restart the product choreography. */
data class ProductPose(
    val lid: Float = 0f,
    val leftLift: Float = 0f,
    val rightLift: Float = 0f,
    val dock: Float = 0f,
)

object ProductMotion {
    const val LidStartMs = 80
    const val LidDurationMs = 420
    const val LiftStartMs = 540
    const val LiftDurationMs = 300
    const val DockStartMs = 900
    const val DockDurationMs = 480
    const val DurationMs = DockStartMs + DockDurationMs

    fun frame(elapsedMs: Float): ProductPose = frame(
        elapsedMs = elapsedMs,
        caseOpen = true,
        leftOutOfCase = true,
        rightOutOfCase = true,
    )

    /** Build a pose from the live case/bud state and the shared clock. */
    fun frame(
        elapsedMs: Float,
        caseOpen: Boolean,
        leftOutOfCase: Boolean,
        rightOutOfCase: Boolean,
    ): ProductPose {
        val lift = if (caseOpen) {
            segment(elapsedMs, LiftStartMs, LiftDurationMs)
        } else {
            1f
        }
        return ProductPose(
            lid = if (caseOpen) segment(elapsedMs, LidStartMs, LidDurationMs) else 0f,
            leftLift = if (leftOutOfCase) lift else 0f,
            rightLift = if (rightOutOfCase) lift else 0f,
            dock = if (caseOpen && leftOutOfCase && rightOutOfCase) {
                segment(elapsedMs, DockStartMs, DockDurationMs)
            } else {
                0f
            },
        )
    }

    fun segment(value: Float, start: Int, duration: Int): Float =
        smooth(((value - start) / duration).coerceIn(0f, 1f))

    fun smooth(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
