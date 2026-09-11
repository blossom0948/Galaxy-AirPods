package com.galaxyairpods.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.BatterySlot
import com.galaxyairpods.domain.model.DataConfidence
import com.galaxyairpods.domain.model.isMax
import kotlinx.coroutines.delay

@Composable
fun BatteryGrid(
    state: AirPodsState,
    modifier: Modifier = Modifier,
    reveal: Boolean = true,
    staggerMs: Long = 35L,
    reducedMotion: Boolean = false,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.model.isMax) {
            BatteryItem(
                slot = BatterySlot.LEFT,
                labelOverride = "헤드폰",
                battery = state.leftBattery,
                charging = state.chargingFor(BatterySlot.LEFT, now),
                chargingUnknown = state.chargingStatusUnknownFor(BatterySlot.LEFT, now),
                confidence = state.confidence,
                reveal = reveal,
                revealDelayMs = 0L,
                reducedMotion = reducedMotion,
                modifier = Modifier.weight(1f),
            )
        } else {
            BatteryItem(
                slot = BatterySlot.LEFT,
                battery = state.leftBattery,
                charging = state.chargingFor(BatterySlot.LEFT, now),
                chargingUnknown = state.chargingStatusUnknownFor(BatterySlot.LEFT, now),
                confidence = state.confidence,
                reveal = reveal,
                revealDelayMs = 0L,
                reducedMotion = reducedMotion,
                modifier = Modifier.weight(1f),
            )
            BatteryItem(
                slot = BatterySlot.RIGHT,
                battery = state.rightBattery,
                charging = state.chargingFor(BatterySlot.RIGHT, now),
                chargingUnknown = state.chargingStatusUnknownFor(BatterySlot.RIGHT, now),
                confidence = state.confidence,
                reveal = reveal,
                revealDelayMs = staggerMs,
                reducedMotion = reducedMotion,
                modifier = Modifier.weight(1f),
            )
            BatteryItem(
                slot = BatterySlot.CASE,
                battery = state.caseBattery,
                charging = state.chargingFor(BatterySlot.CASE, now),
                chargingUnknown = state.chargingStatusUnknownFor(BatterySlot.CASE, now),
                confidence = state.confidence,
                reveal = reveal,
                revealDelayMs = staggerMs * 2,
                reducedMotion = reducedMotion,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun BatteryItem(
    slot: BatterySlot,
    battery: Int?,
    charging: Boolean?,
    chargingUnknown: Boolean = false,
    confidence: DataConfidence,
    reveal: Boolean,
    revealDelayMs: Long,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
    labelOverride: String? = null,
) {
    var itemVisible by remember { mutableStateOf(false) }
    LaunchedEffect(reveal, revealDelayMs, reducedMotion) {
        if (reveal) {
            if (!reducedMotion) delay(revealDelayMs)
            itemVisible = true
        } else {
            itemVisible = false
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (itemVisible) 1f else 0f,
        animationSpec = if (reducedMotion) snap() else tween(160),
        label = "${slot.name}-alpha",
    )
    val progress by animateFloatAsState(
        targetValue = (battery ?: 0).coerceIn(0, 100) / 100f,
        animationSpec = if (reducedMotion) snap() else tween(520),
        label = "${slot.name}-ring",
    )
    val accent = when {
        battery == null -> MaterialTheme.colorScheme.onSurfaceVariant
        battery <= 20 -> Color(0xFFFFB4AB)
        else -> MaterialTheme.colorScheme.primary
    }

    Surface(
        modifier = modifier
            .semantics {
                contentDescription = (labelOverride ?: slot.label) + " 배터리 " +
                    (battery?.let { it.toString() + " 퍼센트" } ?: "확인 불가")
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BatteryRing(
                progress = battery?.let { progress },
                accent = accent,
                modifier = Modifier.size(40.dp),
            )
            Spacer(Modifier.height(5.dp))
            Text(labelOverride ?: slot.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            Crossfade(
                targetState = battery?.let { "$it%" } ?: "--",
                animationSpec = if (reducedMotion || battery == null) snap() else tween(210),
                label = "${slot.name}-value",
            ) { valueLabel ->
                Text(
                    text = valueLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                )
            }
            if (charging == true) {
                Text("충전 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            } else if (chargingUnknown && battery != null) {
                Text("충전 상태 확인 중", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            } else if (battery == null) {
                Text("데이터 없음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            }
        }
    }
}

@Composable
fun BatteryRing(
    progress: Float?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawArc(
            color = accent.copy(alpha = 0.18f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = 5.dp.toPx()),
        )
        // Unknown is intentionally not drawn as an empty 0% ring. A short
        // neutral dash keeps the state visibly different from a real 0%.
        if (progress == null) {
            drawArc(
                color = accent.copy(alpha = 0.55f),
                startAngle = -90f,
                sweepAngle = 72f,
                useCenter = false,
                style = Stroke(width = 5.dp.toPx()),
            )
        } else {
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = Stroke(width = 5.dp.toPx()),
            )
        }
    }
}
