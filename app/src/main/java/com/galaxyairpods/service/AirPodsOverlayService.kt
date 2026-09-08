package com.galaxyairpods.service

import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleService
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
import kotlinx.coroutines.flow.first
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
    private var refreshJob: Job? = null
    private var hideJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        dataStore = AirPodsDataStore(this)

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!Settings.canDrawOverlays(this)) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        refreshJob?.cancel()
        refreshJob = serviceScope.launch {
            val state = dataStore.latestDisplayState.first()
            if (state == null) {
                stopSelfResult(startId)
                return@launch
            }

            displayedState.value = state
            ensureOverlayView()

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
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val view = ComposeView(this).apply {
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
        hideJob?.cancel()
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)

}

@Composable
private fun OverlayNotice(
    state: StateFlow<AirPodsState?>,
    onClose: () -> Unit,
) {
    val current by state.collectAsState()
    if (current == null) return

    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(current!!.model.label, style = MaterialTheme.typography.titleLarge)
            Text("AirPods 배터리", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            ProductRenderer(
                state = current!!,
                openProgress = if (current!!.caseOpen == true) 1f else 0f,
            )
            BatteryGrid(state = current!!, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("닫기")
            }
        }
    }
}
