package com.galaxyairpods.service

import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleService
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import com.galaxyairpods.data.persistence.AirPodsDataStore
import com.galaxyairpods.design.AirPodsGalaxyTheme
import com.galaxyairpods.ui.components.BatteryGrid
import com.galaxyairpods.ui.components.ProductRenderer

/**
 * Official overlay fallback surface. The service is intentionally opt-in: the
 * app asks for Settings.canDrawOverlays() and never silently starts it.
 */
class AirPodsOverlayService : LifecycleService() {
    private var windowManager: WindowManager? = null
    private var overlayView: ComposeView? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM
        }

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                AirPodsGalaxyTheme {
                    OverlayNotice(onClose = { stopSelf() })
                }
            }
        }
        overlayView = view
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { windowManager?.addView(view, params) }.onFailure { stopSelf() }
    }

    override fun onDestroy() {
        overlayView?.let { view -> runCatching { windowManager?.removeView(view) } }
        overlayView = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}

@Composable
private fun OverlayNotice(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by AirPodsDataStore(context).latestState.collectAsState(initial = null)
    Surface(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 12.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            if (state == null) {
                Text("AirPods Galaxy", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(6.dp))
                Text("저장된 실측 상태가 없습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(state!!.model.label, style = MaterialTheme.typography.titleLarge)
                Text("다른 앱 위 상태 팝업", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ProductRenderer(state = state!!, openProgress = if (state!!.caseOpen == true) 1f else 0f)
                BatteryGrid(state = state!!, modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) {
                Text("닫기")
            }
        }
    }
}
