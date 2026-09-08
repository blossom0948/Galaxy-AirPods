package com.galaxyairpods.data

import com.galaxyairpods.data.bluetooth.AapBatteryProtocol
import com.galaxyairpods.data.bluetooth.AapFrame
import com.galaxyairpods.data.bluetooth.AapFrameAccumulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AapFrameAccumulatorTest {
    @Test
    fun joinsPartialReadsAndEmitsConcatenatedKnownFrames() {
        val accumulator = AapFrameAccumulator()
        val connect = ByteArray(18).also {
            it[0] = 0x01
            it[2] = 0x04
            it[4] = 0x00
        }
        val battery = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x04, 0x00,
            0x01,
            0x04, 0x00, 0x50, 0x02, 0x00,
        )

        assertEquals(0, accumulator.append(connect.copyOfRange(0, 5)).frames.size)
        val result = accumulator.append(connect.copyOfRange(5, connect.size) + battery)

        assertEquals(2, result.frames.size)
        assertTrue(accumulatorWasConnectResponse(result.frames[0]))
        val message = AapBatteryProtocol.parseFrame(result.frames[1])
        assertTrue(message is AapFrame.Message)
        assertEquals(AapBatteryProtocol.BATTERY_COMMAND, (message as AapFrame.Message).command)
    }

    @Test
    fun rejectsAnUnboundedBatteryComponentCount() {
        val accumulator = AapFrameAccumulator()
        val malformed = byteArrayOf(0x04, 0x00, 0x04, 0x00, 0x04, 0x00, 0x7F)

        val result = accumulator.append(malformed)

        assertTrue(result.frames.isEmpty())
        assertTrue(result.malformedRecords > 0)
    }

    @Test
    fun preservesAnUnknownRecordBoundaryWithoutByteScanning() {
        val accumulator = AapFrameAccumulator()
        val unknownNotification = ByteArray(153).also {
            it[0] = 0x04
            it[2] = 0x04
            it[4] = 0x2B
        }
        val battery = byteArrayOf(
            0x04, 0x00, 0x04, 0x00,
            0x04, 0x00,
            0x01,
            0x04, 0x00, 0x50, 0x02, 0x00,
        )

        val unknown = accumulator.append(unknownNotification)
        assertEquals(1, unknown.frames.size)
        assertEquals(0, unknown.malformedRecords)
        assertTrue(AapBatteryProtocol.parseFrame(unknown.frames.single()) is AapFrame.Message)

        val valid = accumulator.append(battery)
        assertEquals(1, valid.frames.size)
        assertEquals(0, valid.malformedRecords)
        assertTrue(AapBatteryProtocol.parseFrame(valid.frames.single()) is AapFrame.Message)
    }

    private fun accumulatorWasConnectResponse(frame: ByteArray): Boolean =
        frame.size == 18 && frame[0].toInt() == 0x01
}
