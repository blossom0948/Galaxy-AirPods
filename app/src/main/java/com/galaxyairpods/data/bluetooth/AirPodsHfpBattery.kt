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

    // A few Bluetooth stacks strip the pair count. Keep the fallback strict:
    // only a key-1/value pair can produce a battery number.
    values.windowed(2).firstNotNullOfOrNull { (key, value) ->
        if (key == BATTERY_KEY) value.toHfpPercent() else null
    }?.let { return it }

    return null
}

private fun Int.toHfpPercent(): Int? =
    takeIf { it in 0..9 }?.let { (it + 1) * 10 }

/**
 * Parses Android's other standard HFP battery event:
 * +XEVENT: BATTERY,[level],[numberOfLevels],...
 *
 * The framework converts the vendor arguments differently across Android and
 * OEM Bluetooth stacks, so this accepts String[], primitive arrays and Lists.
 */
internal fun parseXEventBattery(raw: Any?): Int? {
    val values = when (raw) {
        // A few OEMs strip the event name when marshalling primitive arrays;
        // this function is only called for an XEVENT command, so restore it.
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
    // Android Bluetooth stacks have used both conventions for this field:
    // it is normally the maximum level (0..N), while a few newer branches
    // describe N+1 discrete values. The public AOSP parser uses N; accepting
    // the 1/1 edge case keeps a full battery valid on both variants.
    if (level < 0 || numberOfLevels <= 0 || level > numberOfLevels) return null
    if (numberOfLevels == 1) return if (level == 1) 100 else 0
    return (level * 100 / numberOfLevels).takeIf { it in 0..100 }
}

private const val BATTERY_KEY = 1
