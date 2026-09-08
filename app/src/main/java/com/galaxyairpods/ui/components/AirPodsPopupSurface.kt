package com.galaxyairpods.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.galaxyairpods.domain.model.PopupPhase
import com.galaxyairpods.domain.model.PopupUiState
import com.galaxyairpods.domain.motion.MotionLabSettings
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
    val openProgress = remember { Animatable(0f) }
    val dragOffset = remember { Animatable(0f) }

    // Phase markers such as SHOWING_BATTERY/IDLE_VISIBLE must not restart the
    // surface animation. Only a new semantic event re-targets the channels.
    val animationKey = if (popup.phase == PopupPhase.EXITING) popup.eventId else popup.animationId
    LaunchedEffect(animationKey, reducedMotion) {
        // Battery samples update content only. They must not restart the card
        // entrance animation or make the popup jump under the user's finger.
        if (popup.phase == PopupPhase.UPDATED ||
            popup.phase == PopupPhase.SHOWING_BATTERY ||
            popup.phase == PopupPhase.IDLE_VISIBLE ||
            popup.phase == PopupPhase.DRAGGING
        ) return@LaunchedEffect
        if (reducedMotion) {
            if (popup.phase == PopupPhase.EXITING) {
                cardAlpha.snapTo(0f)
                scrimAlpha.snapTo(0f)
                onHide()
            } else {
                cardY.snapTo(0f)
                cardScale.snapTo(1f)
                cardAlpha.snapTo(1f)
                scrimAlpha.snapTo(0.48f)
                productY.snapTo(0f)
                productScale.snapTo(1f)
                productAlpha.snapTo(1f)
                openProgress.snapTo(if (popup.deviceState.caseOpen == true) 1f else 0f)
                onBatteryVisible()
            }
            return@LaunchedEffect
        }

        if (popup.phase == PopupPhase.EXITING) {
            launch {
                cardY.animateTo(settings.exitY, spring(dampingRatio = 0.96f, stiffness = 520f))
            }
            launch {
                cardScale.animateTo(settings.exitScale, spring(dampingRatio = 0.98f, stiffness = 520f))
            }
            launch { cardAlpha.animateTo(0f, spring(dampingRatio = 1f, stiffness = 650f)) }
            launch { scrimAlpha.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f)) }
            delay((MotionLabExitMs / settings.playbackSpeed).toLong())
            onHide()
            return@LaunchedEffect
        }

        launch {
            cardY.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = settings.cardDamping,
                    stiffness = settings.cardStiffness,
                ),
            )
        }
        launch { cardScale.animateTo(1f, spring(settings.cardDamping, settings.cardStiffness)) }
        launch { cardAlpha.animateTo(1f, spring(dampingRatio = 1f, stiffness = 700f)) }
        launch { scrimAlpha.animateTo(0.48f, spring(dampingRatio = 1f, stiffness = 800f)) }

        launch {
            delay((settings.productDelayMs / settings.playbackSpeed).toLong())
            productAlpha.animateTo(1f, spring(dampingRatio = 1f, stiffness = 700f))
            productY.animateTo(0f, spring(settings.productDamping, settings.productStiffness))
            productScale.animateTo(1f, spring(settings.productDamping, settings.productStiffness))
        }
        launch {
            openProgress.animateTo(
                if (popup.deviceState.caseOpen == true) 1f else 0f,
                spring(dampingRatio = 0.94f, stiffness = 330f),
            )
        }
        launch {
            delay((settings.batteryDelayMs / settings.playbackSpeed).toLong())
            onBatteryVisible()
        }
        launch {
            delay((520L / settings.playbackSpeed).toLong())
            onIdle()
        }
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
    val leftLift = if (popup.leftRemoved) with(density) { -18.dp.toPx() } else 0f
    val rightLift = if (popup.rightRemoved) with(density) { -18.dp.toPx() } else 0f
    val tracker = remember { VelocityTracker() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = scrim)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
                .heightIn(max = 520.dp)
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
            color = MaterialTheme.colorScheme.surface,
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
                        artworkHeight = 142.dp,
                        openProgress = openProgress.value,
                        leftLift = leftLift,
                        rightLift = rightLift,
                    )
                }

                BatteryGrid(
                    state = popup.deviceState,
                    reveal = popup.phase == PopupPhase.SHOWING_BATTERY ||
                        popup.phase == PopupPhase.CONNECTED ||
                        popup.phase == PopupPhase.UPDATED ||
                        popup.phase == PopupPhase.IDLE_VISIBLE ||
                        popup.phase == PopupPhase.DRAGGING,
                    staggerMs = settings.batteryStaggerMs,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    text = statusText(popup),
                    style = MaterialTheme.typography.labelLarge,
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

private const val MotionLabExitMs = 220L

private fun com.galaxyairpods.domain.model.AirPodsState.batterySourceLabel(): String = when (batterySource) {
    "AAP_CLASSIC_EXACT" -> "AAP · 정밀 배터리"
    "BLE_PUBLIC_COARSE" -> "BLE · 공개 광고"
    "LEGACY_COARSE" -> "Legacy · 낮은 정밀도"
    else -> "배터리 확인 중"
}
