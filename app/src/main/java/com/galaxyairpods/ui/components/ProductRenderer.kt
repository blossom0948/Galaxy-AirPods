package com.galaxyairpods.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.isMax
import com.galaxyairpods.domain.model.isPro

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
            .semantics { contentDescription = state.model.label + " 제품" },
    ) {
        Canvas(Modifier.fillMaxWidth().height(188.dp)) {
            if (state.model.isMax) {
                drawMaxHeadphones()
            } else {
                drawEarbudCase(
                    model = state.model,
                    openProgress = openProgress.coerceIn(0f, 1f),
                    leftLift = leftLift,
                    rightLift = rightLift,
                )
            }
        }
    }
}

private fun DrawScope.drawEarbudCase(
    model: AirPodsModel,
    openProgress: Float,
    leftLift: Float,
    rightLift: Float,
) {
    val center = size.width / 2f
    val bodyWidth = size.width * 0.52f
    val bodyHeight = size.height * 0.36f
    val bodyLeft = center - bodyWidth / 2f
    val bodyTop = size.height * 0.53f
    val hinge = Offset(center, bodyTop + 5f)

    drawOval(
        color = Color.Black.copy(alpha = 0.24f),
        topLeft = Offset(bodyLeft - 20f, bodyTop + bodyHeight - 2f),
        size = Size(bodyWidth + 40f, 22f),
    )
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(Color(0xFFFFFFFF), Color(0xFFD5DDE8), Color(0xFF9EAABD)),
            start = Offset(bodyLeft, bodyTop),
            end = Offset(bodyLeft + bodyWidth, bodyTop + bodyHeight),
        ),
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(30f, 30f),
    )
    drawRoundRect(
        color = Color(0xFF7C899A).copy(alpha = 0.26f),
        topLeft = Offset(bodyLeft + bodyWidth * 0.15f, bodyTop + bodyHeight * 0.20f),
        size = Size(bodyWidth * 0.70f, bodyHeight * 0.12f),
        cornerRadius = CornerRadius(9f, 9f),
    )
    drawCircle(
        color = Color(0xFF8795A7).copy(alpha = 0.42f),
        radius = 4.5f,
        center = Offset(center, bodyTop + bodyHeight * 0.53f),
    )

    rotate(degrees = -54f * openProgress, pivot = hinge) {
        drawRoundRect(
            brush = Brush.linearGradient(
                colors = listOf(Color(0xFFFFFFFF), Color(0xFFD5DDE8)),
                start = Offset(bodyLeft, bodyTop - bodyHeight * 0.72f),
                end = Offset(bodyLeft + bodyWidth, bodyTop),
            ),
            topLeft = Offset(bodyLeft, bodyTop - bodyHeight * 0.72f),
            size = Size(bodyWidth, bodyHeight * 0.76f),
            cornerRadius = CornerRadius(27f, 27f),
        )
        drawRoundRect(
            color = Color(0xFF8391A3).copy(alpha = 0.20f),
            topLeft = Offset(bodyLeft + bodyWidth * 0.15f, bodyTop - bodyHeight * 0.57f),
            size = Size(bodyWidth * 0.70f, 10f),
            cornerRadius = CornerRadius(8f, 8f),
        )
    }

    val isPro = model.isPro
    val isShortStem = model == AirPodsModel.AIRPODS_GEN3 ||
        model == AirPodsModel.AIRPODS_GEN4 ||
        model == AirPodsModel.AIRPODS_GEN4_ANC
    val headRadius = if (isPro) 18f else if (isShortStem) 20f else 19f
    val stemLength = if (isPro) 25f else if (isShortStem) 29f else 39f

    drawEarbud(
        center = Offset(center - bodyWidth * 0.27f, bodyTop + bodyHeight * 0.10f + leftLift),
        headRadius = headRadius,
        stemLength = stemLength,
        proTip = isPro,
        tilt = -8f,
    )
    drawEarbud(
        center = Offset(center + bodyWidth * 0.27f, bodyTop + bodyHeight * 0.08f + rightLift),
        headRadius = headRadius,
        stemLength = stemLength,
        proTip = isPro,
        tilt = 8f,
    )
}

private fun DrawScope.drawEarbud(
    center: Offset,
    headRadius: Float,
    stemLength: Float,
    proTip: Boolean,
    tilt: Float,
) {
    rotate(degrees = tilt, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color(0xFFE2E9F1), Color(0xFFA9B6C6)),
                center = center,
                radius = headRadius * 1.4f,
            ),
            radius = headRadius,
            center = center,
        )
        if (proTip) {
            drawOval(
                color = Color(0xFF667487),
                topLeft = Offset(center.x - 8f, center.y - 3f),
                size = Size(16f, 9f),
            )
            drawOval(
                color = Color(0xFF3C4757).copy(alpha = 0.78f),
                topLeft = Offset(center.x - 5f, center.y - 1f),
                size = Size(10f, 5f),
            )
        } else {
            drawCircle(
                color = Color(0xFF708094).copy(alpha = 0.75f),
                radius = 4f,
                center = Offset(center.x, center.y - 1f),
            )
        }
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFF0F4F8), Color(0xFFB7C3D1))),
            topLeft = Offset(center.x - 6f, center.y + headRadius * 0.55f),
            size = Size(12f, stemLength),
            cornerRadius = CornerRadius(6f, 6f),
        )
        drawCircle(
            color = Color(0xFF4B5A6C).copy(alpha = 0.70f),
            radius = 2.7f,
            center = Offset(center.x, center.y + headRadius * 0.55f + stemLength - 5f),
        )
    }
}

private fun DrawScope.drawMaxHeadphones() {
    val center = size.width / 2f
    val cupY = size.height * 0.57f
    val cupWidth = size.width * 0.26f
    val cupHeight = size.height * 0.40f
    val gap = size.width * 0.18f
    val leftX = center - gap - cupWidth / 2f
    val rightX = center + gap - cupWidth / 2f
    val headbandRect = Rect(
        left = center - size.width * 0.25f,
        top = size.height * 0.10f,
        right = center + size.width * 0.25f,
        bottom = size.height * 0.85f,
    )

    drawArc(
        color = Color(0xFFB6C1CF),
        startAngle = 195f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = headbandRect.topLeft,
        size = headbandRect.size,
        style = Stroke(width = 12f),
    )
    drawLine(
        color = Color(0xFF8997AA),
        start = Offset(leftX + cupWidth * 0.84f, size.height * 0.29f),
        end = Offset(leftX + cupWidth * 0.84f, cupY - cupHeight * 0.34f),
        strokeWidth = 8f,
    )
    drawLine(
        color = Color(0xFF8997AA),
        start = Offset(rightX + cupWidth * 0.16f, size.height * 0.29f),
        end = Offset(rightX + cupWidth * 0.16f, cupY - cupHeight * 0.34f),
        strokeWidth = 8f,
    )
    drawOval(
        color = Color.Black.copy(alpha = 0.22f),
        topLeft = Offset(leftX - 4f, cupY + cupHeight * 0.36f),
        size = Size(cupWidth * 2f + gap * 2f + 8f, 17f),
    )
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFE9EEF5), Color(0xFF9BA8B9))),
        topLeft = Offset(leftX, cupY - cupHeight / 2f),
        size = Size(cupWidth, cupHeight),
        cornerRadius = CornerRadius(28f, 28f),
    )
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFE9EEF5), Color(0xFF9BA8B9))),
        topLeft = Offset(rightX, cupY - cupHeight / 2f),
        size = Size(cupWidth, cupHeight),
        cornerRadius = CornerRadius(28f, 28f),
    )
    drawCircle(
        color = Color(0xFF718094).copy(alpha = 0.34f),
        radius = cupWidth * 0.27f,
        center = Offset(leftX + cupWidth / 2f, cupY),
    )
    drawCircle(
        color = Color(0xFF718094).copy(alpha = 0.34f),
        radius = cupWidth * 0.27f,
        center = Offset(rightX + cupWidth / 2f, cupY),
    )
    drawCircle(
        color = Color(0xFF9FE6D7),
        radius = 3.5f,
        center = Offset(center, size.height * 0.35f),
    )
}
