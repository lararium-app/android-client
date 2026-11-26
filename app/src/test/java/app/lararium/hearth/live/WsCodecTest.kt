package app.lararium.hearth.live

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

/**
 * Frame codec tests. The masking vector is the RFC 6455 §5.7 example:
 * unmasked "Hello" with key 0x37fa213d masks to 7f 9f 4d 51 58.
 */
class WsCodecTest {

    private val rfcMaskKey = byteArrayOf(0x37, 0xfa.toByte(), 0x21, 0x3d)

    @Test
    fun encodeTextMatchesRfcExample() {
        val frame = WsCodec.encodeText("Hello", rfcMaskKey)
        val expected = byteArrayOf(
            0x81.toByte(),
            0x85.toByte(),
            0x37, 0xfa.toByte(), 0x21, 0x3d,
            0x7f.toByte(), 0x9f.toByte(), 0x4d, 0x51, 0x58
        )
        assertArrayEquals(expected, frame)
    }

    @Test
    fun encodeUsesTwoByteLengthOver125() {
        val payload = ByteArray(200) { (it % 251).toByte() }
        val frame = WsCodec.encode(WsCodec.OP_TEXT, payload, rfcMaskKey)
        assertEquals(0x81.toByte(), frame[0])
        assertEquals((0x80 or 126).toByte(), frame[1])
        assertEquals(0x00, frame[2].toInt())
        assertEquals(200, frame[3].toInt() and 0xFF)
        val masked = frame.copyOfRange(8, frame.size)
        for (i in payload.indices) {
            assertEquals(
                (payload[i].toInt() xor rfcMaskKey[i % 4].toInt()).toByte(),
                masked[i]
            )
        }
    }

    @Test
    fun encodeUsesEightByteLengthOver65535() {
        val payload = ByteArray(70_000)
        val frame = WsCodec.encode(WsCodec.OP_TEXT, payload, rfcMaskKey)
        assertEquals((0x80 or 127).toByte(), frame[1])
        val len = (2..9).fold(0L) { acc, i -> (acc shl 8) or (frame[i].toLong() and 0xFF) }
        assertEquals(70_000L, len)
    }

    @Test
    fun encodeCloseCarriesStatusCode() {
        val frame = WsCodec.encodeClose(1001, rfcMaskKey)
        assertEquals(0x88.toByte(), frame[0])
        assertEquals((0x80 or 2).toByte(), frame[1])
        // payload masked: 1001 = 0x03 0xe9
        val p0 = (frame[6].toInt() xor 0x37) and 0xFF
        val p1 = (frame[7].toInt() xor 0xfa) and 0xFF
        assertEquals(1001, (p0 shl 8) or p1)
    }

    private fun serverFrame(fin: Boolean, opcode: Int, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(if (fin) 0x80 or opcode else opcode)
        when {
            payload.size < 126 -> out.write(payload.size)
            payload.size <= 0xFFFF -> {
                out.write(126)
                out.write((payload.size ushr 8) and 0xFF)
                out.write(payload.size and 0xFF)
            }
            else -> {
                out.write(127)
                for (i in 7 downTo 0) out.write(((payload.size.toLong() ushr (8 * i)) and 0xFF).toInt())
            }
        }
        out.write(payload)
        return out.toByteArray()
    }

    @Test
    fun readerReadsTextFrame() {
        val bytes = serverFrame(true, 0x1, "hi".toByteArray())
        val frame = WsFrameReader(ByteArrayInputStream(bytes)).readMessage { fail("pong unexpected") }
        assertEquals(WsCodec.OP_TEXT, frame.opcode)
        assertEquals("hi", String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun readerReassemblesFragments() {
        val bytes = serverFrame(false, 0x1, "ab".toByteArray()) +
            serverFrame(false, 0x0, "cd".toByteArray()) +
            serverFrame(true, 0x0, "ef".toByteArray())
        val frame = WsFrameReader(ByteArrayInputStream(bytes)).readMessage { fail("pong unexpected") }
        assertEquals(WsCodec.OP_TEXT, frame.opcode)
        assertEquals("abcdef", String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun readerAnswersPingInsideFragmentedMessage() {
        var pong: ByteArray? = null
        val bytes = serverFrame(false, 0x1, "ab".toByteArray()) +
            serverFrame(true, 0x9, "p1".toByteArray()) +
            serverFrame(true, 0x0, "cd".toByteArray())
        val frame = WsFrameReader(ByteArrayInputStream(bytes)).readMessage { pong = it }
        assertArrayEquals("p1".toByteArray(), pong)
        assertEquals("abcd", String(frame.payload, Charsets.UTF_8))
    }

    @Test
    fun readerRejectsMaskedServerFrame() {
        val frame = serverFrame(true, 0x1, "x".toByteArray())
        frame[1] = (frame[1].toInt() or 0x80).toByte()
        try {
            WsFrameReader(ByteArrayInputStream(frame)).readFrame()
            fail("masked frame from server must throw")
        } catch (e: WsCodec.ProtocolError) {
            assertTrue(e.message!!.contains("masked"))
        }
    }

    @Test
    fun readerRejectsOversizedFrame() {
        val header = byteArrayOf(0x81.toByte(), 0x7F, 0, 0, 0, 0, 0x10, 0, 0, 0) // 1 MiB + 1
        try {
            WsFrameReader(ByteArrayInputStream(header)).readFrame()
            fail("oversized frame must throw")
        } catch (e: WsCodec.ProtocolError) {
            assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test
    fun readerRejectsFragmentedControlFrame() {
        val bytes = serverFrame(false, 0x9, "x".toByteArray())
        try {
            WsFrameReader(ByteArrayInputStream(bytes)).readFrame()
            fail("fragmented ping must throw")
        } catch (e: WsCodec.ProtocolError) {
            assertTrue(e.message!!.contains("control"))
        }
    }

    @Test
    fun readerSurfacesCloseFrame() {
        val bytes = serverFrame(true, 0x8, byteArrayOf(0x03.toByte(), 0xeb.toByte()))
        val frame = WsFrameReader(ByteArrayInputStream(bytes)).readMessage { fail("pong unexpected") }
        assertEquals(WsCodec.OP_CLOSE, frame.opcode)
        assertEquals(1003, ((frame.payload[0].toInt() and 0xFF) shl 8) or (frame.payload[1].toInt() and 0xFF))
    }

    @Test
    fun readerThrowsEofOnEmptyStream() {
        try {
            WsFrameReader(ByteArrayInputStream(ByteArray(0))).readFrame()
            fail("empty stream must throw EOF")
        } catch (_: EOFException) {
        }
    }
}
