package com.galaxyairpods.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.AirPodsConnectionState
import com.galaxyairpods.permissions.PermissionManager
import com.galaxyairpods.ui.components.BatteryGrid
import com.galaxyairpods.ui.components.ProductRenderer
import com.galaxyairpods.update.UpdateInfo
import com.galaxyairpods.update.UpdateState

@Composable
fun HomeScreen(
    state: AirPodsState,
    scanStatus: String,
    updateState: UpdateState,
    onStartScanning: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartUpdate: (UpdateInfo) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var permissionRefresh by remember { mutableIntStateOf(0) }
    val permissionsGranted = remember(permissionRefresh) {
        // Notification permission is optional for Bluetooth detection. It
        // enables the foreground notification/media-session bridge, but a
        // denial must not stop scanning or wear detection.
        PermissionManager.allGranted(context, PermissionManager.bluetoothPermissions())
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionRefresh++ }

    LaunchedEffect(permissionsGranted) {
        if (permissionsGranted) onStartScanning()
    }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("AirPods Galaxy", style = MaterialTheme.typography.headlineMedium)
                Text(
                    if (state.deviceId != null) state.model.label else "AirPods 검색 중",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.deviceName?.takeIf { it.isNotBlank() }?.let { deviceName ->
                    Text(
                        deviceName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            StatusDot(state = state)
        }

        if (!permissionsGranted) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("권한 필요", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Bluetooth 권한을 허용해야 AirPods를 감지합니다. 알림 권한은 보조 기능입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        permissionLauncher.launch(PermissionManager.runtimePermissions())
                    }) { Text("권한 허용") }
                }
            }
        }

        if (updateState is UpdateState.Available) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "새 버전 ${updateState.info.versionName} 사용 가능",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "업데이트가 감지되었습니다. 지금 다운로드와 설치를 시작할 수 있습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { onStartUpdate(updateState.info) }) {
                        Text("업데이트 시작")
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(
                    state.connectionLabel,
                    style = MaterialTheme.typography.titleLarge,
                    color = if (state.isAndroidConnected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                ProductRenderer(
                    state = state,
                    modifier = Modifier.fillMaxWidth(),
                    openProgress = if (state.caseOpen == true) 1f else 0f,
                )
                BatteryGrid(state = state, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(
                    scanStatus,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "마지막 갱신 · ${formatLastSeen(state.lastSeenAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Button(onClick = onStartScanning, modifier = Modifier.fillMaxWidth()) {
            Text("다시 검색")
        }
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text("설정")
        }
    }
}

@Composable
private fun StatusDot(state: AirPodsState) {
    val dotColor = when (state.connectionState) {
        AirPodsConnectionState.ANDROID_CONNECTED -> MaterialTheme.colorScheme.primary
        AirPodsConnectionState.NEARBY_ONLY,
        AirPodsConnectionState.OTHER_DEVICE_OR_CONNECTION_PENDING,
        -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = dotColor.copy(alpha = 0.14f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(Modifier.size(8.dp), shape = RoundedCornerShape(50), color = dotColor) {}
            Text(state.connectionLabel, style = MaterialTheme.typography.labelSmall, color = dotColor)
        }
    }
}

private fun formatLastSeen(timestamp: Long?): String {
    if (timestamp == null) return "확인된 적 없음"
    val minutes = ((System.currentTimeMillis() - timestamp).coerceAtLeast(0L) / 60_000L)
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        else -> "${minutes / 60}시간 전"
    }
}
