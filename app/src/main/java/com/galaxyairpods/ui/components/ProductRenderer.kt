package com.galaxyairpods.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsState

/**
 * A lightweight, original renderer used until a licensed product asset pipeline
 * is available. Each visual layer has its own transform so the motion lab can
 * validate state changes without bundling Apple's private imagery.
 */
@Composable
fun ProductRenderer(
    state: AirPodsState,
    modifier: Modifier = Modifier,
    openProgress: Float = if (state.caseOpen == true) 1f else 0f,
    leftLift: Float = 0f,
    rightLift: Float = 0f,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(188.dp)
            .semantics { contentDescription = "독자 제작 AirPods 스타일 제품 렌더" },
    ) {
        Canvas(Modifier.fillMaxWidth().height(188.dp)) {
            drawProduct(
                openProgress = openProgress.coerceIn(0f, 1f),
                leftLift = leftLift,
                rightLift = rightLift,
            )
        }
    }
}

private fun DrawScope.drawProduct(
    openProgress: Float,
    leftLift: Float,
    rightLift: Float,
) {
    val center = size.width / 2f
    val bodyWidth = size.width * 0.52f
    val bodyHeight = size.height * 0.36f
    val bodyLeft = center - bodyWidth / 2f
    val bodyTop = size.height * 0.52f
    val hinge = Offset(center, bodyTop + 5f)

    drawOval(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(bodyLeft - 18f, bodyTop + bodyHeight - 3f),
        size = Size(bodyWidth + 36f, 20f),
    )

    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFF9FBFF), Color(0xFFBFC8D6)),
            start = Offset(bodyLeft, bodyTop),
            end = Offset(bodyLeft + bodyWidth, bodyTop + bodyHeight),
        ),
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(24f, 24f),
    )
    drawRoundRect(
        color = Color(0xFF8C99AB).copy(alpha = 0.34f),
        topLeft = Offset(bodyLeft + bodyWidth * 0.16f, bodyTop + bodyHeight * 0.18f),
        size = Size(bodyWidth * 0.68f, bodyHeight * 0.12f),
        cornerRadius = CornerRadius(8f, 8f),
    )

    // The lid rotates around the rear hinge, not around the bitmap center.
    rotate(degrees = -48f * openProgress, pivot = hinge) {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFD6DEEA)),
                start = Offset(bodyLeft, bodyTop - bodyHeight * 0.74f),
                end = Offset(bodyLeft + bodyWidth, bodyTop),
            ),
            topLeft = Offset(bodyLeft, bodyTop - bodyHeight * 0.72f),
            size = Size(bodyWidth, bodyHeight * 0.76f),
            cornerRadius = CornerRadius(23f, 23f),
        )
        drawRoundRect(
            color = Color(0xFF93A0B2).copy(alpha = 0.22f),
            topLeft = Offset(bodyLeft + bodyWidth * 0.15f, bodyTop - bodyHeight * 0.57f),
            size = Size(bodyWidth * 0.70f, 10f),
            cornerRadius = CornerRadius(8f, 8f),
        )
    }

    drawEarbud(
        center = Offset(center - bodyWidth * 0.26f, bodyTop + bodyHeight * 0.12f + leftLift),
        tint = Color(0xFFEAF0F7),
    )
    drawEarbud(
        center = Offset(center + bodyWidth * 0.26f, bodyTop + bodyHeight * 0.10f + rightLift),
        tint = Color(0xFFDDE7F1),
    )
}

private fun DrawScope.drawEarbud(center: Offset, tint: Color) {
    drawCircle(
        brush = Brush.radialGradient(listOf(Color.White, tint, Color(0xFFAEBBCB))),
        radius = 21f,
        center = center,
    )
    drawRoundRect(
        color = tint,
        topLeft = Offset(center.x - 6f, center.y + 10f),
        size = Size(12f, 30f),
        cornerRadius = CornerRadius(6f, 6f),
    )
    drawCircle(
        color = Color(0xFF7B899A).copy(alpha = 0.52f),
        radius = 3f,
        center = Offset(center.x, center.y + 27f),
    )
}
