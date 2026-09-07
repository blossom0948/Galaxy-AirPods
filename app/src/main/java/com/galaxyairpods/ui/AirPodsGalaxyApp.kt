package com.galaxyairpods.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.galaxyairpods.design.AirPodsGalaxyTheme
import com.galaxyairpods.ui.components.AirPodsPopupSurface
import com.galaxyairpods.ui.screens.DiagnosticsScreen
import com.galaxyairpods.ui.screens.HomeScreen
import com.galaxyairpods.ui.screens.PreviewLabScreen
import com.galaxyairpods.ui.screens.SettingsScreen

private enum class AppDestination(val label: String, val glyph: String) {
    HOME("Home", "⌂"),
    PREVIEW("Preview", "✦"),
    DEBUG("Debug", "◌"),
    SETTINGS("Settings", "⚙"),
}

@Composable
fun AirPodsGalaxyApp(viewModel: AppViewModel = viewModel()) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var destinationName by rememberSaveable { mutableStateOf(AppDestination.HOME.name) }
    var reducedMotion by rememberSaveable { mutableStateOf(AccessibilityMotion.systemRequestsReducedMotion(context)) }
    val destination = AppDestination.valueOf(destinationName)
    val airPodsState by viewModel.airPodsState.collectAsStateWithLifecycle()
    val hasPersistedState by viewModel.hasPersistedState.collectAsStateWithLifecycle()
    val popupState by viewModel.popupState.collectAsStateWithLifecycle()
    val motionSettings by viewModel.motionSettings.collectAsStateWithLifecycle()
    val autoPopup by viewModel.autoPopup.collectAsStateWithLifecycle()
    val showOnCaseOpen by viewModel.showOnCaseOpen.collectAsStateWithLifecycle()
    val popupDuration by viewModel.popupDuration.collectAsStateWithLifecycle()
    val backgroundDetection by viewModel.backgroundDetection.collectAsStateWithLifecycle()

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
            androidx.compose.foundation.layout.Box(Modifier.padding(paddingValues)) {
                when (destination) {
                    AppDestination.HOME -> HomeScreen(
                        state = airPodsState,
                        isPreviewData = !hasPersistedState,
                        onOpenPreview = { destinationName = AppDestination.PREVIEW.name },
                        onOpenDiagnostics = { destinationName = AppDestination.DEBUG.name },
                        onOpenSettings = { destinationName = AppDestination.SETTINGS.name },
                    )

                    AppDestination.PREVIEW -> PreviewLabScreen(
                        settings = motionSettings,
                        onSettingsChange = viewModel::updateMotionSettings,
                        onReset = viewModel::resetMotionSettings,
                        onTrigger = viewModel::preview,
                        onSaveCandidate = viewModel::saveMotionCandidate,
                    )

                    AppDestination.DEBUG -> DiagnosticsScreen(scanner = viewModel.scanner)

                    AppDestination.SETTINGS -> SettingsScreen(
                        autoPopup = autoPopup,
                        showOnCaseOpen = showOnCaseOpen,
                        popupDuration = popupDuration,
                        backgroundDetection = backgroundDetection,
                        reducedMotion = reducedMotion,
                        onAutoPopupChange = viewModel::setAutoPopup,
                        onCaseOpenChange = viewModel::setShowOnCaseOpen,
                        onPopupDurationChange = viewModel::setPopupDuration,
                        onBackgroundDetectionChange = viewModel::setBackgroundDetection,
                        onTestOverlay = viewModel::testOverlay,
                        onReducedMotionChange = { reducedMotion = it },
                    )
                }
            }
        }

        if (popupState.isVisible) {
            BackHandler(enabled = true) { viewModel.dismissPopup() }
            AirPodsPopupSurface(
                popup = popupState,
                settings = motionSettings,
                popupDurationSeconds = popupDuration,
                reducedMotion = reducedMotion,
                onDismiss = viewModel::dismissPopup,
                onHide = viewModel::hidePopup,
                onBatteryVisible = viewModel::markPopupBatteryVisible,
                onIdle = viewModel::markPopupIdle,
                onDraggingChanged = viewModel.popupController::setDragging,
            )
        }
    }
}
