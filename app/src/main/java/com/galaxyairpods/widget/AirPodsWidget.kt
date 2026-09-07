package com.galaxyairpods.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.layout.ColumnScope

private object WidgetKeys {
    val left = intPreferencesKey("widget_left")
    val right = intPreferencesKey("widget_right")
    val case = intPreferencesKey("widget_case")
    val model = stringPreferencesKey("widget_model")
}

class AirPodsWidget : GlanceAppWidget() {
    override val stateDefinition = PreferencesGlanceStateDefinition
    override val sizeMode = SizeMode.Responsive(
        setOf(
            DpSize(110.dp, 80.dp),
            DpSize(250.dp, 110.dp),
            DpSize(250.dp, 56.dp),
        ),
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val preferences = currentState<Preferences>()
            val left = preferences[WidgetKeys.left]
            val right = preferences[WidgetKeys.right]
            val caseBattery = preferences[WidgetKeys.case]
            val modelLabel = preferences[WidgetKeys.model]
            when {
                LocalSize.current.height <= 60.dp -> MinimalWidget(left, right, caseBattery, modelLabel)
                LocalSize.current.width >= 220.dp -> VisualWidget(left, right, caseBattery, modelLabel)
                else -> CompactWidget(left, right, caseBattery, modelLabel)
            }
        }
    }

    companion object {
        suspend fun updateState(
            context: Context,
            left: Int?,
            right: Int?,
            caseBattery: Int?,
            modelLabel: String? = null,
        ) {
            updateAllAppWidgetState(context, left, right, caseBattery, modelLabel)
            AirPodsWidget().updateAll(context)
        }
    }
}

@Composable
private fun CompactWidget(left: Int?, right: Int?, caseBattery: Int?, modelLabel: String?) {
    WidgetColumn {
        Text(modelLabel ?: "AirPods", style = whiteText)
        Text(batterySummary(modelLabel, left, right, caseBattery), style = mintText)
    }
}

@Composable
private fun VisualWidget(left: Int?, right: Int?, caseBattery: Int?, modelLabel: String?) {
    Row(
        modifier = GlanceModifier.fillMaxSize().background(panelColor).padding(14.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text("◉", style = TextStyle(color = ColorProvider(Color(0xFF9FE6D7))))
        Spacer(GlanceModifier.width(12.dp))
        WidgetColumn {
            Text(modelLabel ?: "AirPods", style = whiteText)
            Text(batterySummary(modelLabel, left, right, caseBattery), style = mutedText)
        }
    }
}

@Composable
private fun MinimalWidget(left: Int?, right: Int?, caseBattery: Int?, modelLabel: String?) {
    Row(
        modifier = GlanceModifier.fillMaxSize().background(panelColor).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text("🎧", style = whiteText)
        Spacer(GlanceModifier.width(8.dp))
        Text(batterySummary(modelLabel, left, right, caseBattery), style = mintText)
    }
}

@Composable
private fun WidgetColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier.fillMaxSize().background(panelColor).padding(14.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        content = content,
    )
}

private val panelColor = ColorProvider(Color(0xFF151B26))
private val whiteText = TextStyle(color = ColorProvider(Color(0xFFFFFFFF)))
private val mintText = TextStyle(color = ColorProvider(Color(0xFF9FE6D7)))
private val mutedText = TextStyle(color = ColorProvider(Color(0xFFB8C0CC)))

private fun Int?.percentOrDash(): String = this?.let { "$it%" } ?: "--"

private fun batterySummary(
    modelLabel: String?,
    left: Int?,
    right: Int?,
    caseBattery: Int?,
): String = if (modelLabel?.contains("Max") == true) {
    "헤드폰 " + left.percentOrDash()
} else {
    "L " + left.percentOrDash() + " · R " + right.percentOrDash() +
        " · Case " + caseBattery.percentOrDash()
}

class AirPodsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AirPodsWidget()
}

private suspend fun updateAllAppWidgetState(
    context: Context,
    left: Int?,
    right: Int?,
    caseBattery: Int?,
    modelLabel: String?,
) {
    val manager = GlanceAppWidgetManager(context)
    manager.getGlanceIds(AirPodsWidget::class.java).forEach { glanceId ->
        updateAppWidgetState(context, glanceId) { preferences ->
            if (left == null) preferences.remove(WidgetKeys.left) else preferences[WidgetKeys.left] = left
            if (right == null) preferences.remove(WidgetKeys.right) else preferences[WidgetKeys.right] = right
            if (caseBattery == null) preferences.remove(WidgetKeys.case) else preferences[WidgetKeys.case] = caseBattery
            if (modelLabel == null) preferences.remove(WidgetKeys.model) else preferences[WidgetKeys.model] = modelLabel
        }
    }
}
