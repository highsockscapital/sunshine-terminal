// Sunshine vsock wire protocol — shared by VsockSocketTransport (host) and
// sunshine-vsock-agent.py (guest). Version 1.
//
// Request (host → guest) reuses VsockFrameMultiplexer exactly (no change):
//   [u32 BE totalLen][u64 BE blockId][payload "token\norigin\ncommand"]
// One request per connection; the guest closes after replying, so no
// multiplexing is needed on the socket.
//
// Response (guest → host):
//   [u32 BE totalLen][i32 BE exitCode][u32 BE outLen][stdout][u32 BE errLen][stderr]
//   totalLen = 4 + 4 + outLen + 4 + errLen
// Strings are raw UTF-8 bytes (may contain NUL — hence explicit lengths).
//
// Golden vector (mirrored in the agent's --selftest and VsockWireTest):
//   exitCode=0, stdout="hi\n", stderr="" →
//   00 00 00 0F 00 00 00 00 00 00 00 03 68 69 0A 00 00 00 00
package sunshine.terminal

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Default vsock port the guest agent listens on (guest CID from config). */
const val VSOCK_AGENT_PORT = 5000

/** Hard cap on a single response frame (8 MiB — same class as the ring buffer). */
const val VSOCK_FRAME_MAX_BYTES = 8 * 1024 * 1024

data class VsockResponse(val exitCode: Int, val stdout: String, val stderr: String)

object VsockResponseCodec {
    fun encode(exitCode: Int, stdout: String, stderr: String): ByteArray {
        val out = stdout.toByteArray(Charsets.UTF_8)
        val err = stderr.toByteArray(Charsets.UTF_8)
        val total = 4 + 4 + out.size + 4 + err.size
        val buf = ByteBuffer.allocate(4 + total).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(total)
        buf.putInt(exitCode)
        buf.putInt(out.size)
        buf.put(out)
        buf.putInt(err.size)
        buf.put(err)
        return buf.array()
    }

    fun decode(bytes: ByteArray): VsockResponse {
        require(bytes.size >= 4) { "response-too-short" }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val total = buf.int
        require(total >= 12) { "response-header-too-short" }
        require(total <= VSOCK_FRAME_MAX_BYTES) { "response-too-large" }
        require(bytes.size - 4 >= total) { "response-truncated" }
        val exitCode = buf.int
        val outLen = buf.int
        require(outLen >= 0 && outLen <= total) { "response-bad-outlen" }
        val out = ByteArray(outLen).also { buf.get(it) }
        val errLen = buf.int
        require(errLen >= 0 && 4 + 4 + outLen + 4 + errLen <= total) { "response-bad-errlen" }
        val err = ByteArray(errLen).also { buf.get(it) }
        return VsockResponse(exitCode, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
}
