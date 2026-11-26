package app.lararium.hearth.live

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/**
 * Minimal RFC 6455 frame codec for the single JSON-RPC WebSocket the app holds
 * against the hearth (/v1/ws). Text frames carry JSON-RPC; ping/pong keep the
 * link alive across idle proxies; close carries the daemon's status codes.
 *
 * Pure JVM: no Android imports, no coroutines — fully unit-testable.
 */
class WsFrame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

/** Client-to-server frames are always masked (RFC 6455 §5.3). */
object WsCodec {
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    /** Mirrors the daemon's maxWSFramePayload (internal/surface/rpc.go): 1 MiB. */
    const val MAX_MESSAGE_BYTES = 1 shl 20

    const val MAX_CONTROL_BYTES = 125

    class ProtocolError(message: String) : IOException(message)

    fun encode(opcode: Int, payload: ByteArray, maskKey: ByteArray): ByteArray {
        require(maskKey.size == 4) { "mask key must be 4 bytes" }
        require(opcode in 0x1..0xF) { "invalid opcode" }
        val header = ArrayList<ByteArray>(14)
        header.add(byteArrayOf((0x80 or opcode).toByte()))
        when {
            payload.size < 126 -> header.add(byteArrayOf((0x80 or payload.size).toByte()))
            payload.size <= 0xFFFF -> {
                header.add(byteArrayOf((0x80 or 126).toByte()))
                header.add(
                    byteArrayOf(
                        ((payload.size ushr 8) and 0xFF).toByte(),
                        (payload.size and 0xFF).toByte()
                    )
                )
            }
            else -> {
                header.add(byteArrayOf((0x80 or 127).toByte()))
                val n = payload.size.toLong()
                val len = ByteArray(8)
                for (i in 0 until 8) len[i] = ((n ushr (8 * (7 - i))) and 0xFF).toByte()
                header.add(len)
            }
        }
        header.add(maskKey)
        val masked = ByteArray(payload.size)
        for (i in payload.indices) {
            masked[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
        }
        header.add(masked)
        return header.fold(ByteArray(0)) { acc, part -> acc + part }
    }

    fun encodeText(text: String, maskKey: ByteArray): ByteArray =
        encode(OP_TEXT, text.toByteArray(Charsets.UTF_8), maskKey)

    fun encodeClose(code: Int, maskKey: ByteArray): ByteArray {
        val payload = byteArrayOf(
            ((code ushr 8) and 0xFF).toByte(),
            (code and 0xFF).toByte()
        )
        return encode(OP_CLOSE, payload, maskKey)
    }

    fun newMaskKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(4)
        random.nextBytes(key)
        return key
    }
}

/**
 * Reads unmasked server frames from [input]. Server frames must never carry a
 * mask bit; anything else is a protocol error and kills the connection.
 */
class WsFrameReader(private val input: InputStream) {

    private val data = if (input is DataInputStream) input else DataInputStream(input)

    /** @throws java.io.EOFException when the peer closed cleanly mid-frame. */
    @Throws(IOException::class)
    fun readFrame(): WsFrame {
        val b0 = data.read()
        if (b0 == -1) throw EOFException("websocket stream ended")
        val b1 = data.read()
        if (b1 == -1) throw EOFException("websocket frame header truncated")

        val fin = b0 and 0x80 != 0
        val rsv = b0 and 0x70
        if (rsv != 0) throw WsCodec.ProtocolError("websocket RSV bits set")
        val opcode = b0 and 0x0F
        val masked = b1 and 0x80 != 0
        if (masked) throw WsCodec.ProtocolError("masked frame from server")

        var len = (b1 and 0x7F).toLong()
        when (len.toInt()) {
            126 -> len = data.readUnsignedShort().toLong()
            127 -> {
                len = data.readLong()
                if (len < 0) throw WsCodec.ProtocolError("websocket length overflow")
            }
        }
        val control = opcode and 0x8 != 0
        if (control && (len > WsCodec.MAX_CONTROL_BYTES || !fin)) {
            throw WsCodec.ProtocolError("invalid control frame")
        }
        if (len > WsCodec.MAX_MESSAGE_BYTES) {
            throw WsCodec.ProtocolError("websocket frame too large")
        }
        val payload = ByteArray(len.toInt())
        data.readFully(payload)
        return WsFrame(fin, opcode, payload)
    }

    /**
     * Reads one complete data message, reassembling continuation fragments.
     * Interleaved ping frames are answered via [replyPong] (payload echoed);
     * close frames return a [WsFrame] with [WsCodec.OP_CLOSE] to the caller.
     */
    fun readMessage(replyPong: (ByteArray) -> Unit): WsFrame {
        var dataOpcode = -1
        var buffer = ByteArray(0)
        while (true) {
            val frame = readFrame()
            when (frame.opcode) {
                WsCodec.OP_TEXT, WsCodec.OP_BINARY -> {
                    if (dataOpcode != -1) {
                        throw WsCodec.ProtocolError("data frame during fragmentation")
                    }
                    if (frame.fin) return frame
                    dataOpcode = frame.opcode
                    buffer = frame.payload
                }
                WsCodec.OP_CONTINUATION -> {
                    if (dataOpcode == -1) {
                        throw WsCodec.ProtocolError("continuation without start")
                    }
                    if (buffer.size + frame.payload.size > WsCodec.MAX_MESSAGE_BYTES) {
                        throw WsCodec.ProtocolError("websocket message too large")
                    }
                    buffer += frame.payload
                    if (frame.fin) return WsFrame(true, dataOpcode, buffer)
                }
                WsCodec.OP_PING -> replyPong(frame.payload)
                WsCodec.OP_PONG -> { /* liveness echo, consumed */ }
                WsCodec.OP_CLOSE -> return frame
                else -> throw WsCodec.ProtocolError("unknown opcode ${frame.opcode}")
            }
        }
    }
}
