package com.galaxyairpods.ui

import android.content.Context
import com.galaxyairpods.domain.motion.MotionLabSettings
import org.json.JSONObject

class MotionCandidateStore(private val context: Context) {
    fun save(fileName: String, settings: MotionLabSettings) {
        val json = JSONObject()
            .put("cardDamping", settings.cardDamping)
            .put("cardStiffness", settings.cardStiffness)
            .put("cardInitialY", settings.cardInitialY)
            .put("cardInitialScale", settings.cardInitialScale)
            .put("productDelayMs", settings.productDelayMs)
            .put("productDamping", settings.productDamping)
            .put("productStiffness", settings.productStiffness)
            .put("batteryDelayMs", settings.batteryDelayMs)
            .put("batteryStaggerMs", settings.batteryStaggerMs)
            .put("exitY", settings.exitY)
            .put("exitScale", settings.exitScale)
            .put("playbackSpeed", settings.playbackSpeed)
            .put("source", "Galaxy implementation baseline; not Apple internal constants")

        context.openFileOutput(fileName, Context.MODE_PRIVATE).use { output ->
            output.write(json.toString(2).toByteArray())
        }
    }
}
