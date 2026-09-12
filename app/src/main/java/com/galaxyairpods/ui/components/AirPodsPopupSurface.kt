package com.galaxyairpods.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.galaxyairpods.domain.model.AirPodsWearState
import com.galaxyairpods.domain.model.PopupPhase
import com.galaxyairpods.domain.model.PopupUiState
import com.galaxyairpods.domain.motion.MotionLabSettings
import com.galaxyairpods.domain.motion.ProductMotion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AirPodsPopupSurface(
    popup: PopupUiState,
    settings: MotionLabSettings,
    popupDurationSeconds: Int,
    reducedMotion: Boolean,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onBatteryVisible: () -> Unit,
    onIdle: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cardY = remember { Animatable(settings.cardInitialY) }
    val cardScale = remember { Animatable(settings.cardInitialScale) }
    val cardAlpha = remember { Animatable(0f) }
    val scrimAlpha = remember { Animatable(0f) }
    val productY = remember { Animatable(9f) }
    val productScale = remember { Animatable(0.955f) }
    val productAlpha = remember { Animatable(0f) }
    val dragOffset = remember { Animatable(0f) }
    val productClock = remember(popup.animationId) { Animatable(0f) }
    var batteriesVisible by remember(popup.animationId) { mutableStateOf(false) }
    val exiting = popup.phase == PopupPhase.EXITING
    val caseOpenForMotion = popup.deviceState.caseOpen == true
    val leftOutForMotion = popup.leftRemoved || popup.deviceState.leftInCase == false
    val rightOutForMotion = popup.rightRemoved || popup.deviceState.rightInCase == false

    // Case / buds / docking all share this clock. Battery or sensor updates
    // change the text, never the decorative introduction or its object poses.
    LaunchedEffect(popup.animationId, exiting, reducedMotion) {
        if (exiting) {
            // Hold the last product frame while dismissing the card.
            // Resetting the product here made the case fly back through buds.
            launch { cardAlpha.animateTo(0f, tween(if (reducedMotion) 100 else 200)) }
            launch { cardScale.animateTo(0.97f, tween(200)) }
            launch { scrimAlpha.animateTo(0f, tween(200)) }
            delay(if (reducedMotion) 100L else 200L)
            onHide()
            return@LaunchedEffect
        }
        val speed = settings.playbackSpeed.coerceAtLeast(0.1f)
        val animateCaseOpen = caseOpenForMotion && !reducedMotion
        productClock.snapTo(if (animateCaseOpen) 0f else ProductMotion.DurationMs.toFloat())
        batteriesVisible = !animateCaseOpen
        cardY.snapTo(if (reducedMotion) 0f else settings.cardInitialY)
        cardScale.snapTo(if (reducedMotion) 1f else settings.cardInitialScale)
        cardAlpha.snapTo(0f)
        scrimAlpha.snapTo(0f)
        productY.snapTo(if (reducedMotion) 0f else 8f)
        productScale.snapTo(1f)
        productAlpha.snapTo(0f)
        dragOffset.snapTo(0f)
        launch { cardY.animateTo(0f, tween(if (reducedMotion) 120 else 260)) }
        launch { cardScale.animateTo(1f, tween(if (reducedMotion) 120 else 260)) }
        launch { cardAlpha.animateTo(1f, tween(120)) }
        launch { scrimAlpha.animateTo(settings.scrimAlpha, tween(160)) }
        launch { productAlpha.animateTo(1f, tween(120)) }
        launch { productY.animateTo(0f, tween(200)) }
        launch {
            if (animateCaseOpen) delay((ProductMotion.DockStartMs / speed).toLong())
            batteriesVisible = true
            onBatteryVisible()
        }
        if (animateCaseOpen) {
            productClock.animateTo(ProductMotion.DurationMs.toFloat(),
                tween((ProductMotion.DurationMs / speed).toInt(), easing = LinearEasing))
        } else if (reducedMotion) {
            delay(120)
        }
        onIdle()
    }

    LaunchedEffect(popup.eventId, popup.phase, popupDurationSeconds) {
        if (popup.phase == PopupPhase.IDLE_VISIBLE ||
            popup.phase == PopupPhase.CONNECTED
        ) {
            delay((popupDurationSeconds.coerceIn(2, 15) * 1_000L / settings.playbackSpeed).toLong())
            onDismiss()
        }
    }

    val scrim by animateFloatAsState(scrimAlpha.value, label = "popup-scrim")
    val tracker = remember { VelocityTracker() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrim)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
                .heightIn(max = 540.dp)
                .verticalScroll(rememberScrollState())
                .graphicsLayer {
                    alpha = cardAlpha.value
                    scaleX = cardScale.value
                    scaleY = cardScale.value
                    translationY = (cardY.value * size.height) + dragOffset.value
                }
                .pointerInput(popup.eventId) {
                    detectVerticalDragGestures(
                        onDragStart = {
                            tracker.resetTracking()
                            onDraggingChanged(true)
                        },
                        onVerticalDrag = { change, dragAmount ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            scope.launch {
                                dragOffset.snapTo((dragOffset.value + dragAmount).coerceAtLeast(-8f))
                            }
                            change.consume()
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            onDraggingChanged(false)
                            if (dragOffset.value > 150f || velocity > 900f) {
                                onDismiss()
                            } else {
                                scope.launch {
                                    dragOffset.animateTo(
                                        0f,
                                        spring(dampingRatio = 0.88f, stiffness = Spring.StiffnessMediumLow),
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            onDraggingChanged(false)
                            scope.launch { dragOffset.animateTo(0f) }
                        },
                    )
                },
            shape = RoundedCornerShape(28.dp),
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = popup.deviceState.model.label,
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
                        ) {
                            Text(
                                text = popup.deviceState.connectionLabel,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "팝업 닫기")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = productAlpha.value
                            scaleX = productScale.value
                            scaleY = productScale.value
                            translationY = with(density) { productY.value.dp.toPx() }
                        },
                ) {
                    ProductRenderer(
                        state = popup.deviceState,
                        artworkHeight = 224.dp,
                        motionPose = ProductMotion.frame(
                            elapsedMs = productClock.value,
                            caseOpen = caseOpenForMotion,
                            leftOutOfCase = leftOutForMotion,
                            rightOutOfCase = rightOutForMotion,
                        ),
                        showCase = true,
                        reducedMotion = reducedMotion,
                    )
                }

                BatteryGrid(
                    state = popup.deviceState,
                    reveal = batteriesVisible,
                    staggerMs = settings.batteryStaggerMs,
                    reducedMotion = reducedMotion,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "착용 상태 · ${wearLabel(popup.deviceState.wearState)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = statusText(popup),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "마지막 업데이트 · ${formatAge(popup.deviceState.batteryCapturedAt ?: popup.deviceState.lastSeenAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${popup.deviceState.batterySourceLabel()} · ${popup.deviceState.confidence.label}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun statusText(popup: PopupUiState): String = when (popup.phase) {
    PopupPhase.ENTERING,
    PopupPhase.DETECTED,
    PopupPhase.SHOWING_DEVICE,
    -> "상태를 확인하는 중…"
    PopupPhase.SHOWING_BATTERY,
    PopupPhase.UPDATED,
    PopupPhase.IDLE_VISIBLE,
    PopupPhase.DRAGGING,
    -> when {
        !popup.deviceState.hasAnyBattery -> "배터리 상태 확인 중"
        popup.deviceState.confidence == com.galaxyairpods.domain.model.DataConfidence.STALE ->
            "마지막 배터리 정보가 오래됨"
        else -> "배터리 상태가 최신입니다"
    }
    PopupPhase.CONNECTED -> popup.deviceState.connectionLabel
    PopupPhase.EXITING -> ""
    PopupPhase.HIDDEN -> ""
}

private fun wearLabel(state: AirPodsWearState): String = when (state) {
    AirPodsWearState.LEFT_IN_EAR -> "왼쪽 착용"
    AirPodsWearState.RIGHT_IN_EAR -> "오른쪽 착용"
    AirPodsWearState.BOTH_IN_EAR -> "양쪽 착용"
    AirPodsWearState.PARTIAL_IN_EAR -> "부분 착용"
    AirPodsWearState.NONE_IN_EAR -> "미착용"
    AirPodsWearState.IN_CASE -> "케이스 안"
    AirPodsWearState.CONFLICT -> "확인 충돌"
    AirPodsWearState.UNKNOWN -> "확인 중"
}

private fun formatAge(timestamp: Long?): String {
    if (timestamp == null) return "확인된 적 없음"
    val seconds = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 1 -> "방금"
        seconds < 60 -> "${seconds}초 전"
        seconds < 3_600 -> "${seconds / 60}분 전"
        else -> "${seconds / 3_600}시간 전"
    }
}

private fun com.galaxyairpods.domain.model.AirPodsState.batterySourceLabel(): String = when (batterySource) {
    "AAP_CLASSIC_EXACT" -> "AAP · 정밀 배터리"
    "BLE_PUBLIC_COARSE" -> "BLE · 공개 광고"
    "LEGACY_COARSE" -> "Legacy · 낮은 정밀도"
    else -> "배터리 확인 중"
}
