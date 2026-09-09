package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AapBatteryProtocol
import com.galaxyairpods.data.bluetooth.AapFrame
import com.galaxyairpods.data.bluetooth.AapEarStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapBatteryProtocolTest {
    @Test
    fun parsesExactLeftRightAndCaseValues() {
        val frame = message(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00,
            0x03,
            0x04, 0x00, 0x62, 0x02, 0x00,
            0x02, 0x00, 0x4D, 0x01, 0x00,
            0x08, 0x00, 0x58, 0x02, 0x00,
        )

        val parsedFrame = AapBatteryProtocol.parseFrame(frame) as AapFrame.Message
        val parsed = AapBatteryProtocol.parseBattery(parsedFrame)

        assertNotNull(parsed)
        assertEquals(98, parsed?.left?.percent)
        assertEquals(77, parsed?.right?.percent)
        assertEquals(88, parsed?.case?.percent)
        assertEquals(false, parsed?.left?.charging)
        assertEquals(true, parsed?.right?.charging)
        assertEquals(3, parsed?.componentCount)
    }

    @Test
    fun rejectsWrongPayloadLengthAndInvalidPercent() {
        val truncated = message(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01, 0x04, 0x00)
        val invalidPercent = message(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
            0x04, 0x00, 0x7F, 0x02, 0x00,
        )

        assertNull(AapBatteryProtocol.parseBattery(AapBatteryProtocol.parseFrame(truncated) as AapFrame.Message))
        assertNull(AapBatteryProtocol.parseBattery(AapBatteryProtocol.parseFrame(invalidPercent) as AapFrame.Message))
    }

    @Test
    fun ignoresDisconnectedComponentInsteadOfPromotingZeroToBattery() {
        val frame = message(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x03,
            0x04, 0x01, 0x00, 0x04, 0x01,
            0x02, 0x01, 0x62, 0x02, 0x01,
            0x08, 0x01, 0x44, 0x02, 0x01,
        )

        val parsed = AapBatteryProtocol.parseBattery(
            AapBatteryProtocol.parseFrame(frame) as AapFrame.Message,
        )

        assertNotNull(parsed)
        assertNull(parsed?.left)
        assertEquals(98, parsed?.right?.percent)
        assertEquals(68, parsed?.case?.percent)
    }

    @Test
    fun doesNotCopySingleUnknownComponentToEitherEar() {
        val frame = message(
            0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x01,
            0x01, 0x00, 0x50, 0x02, 0x00,
        )

        val parsed = AapBatteryProtocol.parseBattery(AapBatteryProtocol.parseFrame(frame) as AapFrame.Message)

        assertNull(parsed)
    }

    @Test
    fun handshakeAndNotificationProfilesHaveExpectedWireShape() {
        assertEquals(16, AapBatteryProtocol.handshake.size)
        assertEquals(0x00, AapBatteryProtocol.handshake[0].toInt())
        assertEquals(0x01, AapBatteryProtocol.handshake[4].toInt())
        assertEquals(14, AapBatteryProtocol.featureFlags.size)
        assertEquals(
            listOf(0x4D, 0x00, 0xD7, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00),
            AapBatteryProtocol.featureFlags.drop(4).map { it.toInt() and 0xFF },
        )
        assertEquals(3, AapBatteryProtocol.notificationProfiles.size)
        assertEquals(2, AapBatteryProtocol.legacyPreflightNotificationProfiles.size)
        assertTrue(AapBatteryProtocol.notificationProfiles.all { it.second.size == 10 })
        assertEquals(
            listOf("EF_COMPAT", "FF"),
            AapBatteryProtocol.legacyPreflightNotificationProfiles.map { it.first },
        )
        assertEquals(
            listOf(
                listOf(0xFF, 0xFF, 0xFE, 0xFF),
                listOf(0xFF, 0xFF, 0xEF, 0xFF),
                listOf(0xFF, 0xFF, 0xFF, 0xFF),
            ),
            AapBatteryProtocol.notificationProfiles.map { (_, bytes) ->
                bytes.takeLast(4).map { it.toInt() and 0xFF }
            },
        )
    }

    @Test
    fun parsesStrictEarDetectionPayload() {
        val frame = message(
            0x04, 0x00, 0x04, 0x00, 0x06, 0x00,
            0x00, 0x01,
        )

        val parsed = AapBatteryProtocol.parseEarDetection(
            AapBatteryProtocol.parseFrame(frame) as AapFrame.Message,
        )

        assertEquals(AapEarStatus.IN_EAR, parsed?.primary)
        assertEquals(AapEarStatus.OUT_OF_EAR, parsed?.secondary)
    }

    @Test
    fun rejectsEarDetectionWithUnknownStatusOrExtraBytes() {
        val unknownStatus = message(
            0x04, 0x00, 0x04, 0x00, 0x06, 0x00,
            0x00, 0x04,
        )
        val extraByte = message(
            0x04, 0x00, 0x04, 0x00, 0x06, 0x00,
            0x00, 0x01, 0x00,
        )

        assertNull(
            AapBatteryProtocol.parseEarDetection(
                AapBatteryProtocol.parseFrame(unknownStatus) as AapFrame.Message,
            ),
        )
        assertNull(
            AapBatteryProtocol.parseEarDetection(
                AapBatteryProtocol.parseFrame(extraByte) as AapFrame.Message,
            ),
        )
    }

    @Test
    fun acceptsObservedDisconnectedPodStatusAsKnownNonInEarState() {
        val frame = message(
            0x04, 0x00, 0x04, 0x00, 0x06, 0x00,
            0x00, 0x03,
        )

        val parsed = AapBatteryProtocol.parseEarDetection(
            AapBatteryProtocol.parseFrame(frame) as AapFrame.Message,
        )

        assertEquals(AapEarStatus.IN_EAR, parsed?.primary)
        assertEquals(AapEarStatus.DISCONNECTED, parsed?.secondary)
    }

    private fun message(vararg values: Int): ByteArray =
        values.map { it.toByte() }.toByteArray()
}
