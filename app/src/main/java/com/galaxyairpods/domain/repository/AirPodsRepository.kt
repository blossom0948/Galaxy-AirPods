package com.galaxyairpods.domain.repository

import com.galaxyairpods.domain.model.AirPodsState
import com.galaxyairpods.domain.model.ParsedAirPodsPacket
import kotlinx.coroutines.flow.StateFlow

interface AirPodsRepository {
    val state: StateFlow<AirPodsState>

    suspend fun applyParsedPacket(
        deviceId: String,
        packet: ParsedAirPodsPacket,
        seenAt: Long = System.currentTimeMillis(),
        wearDetectionEnabled: Boolean = true,
        deviceProfileId: String? = null,
        capturedAtElapsedMs: Long? = null,
    )
}
