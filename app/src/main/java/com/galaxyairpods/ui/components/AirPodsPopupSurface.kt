package com.galaxyairpods.ui.components

import androidx.compose.animation.core.Animatable
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
import com.galaxyairpods.domain.motion.MotionTokens
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
    var openProgressTarget by remember { mutableStateOf(0f) }
    var leftLiftTarget by remember { mutableStateOf(0f) }
    var rightLiftTarget by remember { mutableStateOf(0f) }
    var componentLayoutTarget by remember { mutableStateOf(0f) }
    var entrancePopupId by remember { mutableStateOf<Long?>(null) }
    var entranceRunning by remember { mutableStateOf(false) }

    val desiredLeftLift = if (popup.leftRemoved) {
        with(density) { -52.dp.toPx() }
    } else {
        0f
    }
    val desiredRightLift = if (popup.rightRemoved) {
        with(density) { -52.dp.toPx() }
    } else {
        0f
    }

    // Battery updates never restart this effect. A new animationId starts a
    // complete popup entrance, while case/earbud changes after that are
    // handled by the smaller target effect below.
    val animationKey = if (popup.phase == PopupPhase.EXITING) popup.eventId else popup.animationId
    LaunchedEffect(animationKey, reducedMotion) {
        if (popup.phase == PopupPhase.EXITING) {
            entranceRunning = false
            openProgressTarget = 0f
            leftLiftTarget = desiredLeftLift
            rightLiftTarget = desiredRightLift
            componentLayoutTarget = 0f
            val exitDurationMs = if (reducedMotion) 120L else MotionLabExitMs
            launch {
                cardY.animateTo(
                    settings.exitY,
                    if (reducedMotion) tween(exitDurationMs.toInt())
                    else spring(dampingRatio = 0.96f, stiffness = 520f),
                )
            }
            launch {
                cardScale.animateTo(
                    settings.exitScale,
                    if (reducedMotion) tween(exitDurationMs.toInt())
                    else spring(dampingRatio = 0.98f, stiffness = 520f),
                )
            }
            launch {
                cardAlpha.animateTo(
                    0f,
                    if (reducedMotion) tween(exitDurationMs.toInt())
                    else spring(dampingRatio = 1f, stiffness = 650f),
                )
            }
            launch {
                scrimAlpha.animateTo(
                    0f,
                    if (reducedMotion) tween(exitDurationMs.toInt())
                    else spring(dampingRatio = 1f, stiffness = 700f),
                )
            }
            delay((exitDurationMs / settings.playbackSpeed).toLong())
            onHide()
            return@LaunchedEffect
        }

        entrancePopupId = popup.animationId
        entranceRunning = true

        if (reducedMotion) {
            // Reduced motion still gets a short, observable fade/crossfade.
            // Snapping every channel made the popup look broken on devices
            // whose global animator scale was set to zero.
            val reducedEntryMs = (120L / settings.playbackSpeed)
                .toLong()
                .coerceAtLeast(1L)
                .toInt()
            openProgressTarget = if (popup.deviceState.caseOpen == true) 1f else 0f
            leftLiftTarget = desiredLeftLift
            rightLiftTarget = desiredRightLift
            componentLayoutTarget = 0f
            launch { cardY.animateTo(0f, tween(reducedEntryMs)) }
            launch { cardScale.animateTo(1f, tween(reducedEntryMs)) }
            launch { cardAlpha.animateTo(1f, tween(reducedEntryMs)) }
            launch { scrimAlpha.animateTo(settings.scrimAlpha, tween(reducedEntryMs)) }
            launch { productY.animateTo(0f, tween(reducedEntryMs)) }
            launch { productScale.animateTo(1f, tween(reducedEntryMs)) }
            launch { productAlpha.animateTo(1f, tween(reducedEntryMs)) }
            delay(reducedEntryMs.toLong())
            entranceRunning = false
            onBatteryVisible()
            onIdle()
            return@LaunchedEffect
        }

        // Reset the channels for every new popup. Without this, re-opening an
        // overlay that shares its ComposeView can start at the settled frame
        // and make the Apple-like entrance appear to be missing.
        cardY.snapTo(settings.cardInitialY)
        cardScale.snapTo(settings.cardInitialScale)
        cardAlpha.snapTo(0f)
        scrimAlpha.snapTo(0f)
        productY.snapTo(settings.productInitialYOffsetDp)
        productScale.snapTo(settings.productInitialScale)
        productAlpha.snapTo(0f)
        dragOffset.snapTo(0f)
        openProgressTarget = 0f
        leftLiftTarget = 0f
        rightLiftTarget = 0f
        componentLayoutTarget = 0f

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
        launch {
            scrimAlpha.animateTo(settings.scrimAlpha, spring(dampingRatio = 1f, stiffness = 800f))
        }

        launch {
            delay((settings.productDelayMs / settings.playbackSpeed).toLong())
            openProgressTarget = if (popup.deviceState.caseOpen == true) 1f else 0f
            productAlpha.animateTo(1f, spring(dampingRatio = 1f, stiffness = 700f))
            productY.animateTo(0f, spring(settings.productDamping, settings.productStiffness))
            productScale.animateTo(1f, spring(settings.productDamping, settings.productStiffness))
        }
        launch {
            // Wait for the lid to reach its open pose, then lift both earbuds
            // from the same frame.  The previous stagger made the two buds
            // look unrelated and also exposed their different source-canvas
            // sizes more clearly.
            delay(((settings.productDelayMs + MotionTokens.CaseOpenDurationMs) /
                settings.playbackSpeed).toLong())
            leftLiftTarget = desiredLeftLift
            rightLiftTarget = desiredRightLift
            delay(((MotionTokens.EarbudMotionDurationMs + MotionTokens.ComponentArrangeDelayMs) /
                settings.playbackSpeed).toLong())
            if (popup.deviceState.caseOpen == true && popup.leftRemoved && popup.rightRemoved) {
                componentLayoutTarget = 1f
            }
        }
        launch {
            delay((settings.batteryDelayMs / settings.playbackSpeed).toLong())
            onBatteryVisible()
        }
        launch {
            delay((MotionTokens.PopupEntranceCompleteMs / settings.playbackSpeed).toLong())
            entranceRunning = false
            onIdle()
        }
    }

    // A lid or one-bud change while the popup is visible retargets only the
    // affected product channel. It never restarts the card or battery reveal.
    LaunchedEffect(
        popup.deviceState.caseOpen,
        popup.leftRemoved,
        popup.rightRemoved,
        popup.deviceState.leftInCase,
        popup.deviceState.rightInCase,
        popup.animationId,
        entranceRunning,
        reducedMotion,
    ) {
        if (!entranceRunning &&
            entrancePopupId == popup.animationId &&
            popup.phase != PopupPhase.EXITING
        ) {
            openProgressTarget = if (popup.deviceState.caseOpen == true) 1f else 0f
            leftLiftTarget = desiredLeftLift
            rightLiftTarget = desiredRightLift
            componentLayoutTarget = if (
                popup.deviceState.caseOpen == true &&
                popup.leftRemoved &&
                popup.rightRemoved
            ) 1f else 0f
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
                        artworkHeight = 188.dp,
                        openProgress = openProgressTarget,
                        leftLift = leftLiftTarget,
                        rightLift = rightLiftTarget,
                        componentLayout = componentLayoutTarget,
                        // Once both buds are really out of the open case, the
                        // settled popup focuses on the two independent buds.
                        // During entrance/partial removal the case remains so
                        // the lid-to-bud motion is still understandable.
                        showCase = shouldShowCase(popup, keepCaseDuringEntrance = entranceRunning),
                        reducedMotion = reducedMotion,
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

private const val MotionLabExitMs = 220L

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

private fun shouldShowCase(
    popup: PopupUiState,
    keepCaseDuringEntrance: Boolean,
): Boolean =
    keepCaseDuringEntrance ||
        popup.deviceState.caseOpen != false ||
        popup.deviceState.leftInCase != false ||
        popup.deviceState.rightInCase != false ||
        popup.phase in setOf(
            PopupPhase.ENTERING,
            PopupPhase.DETECTED,
            PopupPhase.SHOWING_DEVICE,
            PopupPhase.SHOWING_BATTERY,
            PopupPhase.EXITING,
        )

private fun com.galaxyairpods.domain.model.AirPodsState.batterySourceLabel(): String = when (batterySource) {
    "AAP_CLASSIC_EXACT" -> "AAP · 정밀 배터리"
    "BLE_PUBLIC_COARSE" -> "BLE · 공개 광고"
    "LEGACY_COARSE" -> "Legacy · 낮은 정밀도"
    else -> "배터리 확인 중"
}
