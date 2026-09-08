package com.galaxyairpods.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.isMax
import com.galaxyairpods.domain.model.isPro
import kotlin.math.min

/**
 * Renders the product as independent vector layers instead of scaling one
 * flat bitmap. The geometry is original artwork: it is not an Apple system
 * screenshot or a third-party asset. Keeping the layers in one Canvas makes
 * the popup light enough for a TYPE_APPLICATION_OVERLAY window while still
 * giving the lid, body, left bud and right bud independent motion channels.
 */
@Composable
fun ProductRenderer(
    state: AirPodsState,
    modifier: Modifier = Modifier,
    artworkHeight: Dp = 188.dp,
    openProgress: Float = if (state.caseOpen == true) 1f else 0f,
    leftLift: Float = 0f,
    rightLift: Float = 0f,
    showCase: Boolean = true,
) {
    val density = LocalDensity.current
    val renderedOpenProgress by animateFloatAsState(
        targetValue = openProgress.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.86f, stiffness = 420f),
        label = "airpods-case-open",
    )
    val outOfCaseLift = with(density) { -52.dp.toPx() }
    val leftTargetLift = minOf(
        leftLift,
        if (state.leftInCase == false) outOfCaseLift else 0f,
    )
    val rightTargetLift = minOf(
        rightLift,
        if (state.rightInCase == false) outOfCaseLift else 0f,
    )
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
                drawAirPodsArtwork(
                    model = state.model,
                    openProgress = renderedOpenProgress,
                    leftLift = renderedLeftLift,
                    rightLift = renderedRightLift,
                    caseCharging = state.caseCharging == true,
                    showCase = showCase,
                )
            }
        }
    }
}

private const val DESIGN_WIDTH = 360f
private const val DESIGN_HEIGHT = 190f

private data class AirPodsArtworkGeometry(
    val caseWidth: Float,
    val bodyHeight: Float,
    val lidHeight: Float,
    val cornerRadius: Float,
    val headWidth: Float,
    val headHeight: Float,
    val stemLength: Float,
    val stemWidth: Float,
    val proTip: Boolean,
)

/** Different generations keep their recognisable case and earbud silhouette. */
private fun AirPodsModel.artworkGeometry(): AirPodsArtworkGeometry = when {
    isPro -> AirPodsArtworkGeometry(
        caseWidth = 218f,
        bodyHeight = 68f,
        lidHeight = 48f,
        cornerRadius = 23f,
        headWidth = 27f,
        headHeight = 29f,
        stemLength = 31f,
        stemWidth = 10f,
        proTip = true,
    )
    this == AirPodsModel.AIRPODS_GEN3 ||
        this == AirPodsModel.AIRPODS_GEN4 ||
        this == AirPodsModel.AIRPODS_GEN4_ANC -> AirPodsArtworkGeometry(
        caseWidth = 206f,
        bodyHeight = 65f,
        lidHeight = 45f,
        cornerRadius = 23f,
        headWidth = 27f,
        headHeight = 25f,
        stemLength = 39f,
        stemWidth = 9f,
        proTip = false,
    )
    else -> AirPodsArtworkGeometry(
        caseWidth = 196f,
        bodyHeight = 69f,
        lidHeight = 47f,
        cornerRadius = 21f,
        headWidth = 25f,
        headHeight = 24f,
        stemLength = 49f,
        stemWidth = 9f,
        proTip = false,
    )
}

private fun DrawScope.drawAirPodsArtwork(
    model: AirPodsModel,
    openProgress: Float,
    leftLift: Float,
    rightLift: Float,
    caseCharging: Boolean,
    showCase: Boolean,
) {
    val geometry = model.artworkGeometry()
    val unit = min(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
    val originX = (size.width - DESIGN_WIDTH * unit) / 2f
    val originY = (size.height - DESIGN_HEIGHT * unit) / 2f
    val centerX = originX + DESIGN_WIDTH * unit / 2f
    val bodyWidth = geometry.caseWidth * unit
    val bodyHeight = geometry.bodyHeight * unit
    val bodyLeft = centerX - bodyWidth / 2f
    val bodyTop = originY + 106f * unit
    val hinge = Offset(centerX, bodyTop + 1.5f * unit)
    val leftInCase = leftLift > -8f * unit
    val rightInCase = rightLift > -8f * unit

    if (showCase) {
        drawOval(
            color = Color(0xFF7B8798).copy(alpha = 0.20f),
            topLeft = Offset(bodyLeft - 18f * unit, bodyTop + bodyHeight - 1f * unit),
            size = Size(bodyWidth + 36f * unit, 13f * unit),
        )
        drawOval(
            color = Color(0xFF5F6A78).copy(alpha = 0.12f),
            topLeft = Offset(bodyLeft + 16f * unit, bodyTop + bodyHeight + 1f * unit),
            size = Size(bodyWidth - 32f * unit, 7f * unit),
        )
    }

    // Buds that are still in the case are drawn behind the front shell. This
    // masks their lower stems naturally instead of clipping a bitmap.
    if (showCase && leftInCase) {
        drawEarbud(
            center = Offset(centerX - bodyWidth * 0.215f, bodyTop + 8f * unit),
            geometry = geometry,
            unit = unit,
            tilt = -7f,
            alpha = 0.96f,
        )
    }
    if (showCase && rightInCase) {
        drawEarbud(
            center = Offset(centerX + bodyWidth * 0.215f, bodyTop + 8f * unit),
            geometry = geometry,
            unit = unit,
            tilt = 7f,
            alpha = 0.96f,
        )
    }

    if (showCase) {
        drawCaseBody(
            left = bodyLeft,
            top = bodyTop,
            width = bodyWidth,
            height = bodyHeight,
            corner = geometry.cornerRadius * unit,
            openProgress = openProgress,
        )
        drawCaseInterior(
            left = bodyLeft,
            top = bodyTop,
            width = bodyWidth,
            openProgress = openProgress,
            unit = unit,
        )
        drawCaseLid(
            left = bodyLeft,
            top = bodyTop,
            width = bodyWidth,
            height = geometry.lidHeight * unit,
            corner = geometry.cornerRadius * 0.94f * unit,
            hinge = hinge,
            openProgress = openProgress,
            unit = unit,
        )
        drawChargingLight(
            center = Offset(centerX, bodyTop + bodyHeight * 0.54f),
            unit = unit,
            charging = caseCharging,
        )
    }

    // Removed buds are rendered above the case. Their horizontal separation
    // increases as they rise, which reads as a real two-object lift rather
    // than a single PNG being translated as one unit.
    if (!leftInCase) {
        val progress = (-leftLift / unit / 52f).coerceIn(0f, 1f)
        drawEarbud(
            center = Offset(
                centerX - bodyWidth * 0.215f - 17f * unit * progress,
                bodyTop + 8f * unit + leftLift,
            ),
            geometry = geometry,
            unit = unit,
            tilt = -10f,
            alpha = 1f,
        )
    }
    if (!rightInCase) {
        val progress = (-rightLift / unit / 52f).coerceIn(0f, 1f)
        drawEarbud(
            center = Offset(
                centerX + bodyWidth * 0.215f + 17f * unit * progress,
                bodyTop + 8f * unit + rightLift,
            ),
            geometry = geometry,
            unit = unit,
            tilt = 10f,
            alpha = 1f,
        )
    }
}

private fun DrawScope.drawCaseBody(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: Float,
    openProgress: Float,
) {
    drawRoundRect(
        brush = Brush.linearGradient(
            colors = listOf(
                Color(0xFFFFFFFF),
                Color(0xFFF5F7FA),
                Color(0xFFD6DEE8),
                Color(0xFFB3BFCE),
            ),
            start = Offset(left, top),
            end = Offset(left + width, top + height),
        ),
        topLeft = Offset(left, top),
        size = Size(width, height),
        cornerRadius = CornerRadius(corner, corner),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.58f),
        topLeft = Offset(left + width * 0.065f, top + height * 0.08f),
        size = Size(width * 0.87f, height * 0.28f),
        cornerRadius = CornerRadius(corner * 0.55f, corner * 0.55f),
    )
    drawRoundRect(
        color = Color(0xFF738196).copy(alpha = 0.10f + openProgress * 0.08f),
        topLeft = Offset(left + width * 0.12f, top + height * 0.16f),
        size = Size(width * 0.76f, height * 0.18f),
        cornerRadius = CornerRadius(corner * 0.45f, corner * 0.45f),
    )
    drawLine(
        color = Color(0xFF8793A2).copy(alpha = 0.28f),
        start = Offset(left + width * 0.15f, top + height * 0.115f),
        end = Offset(left + width * 0.85f, top + height * 0.115f),
        strokeWidth = maxOf(1.2f, height * 0.018f),
    )
}

private fun DrawScope.drawCaseInterior(
    left: Float,
    top: Float,
    width: Float,
    openProgress: Float,
    unit: Float,
) {
    val cavityAlpha = (0.16f + openProgress * 0.64f).coerceIn(0f, 0.82f)
    drawRoundRect(
        brush = Brush.verticalGradient(
            listOf(
                Color(0xFF5C6A7C).copy(alpha = cavityAlpha),
                Color(0xFF1F2A39).copy(alpha = cavityAlpha * 0.92f),
            ),
        ),
        topLeft = Offset(left + width * 0.105f, top - 1f * unit),
        size = Size(width * 0.79f, 19f * unit),
        cornerRadius = CornerRadius(9f * unit, 9f * unit),
    )
    drawOval(
        color = Color(0xFF1B2634).copy(alpha = cavityAlpha * 0.80f),
        topLeft = Offset(left + width * 0.22f, top + 3f * unit),
        size = Size(width * 0.20f, 10f * unit),
    )
    drawOval(
        color = Color(0xFF1B2634).copy(alpha = cavityAlpha * 0.80f),
        topLeft = Offset(left + width * 0.58f, top + 3f * unit),
        size = Size(width * 0.20f, 10f * unit),
    )
}

private fun DrawScope.drawCaseLid(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    corner: Float,
    hinge: Offset,
    openProgress: Float,
    unit: Float,
) {
    val lidTop = top - height + 2f * unit
    rotate(degrees = -54f * openProgress, pivot = hinge) {
        translate(top = -5f * unit * openProgress) {
            scale(
                scaleX = 1f,
                scaleY = 1f - 0.22f * openProgress,
                pivot = hinge,
            ) {
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFFFFF), Color(0xFFF0F4F8), Color(0xFFCAD4E0)),
                        start = Offset(left, lidTop),
                        end = Offset(left + width, top),
                    ),
                    topLeft = Offset(left, lidTop),
                    size = Size(width, height),
                    cornerRadius = CornerRadius(corner, corner),
                )
                drawRoundRect(
                    color = Color.White.copy(alpha = 0.56f),
                    topLeft = Offset(left + width * 0.075f, lidTop + height * 0.16f),
                    size = Size(width * 0.85f, height * 0.28f),
                    cornerRadius = CornerRadius(corner * 0.45f, corner * 0.45f),
                )
                drawLine(
                    color = Color(0xFF8D99A9).copy(alpha = 0.22f),
                    start = Offset(left + width * 0.16f, lidTop + height * 0.58f),
                    end = Offset(left + width * 0.84f, lidTop + height * 0.58f),
                    strokeWidth = maxOf(1.2f, height * 0.022f),
                )
            }
        }
    }
}

private fun DrawScope.drawChargingLight(
    center: Offset,
    unit: Float,
    charging: Boolean,
) {
    drawCircle(
        color = if (charging) Color(0xFF1CCB83) else Color(0xFF718096),
        radius = 2.4f * unit,
        center = center,
        alpha = if (charging) 0.96f else 0.36f,
    )
    if (charging) {
        drawCircle(
            color = Color(0xFF58E9B0).copy(alpha = 0.24f),
            radius = 5.2f * unit,
            center = center,
        )
    }
}

private fun DrawScope.drawEarbud(
    center: Offset,
    geometry: AirPodsArtworkGeometry,
    unit: Float,
    tilt: Float,
    alpha: Float,
) {
    rotate(degrees = tilt, pivot = center) {
        val headWidth = geometry.headWidth * unit
        val headHeight = geometry.headHeight * unit
        val headLeft = center.x - headWidth / 2f
        val headTop = center.y - headHeight / 2f
        val stemTop = center.y + headHeight * 0.27f
        val stemHeight = geometry.stemLength * unit
        val stemWidth = geometry.stemWidth * unit

        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = alpha),
                    Color(0xFFF0F4F8).copy(alpha = alpha),
                    Color(0xFFB6C2D0).copy(alpha = alpha),
                ),
                center = Offset(center.x - headWidth * 0.16f, center.y - headHeight * 0.16f),
                radius = headWidth * 0.75f,
            ),
            topLeft = Offset(headLeft, headTop),
            size = Size(headWidth, headHeight),
        )
        if (geometry.proTip) {
            drawOval(
                color = Color(0xFF657487).copy(alpha = alpha * 0.92f),
                topLeft = Offset(center.x - headWidth * 0.27f, center.y - headHeight * 0.03f),
                size = Size(headWidth * 0.54f, headHeight * 0.28f),
            )
            drawOval(
                color = Color(0xFF303C4B).copy(alpha = alpha * 0.78f),
                topLeft = Offset(center.x - headWidth * 0.18f, center.y + headHeight * 0.01f),
                size = Size(headWidth * 0.36f, headHeight * 0.13f),
            )
            drawCircle(
                color = Color(0xFF536174).copy(alpha = alpha * 0.72f),
                radius = 1.8f * unit,
                center = Offset(center.x + headWidth * 0.26f, center.y + headHeight * 0.22f),
            )
        } else {
            drawOval(
                color = Color(0xFF6C7A8D).copy(alpha = alpha * 0.72f),
                topLeft = Offset(center.x - headWidth * 0.18f, center.y - headHeight * 0.03f),
                size = Size(headWidth * 0.36f, headHeight * 0.20f),
            )
            drawCircle(
                color = Color(0xFF526174).copy(alpha = alpha * 0.58f),
                radius = 1.4f * unit,
                center = Offset(center.x + headWidth * 0.22f, center.y + headHeight * 0.18f),
            )
        }
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(
                    Color(0xFFF7FAFC).copy(alpha = alpha),
                    Color(0xFFD5DEE9).copy(alpha = alpha),
                    Color(0xFFA9B7C7).copy(alpha = alpha),
                ),
            ),
            topLeft = Offset(center.x - stemWidth / 2f, stemTop),
            size = Size(stemWidth, stemHeight),
            cornerRadius = CornerRadius(stemWidth / 2f, stemWidth / 2f),
        )
        drawCircle(
            color = Color(0xFF455366).copy(alpha = alpha * 0.72f),
            radius = 1.7f * unit,
            center = Offset(center.x, stemTop + stemHeight - 5f * unit),
        )
    }
}

private fun DrawScope.drawMaxHeadphones() {
    val unit = min(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
    val originX = (size.width - DESIGN_WIDTH * unit) / 2f
    val originY = (size.height - DESIGN_HEIGHT * unit) / 2f
    val center = originX + DESIGN_WIDTH * unit / 2f
    val cupWidth = 65f * unit
    val cupHeight = 75f * unit
    val cupY = originY + 115f * unit
    val gap = 42f * unit
    val leftX = center - gap - cupWidth / 2f
    val rightX = center + gap - cupWidth / 2f
    val bandRect = Rect(
        left = center - 88f * unit,
        top = originY + 20f * unit,
        right = center + 88f * unit,
        bottom = originY + 148f * unit,
    )

    drawArc(
        color = Color(0xFFAFBBC9),
        startAngle = 195f,
        sweepAngle = 150f,
        useCenter = false,
        topLeft = bandRect.topLeft,
        size = bandRect.size,
        style = Stroke(width = 10f * unit),
    )
    drawOval(
        color = Color.Black.copy(alpha = 0.18f),
        topLeft = Offset(leftX - 8f * unit, cupY + cupHeight * 0.35f),
        size = Size(cupWidth * 2f + gap * 2f + 16f * unit, 11f * unit),
    )
    drawLine(
        color = Color(0xFF8997AA),
        start = Offset(leftX + cupWidth * 0.84f, originY + 74f * unit),
        end = Offset(leftX + cupWidth * 0.84f, cupY - cupHeight * 0.32f),
        strokeWidth = 7f * unit,
    )
    drawLine(
        color = Color(0xFF8997AA),
        start = Offset(rightX + cupWidth * 0.16f, originY + 74f * unit),
        end = Offset(rightX + cupWidth * 0.16f, cupY - cupHeight * 0.32f),
        strokeWidth = 7f * unit,
    )
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFE9EEF5), Color(0xFF9BA8B9))),
        topLeft = Offset(leftX, cupY - cupHeight / 2f),
        size = Size(cupWidth, cupHeight),
        cornerRadius = CornerRadius(24f * unit, 24f * unit),
    )
    drawRoundRect(
        brush = Brush.linearGradient(listOf(Color(0xFFE9EEF5), Color(0xFF9BA8B9))),
        topLeft = Offset(rightX, cupY - cupHeight / 2f),
        size = Size(cupWidth, cupHeight),
        cornerRadius = CornerRadius(24f * unit, 24f * unit),
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
}
