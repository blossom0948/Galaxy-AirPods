package com.galaxyairpods.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.isMax
import com.galaxyairpods.domain.model.isPro

@Composable
fun ProductRenderer(
    state: AirPodsState,
    modifier: Modifier = Modifier,
    artworkHeight: Dp = 188.dp,
    openProgress: Float = if (state.caseOpen == true) 1f else 0f,
    leftLift: Float = 0f,
    rightLift: Float = 0f,
) {
    val density = LocalDensity.current
    val renderedOpenProgress by animateFloatAsState(
        targetValue = openProgress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "airpods-case-open",
    )
    val leftTargetLift = leftLift + if (state.leftInCase == false && leftLift == 0f) {
        with(density) { -22.dp.toPx() }
    } else {
        0f
    }
    val rightTargetLift = rightLift + if (state.rightInCase == false && rightLift == 0f) {
        with(density) { -22.dp.toPx() }
    } else {
        0f
    }
    val renderedLeftLift by animateFloatAsState(
        targetValue = leftTargetLift,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 360f),
        label = "airpods-left-lift",
    )
    val renderedRightLift by animateFloatAsState(
        targetValue = rightTargetLift,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 360f),
        label = "airpods-right-lift",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(artworkHeight)
            .semantics { contentDescription = state.model.label + " 제품" },
    ) {
        Canvas(Modifier.fillMaxWidth().height(artworkHeight)) {
            if (state.model.isMax) {
                drawMaxHeadphones()
            } else {
                drawEarbudCase(
                    model = state.model,
                    openProgress = renderedOpenProgress,
                    leftLift = renderedLeftLift,
                    rightLift = renderedRightLift,
                )
            }
        }
    }
}

/** Geometry is intentionally model-specific so every selector choice has a
 * recognisable silhouette instead of one generic placeholder drawing. */
private data class EarbudArtworkGeometry(
    val caseWidth: Float,
    val caseHeight: Float,
    val caseCorner: Float,
    val lidHeightFactor: Float,
    val headRadius: Float,
    val stemLength: Float,
    val stemWidth: Float,
    val proTip: Boolean,
)

private fun AirPodsModel.earbudArtworkGeometry(): EarbudArtworkGeometry = when {
    isPro -> EarbudArtworkGeometry(
        caseWidth = 0.54f,
        caseHeight = 0.35f,
        caseCorner = 30f,
        lidHeightFactor = 0.74f,
        headRadius = 18f,
        stemLength = 25f,
        stemWidth = 11f,
        proTip = true,
    )
    this == AirPodsModel.AIRPODS_GEN3 ||
        this == AirPodsModel.AIRPODS_GEN4 ||
        this == AirPodsModel.AIRPODS_GEN4_ANC -> EarbudArtworkGeometry(
        caseWidth = 0.51f,
        caseHeight = 0.34f,
        caseCorner = 29f,
        lidHeightFactor = 0.72f,
        headRadius = 20f,
        stemLength = 28f,
        stemWidth = 11f,
        proTip = false,
    )
    else -> EarbudArtworkGeometry(
        caseWidth = 0.48f,
        caseHeight = 0.37f,
        caseCorner = 25f,
        lidHeightFactor = 0.78f,
        headRadius = 18f,
        stemLength = 40f,
        stemWidth = 10f,
        proTip = false,
    )
}

private fun DrawScope.drawEarbudCase(
    model: AirPodsModel,
    openProgress: Float,
    leftLift: Float,
    rightLift: Float,
) {
    val geometry = model.earbudArtworkGeometry()
    val center = size.width / 2f
    val bodyWidth = size.width * geometry.caseWidth
    val bodyHeight = size.height * geometry.caseHeight
    val bodyLeft = center - bodyWidth / 2f
    val bodyTop = size.height * 0.53f
    val hinge = Offset(center, bodyTop + 5f)
    val lidHeight = bodyHeight * geometry.lidHeightFactor

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
        cornerRadius = CornerRadius(geometry.caseCorner, geometry.caseCorner),
    )
    drawRoundRect(
        color = Color(0xFF8D99AA).copy(alpha = 0.12f + (0.08f * openProgress)),
        topLeft = Offset(bodyLeft + bodyWidth * 0.11f, bodyTop + bodyHeight * 0.08f),
        size = Size(bodyWidth * 0.78f, bodyHeight * 0.14f),
        cornerRadius = CornerRadius(8f, 8f),
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
    drawLine(
        color = Color(0xFF7E8B9C).copy(alpha = 0.24f),
        start = Offset(bodyLeft + bodyWidth * 0.20f, bodyTop + bodyHeight * 0.13f),
        end = Offset(bodyLeft + bodyWidth * 0.80f, bodyTop + bodyHeight * 0.13f),
        strokeWidth = 2.5f,
    )

    // The lid uses a real hinge pivot plus a small lift instead of rotating a
    // flat bitmap. This keeps the case proportions stable while it opens.
    rotate(degrees = -54f * openProgress, pivot = hinge) {
        translate(top = -8f * openProgress) {
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(Color(0xFFFFFFFF), Color(0xFFD5DDE8)),
                    start = Offset(bodyLeft, bodyTop - bodyHeight * 0.72f),
                    end = Offset(bodyLeft + bodyWidth, bodyTop),
                ),
                topLeft = Offset(bodyLeft, bodyTop - lidHeight * 0.72f),
                size = Size(bodyWidth, lidHeight * 0.76f),
                cornerRadius = CornerRadius(geometry.caseCorner * 0.90f, geometry.caseCorner * 0.90f),
            )
            drawRoundRect(
                color = Color(0xFF8391A3).copy(alpha = 0.20f),
                topLeft = Offset(bodyLeft + bodyWidth * 0.15f, bodyTop - lidHeight * 0.57f),
                size = Size(bodyWidth * 0.70f, 10f),
                cornerRadius = CornerRadius(8f, 8f),
            )
        }
    }

    val leftOutProgress = (-leftLift / (size.height * 0.14f)).coerceIn(0f, 1f)
    val rightOutProgress = (-rightLift / (size.height * 0.14f)).coerceIn(0f, 1f)

    drawEarbud(
        center = Offset(
            center - bodyWidth * 0.27f - (size.width * 0.035f * leftOutProgress),
            bodyTop + bodyHeight * 0.10f + leftLift,
        ),
        headRadius = geometry.headRadius,
        stemLength = geometry.stemLength,
        stemWidth = geometry.stemWidth,
        proTip = geometry.proTip,
        tilt = -8f,
    )
    drawEarbud(
        center = Offset(
            center + bodyWidth * 0.27f + (size.width * 0.035f * rightOutProgress),
            bodyTop + bodyHeight * 0.08f + rightLift,
        ),
        headRadius = geometry.headRadius,
        stemLength = geometry.stemLength,
        stemWidth = geometry.stemWidth,
        proTip = geometry.proTip,
        tilt = 8f,
    )
}

private fun DrawScope.drawEarbud(
    center: Offset,
    headRadius: Float,
    stemLength: Float,
    stemWidth: Float,
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
            drawCircle(
                color = Color(0xFF566477).copy(alpha = 0.66f),
                radius = 2.4f,
                center = Offset(center.x + 7f, center.y + 6f),
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
            topLeft = Offset(center.x - stemWidth / 2f, center.y + headRadius * 0.55f),
            size = Size(stemWidth, stemLength),
            cornerRadius = CornerRadius(stemWidth / 2f, stemWidth / 2f),
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
