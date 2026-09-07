package com.galaxyairpods.data.bluetooth

/**
 * Parses the standard Apple HFP vendor event used by some AirPods and
 * AirPods-compatible headsets when their BLE advertisement is unavailable.
 *
 * The event is normally encoded as [pairCount, key, value, ...]. Key 1 is the
 * battery value and its 0..9 payload maps to 10..100 percent. Android OEMs
 * expose the arguments as String[], Int[], or ArrayList<String>, so this
 * parser deliberately accepts all of those representations.
 */
internal fun parseIphoneAccessoryBattery(raw: Any?): Int? {
    val values = when (raw) {
        is IntArray -> raw.toList()
        is Array<*> -> raw.mapNotNull { it?.toString()?.toIntOrNull() }
        is List<*> -> raw.mapNotNull { it?.toString()?.toIntOrNull() }
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

    // A few Bluetooth stacks strip the pair count. Keep the fallback strict:
    // only a key-1/value pair can produce a battery number.
    values.windowed(2).firstNotNullOfOrNull { (key, value) ->
        if (key == BATTERY_KEY) value.toHfpPercent() else null
    }?.let { return it }

    return null
}

private fun Int.toHfpPercent(): Int? =
    takeIf { it in 0..9 }?.let { (it + 1) * 10 }

private const val BATTERY_KEY = 1
