package com.galaxyairpods.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsModel
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.BatterySlot
import com.galaxyairpods.domain.model.isMax
import com.galaxyairpods.domain.model.isPro
import com.galaxyairpods.domain.motion.ProductMotion
import com.galaxyairpods.domain.motion.ProductPose
import kotlin.math.min

@Composable
fun ProductRenderer(
    state: AirPodsState,
    modifier: Modifier = Modifier,
    artworkHeight: Dp = 188.dp,
    openProgress: Float = if (state.caseOpen == true) 1f else 0f,
    leftLift: Float? = null,
    rightLift: Float? = null,
    showCase: Boolean = true,
    reducedMotion: Boolean = false,
    motionPose: ProductPose? = null,
) {
    val density = LocalDensity.current
    val maxLift = with(density) { 52.dp.toPx() }
    val open by animateFloatAsState(openProgress.coerceIn(0f, 1f),
        tween(if (reducedMotion) 120 else 420, easing = FastOutSlowInEasing), label = "lid")
    val left by animateFloatAsState(
        ((leftLift?.div(-maxLift)) ?: if (state.leftInCase == false) 1f else 0f).coerceIn(0f, 1f),
        tween(if (reducedMotion) 120 else 300), label = "left")
    val right by animateFloatAsState(
        ((rightLift?.div(-maxLift)) ?: if (state.rightInCase == false) 1f else 0f).coerceIn(0f, 1f),
        tween(if (reducedMotion) 120 else 300), label = "right")
    // A popup supplies the exact frame from its single clock. Do not animate
    // those values again: that used to make geometry lag behind phase changes.
    val pose = motionPose ?: ProductPose(open, left, right)
    val resources = LocalContext.current.resources
    val scene = if (state.model.artworkTechnology() == AirPodsArtworkTechnology.BITMAP_LAYERS) {
        remember(resources) { ProductArtworkScene(resources) }
    } else null
    Canvas(modifier.fillMaxWidth().height(artworkHeight)
        .semantics { contentDescription = state.model.label + " 제품" }) {
        if (scene != null) {
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            canvas.scale(density.density, density.density)
            scene.draw(canvas, size.width / density.density, size.height / density.density,
                pose, showCase, state.chargingFor(BatterySlot.CASE) == true)
            canvas.restore()
        } else if (state.model.isMax) {
            drawMaxHeadphones()
        } else {
            drawVectorProductScene(state, pose, showCase)
        }
    }
}

private fun DrawScope.drawVectorProductScene(state: AirPodsState, pose: ProductPose, showCase: Boolean) {
    val stageUnit = min(1f, min(size.width / 280.dp.toPx(), size.height / 224.dp.toPx()))
    val unit = 0.67f * density * stageUnit
    val geometry = state.model.artworkGeometry()
    val width = geometry.caseWidth * unit
    val bodyHeight = geometry.bodyHeight * unit
    val dock = pose.dock
    val departure = com.galaxyairpods.domain.motion.ProductMotion.smooth((dock / 0.45f).coerceIn(0f, 1f))
    val arrival = com.galaxyairpods.domain.motion.ProductMotion.smooth(((dock - 0.60f) / 0.4f).coerceIn(0f, 1f))
    val gap = 8.dp.toPx()
    val column = (size.width - 2f * gap) / 3f
    val inSlot = dock >= 0.5f
    val scale = if (inSlot) 0.5f else 1f
    val cx = if (inSlot) size.width - column / 2f else size.width / 2f
    val bottom = size.height - 16.dp.toPx() +
        (if (inSlot) (1f - arrival) * 14f else departure * 25f).dp.toPx()
    val top = bottom - bodyHeight * scale
    val caseAlpha = if (inSlot) arrival else 1f - departure
    // Fade the entire vector case as one group; each bud is drawn once.
    fun case(frontOnly: Boolean) {
        if (!showCase || caseAlpha <= 0f) return
        val c = drawContext.canvas.nativeCanvas
        c.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * caseAlpha).toInt())
        if (!frontOnly) {
            drawCaseLid(cx - width * scale / 2f, top, width * scale,
                geometry.lidHeight * unit * scale, geometry.cornerRadius * unit * scale,
                Offset(cx, top), pose.lid, unit * scale)
        }
        drawCaseBody(cx - width * scale / 2f, top, width * scale, bodyHeight * scale,
            geometry.cornerRadius * unit * scale, pose.lid)
        c.restore()
    }
    case(false)
    fun bud(side: Float, lift: Float, target: Float) {
        val startX = size.width / 2f + side * (34f + 16f * lift).dp.toPx()
        val startY = (size.height - 100.dp.toPx()) * (1f - lift) + 38.dp.toPx() * lift
        val budAlpha = if (lift > 0.001f || !showCase) 1f else {
            ProductMotion.smooth(((pose.lid - 0.45f) / 0.55f).coerceIn(0f, 1f))
        }
        drawEarbud(Offset(startX + (target - startX) * dock,
            startY + (size.height - 55.dp.toPx() - startY) * dock),
            geometry, unit, side * 7f, budAlpha)
    }
    bud(-1f, pose.leftLift, column / 2f)
    bud(1f, pose.rightLift, size.width / 2f)
    if (showCase && caseAlpha > 0f && dock < 0.5f && pose.lid > 0.001f) {
        val c = drawContext.canvas.nativeCanvas
        c.saveLayerAlpha(0f, 0f, size.width, size.height, (255 * caseAlpha).toInt())
        clipRect(
            left = cx - width * scale / 2f,
            top = top + bodyHeight * scale * 0.25f,
            right = cx + width * scale / 2f,
            bottom = top + bodyHeight * scale,
        ) {
            drawCaseBody(cx - width * scale / 2f, top, width * scale, bodyHeight * scale,
                geometry.cornerRadius * unit * scale, pose.lid)
        }
        c.restore()
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
