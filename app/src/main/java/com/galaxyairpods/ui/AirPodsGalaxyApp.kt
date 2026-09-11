package com.galaxyairpods.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxyairpods.design.AirPodsGalaxyTheme
import com.galaxyairpods.ui.screens.HomeScreen
import com.galaxyairpods.ui.screens.SettingsScreen
import com.galaxyairpods.update.UpdateState

private enum class AppDestination(val label: String, val glyph: String) {
    HOME("홈", "⌂"),
    SETTINGS("설정", "⚙"),
}

@Composable
fun AirPodsGalaxyApp(viewModel: AppViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    var reducedMotion by rememberSaveable {
        mutableStateOf(AccessibilityMotion.systemRequestsReducedMotion(context))
    }
    val destination = AppDestination.entries.firstOrNull { it.name == destinationName }
        ?: AppDestination.HOME
    val airPodsState by viewModel.airPodsState.collectAsStateWithLifecycle()
    val autoPopup by viewModel.autoPopup.collectAsStateWithLifecycle()
    val showOnCaseOpen by viewModel.showOnCaseOpen.collectAsStateWithLifecycle()
    val popupDuration by viewModel.popupDuration.collectAsStateWithLifecycle()
    val backgroundDetection by viewModel.backgroundDetection.collectAsStateWithLifecycle()
    val wearDetectionEnabled by viewModel.wearDetectionEnabled.collectAsStateWithLifecycle()
    val automaticMediaControlEnabled by viewModel.automaticMediaControlEnabled.collectAsStateWithLifecycle()
    val modelOverride by viewModel.modelOverride.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.startScanning() }

    AirPodsGalaxyTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    AppDestination.entries.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destinationName = item.name },
                            icon = { Text(item.glyph) },
                            label = { Text(item.label) },
                        )
                    }
                }
            },
        ) { paddingValues ->
            Box(Modifier.fillMaxSize().padding(paddingValues)) {
                when (destination) {
                    AppDestination.HOME -> HomeScreen(
                        state = airPodsState,
                        scanStatus = scanStatus,
                        updateState = updateState,
                        onStartScanning = viewModel::startScanning,
                        onOpenSettings = { destinationName = AppDestination.SETTINGS.name },
                        onStartUpdate = viewModel::startUpdate,
                    )

                    AppDestination.SETTINGS -> SettingsScreen(
                        autoPopup = autoPopup,
                        showOnCaseOpen = showOnCaseOpen,
                        popupDuration = popupDuration,
                        backgroundDetection = backgroundDetection,
                        wearDetectionEnabled = wearDetectionEnabled,
                        automaticMediaControlEnabled = automaticMediaControlEnabled,
                        modelOverride = modelOverride,
                        reducedMotion = reducedMotion,
                        onAutoPopupChange = viewModel::setAutoPopup,
                        onCaseOpenChange = viewModel::setShowOnCaseOpen,
                        onPopupDurationChange = viewModel::setPopupDuration,
                        onBackgroundDetectionChange = viewModel::setBackgroundDetection,
                        onWearDetectionChange = viewModel::setWearDetectionEnabled,
                        onAutomaticMediaControlChange = viewModel::setAutomaticMediaControlEnabled,
                        onModelOverrideChange = viewModel::setModelOverride,
                        onTestOverlay = viewModel::testOverlay,
                        onReducedMotionChange = { reducedMotion = it },
                        onStartScanning = viewModel::startScanning,
                        updateState = updateState,
                        onCheckForUpdates = viewModel::checkForUpdates,
                        onStartUpdate = viewModel::startUpdate,
                        onInstallReady = viewModel::installReadyUpdate,
                    )
                }
            }
        }

    }
}
