package com.galaxyairpods.data.bluetooth.legacy

/**
 * Optional coarse HFP compatibility parsers. These values represent one
 * accessory-level observation only; they are not L/R/Case exact telemetry.
 */
internal fun parseIphoneAccessoryBattery(raw: Any?): Int? {
    val values = when (raw) {
        is IntArray -> raw.toList()
        is Array<*> -> raw.mapNotNull { it?.toString()?.toIntOrNull() }
        is List<*> -> raw.mapNotNull { it?.toString()?.toIntOrNull() }
        is CharSequence -> Regex("-?\\d+").findAll(raw).map { it.value.toInt() }.toList()
        else -> return null
    }
    if (values.isEmpty()) return null

    val pairCount = values.first()
    if (pairCount in 1..10) {
        var index = 1
        repeat(pairCount) {
            if (index + 1 >= values.size) return@repeat
            val key = values[index]
            val value = values[index + 1]
            index += 2
            if (key == BATTERY_KEY) return value.toHfpPercent()
        }
    }

    values.windowed(2).firstNotNullOfOrNull { (key, value) ->
        if (key == BATTERY_KEY) value.toHfpPercent() else null
    }?.let { return it }

    return null
}

private fun Int.toHfpPercent(): Int? =
    takeIf { it in 0..9 }?.let { (it + 1) * 10 }

internal fun parseXEventBattery(raw: Any?): Int? {
    val values = when (raw) {
        is IntArray -> listOf("BATTERY") + raw.map { it.toString() }
        is Array<*> -> raw.toList()
        is List<*> -> raw
        is CharSequence -> raw.toString().split(',').map { it.trim() }
        else -> return null
    }
    if (values.size < 3) return null
    if (values.first()?.toString()?.equals("BATTERY", ignoreCase = true) != true) return null

    val level = values.getOrNull(1)?.toString()?.toIntOrNull() ?: return null
    val numberOfLevels = values.getOrNull(2)?.toString()?.toIntOrNull() ?: return null
    if (level < 0 || numberOfLevels <= 0 || level > numberOfLevels) return null
    if (numberOfLevels == 1) return if (level == 1) 100 else 0
    return (level * 100 / numberOfLevels).takeIf { it in 0..100 }
}

private const val BATTERY_KEY = 1
