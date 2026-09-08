package com.galaxyairpods.service

import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.background
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.design.AirPodsGalaxyTheme
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.ui.components.BatteryGrid
import com.galaxyairpods.ui.components.ProductRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Shows a short-lived system overlay containing the last real BLE state.
 *
 * The monitor service is already a connected-device foreground service. This
 * short-lived helper is started by that service and only owns the overlay
 * window, avoiding a second foreground-service start that Samsung/Android can
 * reject while the app is backgrounded.
 */
class AirPodsOverlayService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val displayedState = MutableStateFlow<AirPodsState?>(null)
    private lateinit var dataStore: AirPodsDataStore
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null
    private var overlayViewTreeOwner: OverlayViewTreeOwner? = null
    private var refreshJob: Job? = null
    private var stateJob: Job? = null
    private var hideJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        dataStore = AirPodsDataStore(this)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayViewTreeOwner = OverlayViewTreeOwner()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!Settings.canDrawOverlays(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            val state = dataStore.latestDisplayState.first()?.let { stored ->
                if (dataStore.wearDetectionEnabled.first()) stored else stored.withoutWearDetection()
            }
            if (state == null) {
                stopSelfResult(startId)
                return@launch
            }

            displayedState.value = state
            ensureOverlayView()

            stateJob?.cancel()
            stateJob = launch {
                combine(dataStore.latestDisplayState, dataStore.wearDetectionEnabled) { liveState, wearEnabled ->
                    liveState?.let { if (wearEnabled) it else it.withoutWearDetection() }
                }.collect { liveState ->
                    if (liveState != null) displayedState.value = liveState
                }
            }

            hideJob?.cancel()
            val durationSeconds = dataStore.popupDuration.first().coerceIn(2, 15)
            hideJob = launch {
                delay(durationSeconds * 1_000L)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureOverlayView() {
        if (overlayView != null) return
        val manager = windowManager ?: run {
            stopSelf()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
        }

        val view = ComposeView(this).apply {
            // A ComposeView attached directly to a TYPE_APPLICATION_OVERLAY
            // window does not inherit the owners that an Activity decor view
            // normally supplies. Compose otherwise crashes as soon as the
            // overlay is attached with "ViewTreeLifecycleOwner not found".
            setViewTreeLifecycleOwner(this@AirPodsOverlayService)
            setViewTreeSavedStateRegistryOwner(overlayViewTreeOwner)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AirPodsGalaxyTheme {
                    OverlayNotice(
                        state = displayedState,
                        onClose = { stopSelf() },
                    )
                }
            }
        }

        runCatching { manager.addView(view, params) }
            .onSuccess { overlayView = view }
            .onFailure { stopSelf() }
    }

    override fun onDestroy() {
        refreshJob?.cancel()
        stateJob?.cancel()
        hideJob?.cancel()
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        overlayViewTreeOwner?.destroy()
        overlayViewTreeOwner = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

}

/**
 * Compose also requires a SavedStateRegistryOwner for a view that is attached
 * outside an Activity. A Service is a LifecycleOwner, but it is not a
 * SavedStateRegistryOwner, so the overlay supplies a small window-scoped
 * owner and destroys it with the overlay service.
 */
private class OverlayViewTreeOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performAttach()
        // There is no Activity instance to restore from. Mark the registry as
        // restored before moving the window owner to CREATED so Recreator does
        // not try to consume an absent component state bundle.
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun destroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}

@Composable
private fun OverlayNotice(
    state: StateFlow<AirPodsState?>,
    onClose: () -> Unit,
) {
    val current by state.collectAsState()
    if (current == null) return
    val displayed = current ?: return

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.22f)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .navigationBarsPadding()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
            shape = MaterialTheme.shapes.extraLarge,
            color = Color.White,
            tonalElevation = 0.dp,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(displayed.model.label, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(2.dp))
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        ) {
                            Text(
                                displayed.connectionLabel,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "팝업 닫기")
                    }
                }

                ProductRenderer(
                    state = displayed,
                    modifier = Modifier.fillMaxWidth(),
                    artworkHeight = 126.dp,
                    openProgress = if (displayed.caseOpen == true) 1f else 0f,
                    showCase = !(displayed.caseOpen == true &&
                        displayed.leftInCase == false &&
                        displayed.rightInCase == false),
                )
                BatteryGrid(
                    state = displayed,
                    modifier = Modifier.fillMaxWidth(),
                    staggerMs = 70L,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "마지막 업데이트 · ${formatAge(displayed.batteryCapturedAt ?: displayed.lastSeenAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "${sourceLabel(displayed.batterySource)} · ${wearLabel(displayed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun sourceLabel(source: String?): String = when (source) {
    "AAP_CLASSIC_EXACT" -> "AAP · 정밀 배터리"
    "BLE_PUBLIC_COARSE" -> "BLE · 공개 광고(약 10%)"
    "LEGACY_COARSE" -> "Legacy · 낮은 정밀도"
    else -> "배터리 확인 중"
}

private fun wearLabel(state: AirPodsState): String = when (state.wearState) {
    com.galaxyairpods.domain.model.AirPodsWearState.LEFT_IN_EAR -> "왼쪽 착용"
    com.galaxyairpods.domain.model.AirPodsWearState.RIGHT_IN_EAR -> "오른쪽 착용"
    com.galaxyairpods.domain.model.AirPodsWearState.BOTH_IN_EAR -> "양쪽 착용"
    com.galaxyairpods.domain.model.AirPodsWearState.PARTIAL_IN_EAR -> "부분 착용"
    com.galaxyairpods.domain.model.AirPodsWearState.NONE_IN_EAR -> "미착용"
    com.galaxyairpods.domain.model.AirPodsWearState.IN_CASE -> "케이스 안"
    com.galaxyairpods.domain.model.AirPodsWearState.CONFLICT -> "착용 상태 충돌"
    com.galaxyairpods.domain.model.AirPodsWearState.UNKNOWN -> "착용 감지 확인 중"
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
