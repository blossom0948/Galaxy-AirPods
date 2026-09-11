package com.galaxyairpods.service

import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import com.galaxyairpods.domain.model.PopupEvent
import com.galaxyairpods.domain.model.PopupUiState
import com.galaxyairpods.domain.motion.MotionLabSettings
import com.galaxyairpods.domain.popup.PopupMotionController
import com.galaxyairpods.ui.AccessibilityMotion
import com.galaxyairpods.ui.components.AirPodsPopupSurface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
private data class OverlayTrigger(
    val caseOpen: Boolean?,
    val leftInCase: Boolean?,
    val rightInCase: Boolean?,
)

class AirPodsOverlayService : LifecycleService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val popupController = PopupMotionController()
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
            val trigger = OverlayTrigger(
                caseOpen = intent?.booleanExtraOrNull(EXTRA_CASE_OPEN),
                leftInCase = intent?.booleanExtraOrNull(EXTRA_LEFT_IN_CASE),
                rightInCase = intent?.booleanExtraOrNull(EXTRA_RIGHT_IN_CASE),
            )
            val state = dataStore.latestDisplayState.first()?.let { stored ->
                if (dataStore.wearDetectionEnabled.first()) stored else stored.withoutWearDetection()
            }?.withOverlayTrigger(trigger)
            if (state == null) {
                stopSelfResult(startId)
                return@launch
            }

            popupController.show(state)
            val durationSeconds = dataStore.popupDuration.first().coerceIn(2, 15)
            ensureOverlayView(durationSeconds)

            stateJob?.cancel()
            stateJob = launch {
                var previousState: AirPodsState? = state
                var firstLiveEmission = true
                combine(dataStore.latestDisplayState, dataStore.wearDetectionEnabled) { liveState, wearEnabled ->
                    liveState?.let { if (wearEnabled) it else it.withoutWearDetection() }
                }.collect { liveState ->
                    if (liveState != null) {
                        // The first DataStore emission can race the monitor's
                        // saveState call. Apply the event snapshot once so a
                        // real lid-open event cannot begin with stale false.
                        val effectiveState = if (firstLiveEmission) {
                            liveState.withOverlayTrigger(trigger)
                        } else {
                            liveState
                        }
                        firstLiveEmission = false
                        val previous = previousState
                        when {
                            previous?.caseOpen != true && effectiveState.caseOpen == true ->
                                popupController.dispatch(PopupEvent.CaseOpened(effectiveState))
                            previous?.caseOpen == true && effectiveState.caseOpen == false ->
                                popupController.dispatch(PopupEvent.CaseClosed(effectiveState))
                            else -> popupController.dispatch(PopupEvent.BatteryUpdated(effectiveState))
                        }
                        previousState = effectiveState
                    }
                }
            }

            hideJob?.cancel()
            hideJob = launch {
                delay(durationSeconds * 1_000L)
                popupController.dispatch(PopupEvent.Timeout)
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureOverlayView(durationSeconds: Int) {
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
                        state = popupController.state,
                        popupDurationSeconds = durationSeconds,
                        onDismiss = { popupController.dispatch(PopupEvent.UserDismiss) },
                        onHide = { stopSelf() },
                        onBatteryVisible = { popupController.markBatteryVisible() },
                        onIdle = { popupController.markIdle() },
                        onDraggingChanged = popupController::setDragging,
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

internal const val EXTRA_CASE_OPEN = "com.galaxyairpods.overlay.CASE_OPEN"
internal const val EXTRA_LEFT_IN_CASE = "com.galaxyairpods.overlay.LEFT_IN_CASE"
internal const val EXTRA_RIGHT_IN_CASE = "com.galaxyairpods.overlay.RIGHT_IN_CASE"

private fun Intent.booleanExtraOrNull(key: String): Boolean? =
    if (hasExtra(key)) getBooleanExtra(key, false) else null

private fun AirPodsState.withOverlayTrigger(trigger: OverlayTrigger): AirPodsState = copy(
    caseOpen = trigger.caseOpen ?: caseOpen,
    leftInCase = trigger.leftInCase ?: leftInCase,
    rightInCase = trigger.rightInCase ?: rightInCase,
)

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
    state: StateFlow<PopupUiState>,
    popupDurationSeconds: Int,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
    onBatteryVisible: () -> Unit,
    onIdle: () -> Unit,
    onDraggingChanged: (Boolean) -> Unit,
) {
    val popup by state.collectAsState()
    if (!popup.isVisible) return

    // Recreate only for a new semantic popup. Battery samples keep the same
    // subtree so they update values without resetting the entrance timeline.
    key(popup.animationId) {
        AirPodsPopupSurface(
            popup = popup,
            settings = MotionLabSettings.Default,
            popupDurationSeconds = popupDurationSeconds,
            reducedMotion = AccessibilityMotion.systemRequestsReducedMotion(LocalContext.current),
            onDismiss = onDismiss,
            onHide = onHide,
            onBatteryVisible = onBatteryVisible,
            onIdle = onIdle,
            onDraggingChanged = onDraggingChanged,
        )
    }
}
