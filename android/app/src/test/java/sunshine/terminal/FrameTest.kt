package sunshine.terminal

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FrameTest {

    @Test fun frameRoundTrip() {
        val channel = VsockGuestChannel(FakeTransport(), stateDir = null)
        val frame = VsockFrameMultiplexer.encode(7L, "tok", "agent", "ls -la")
        val buf = java.nio.ByteBuffer.wrap(frame).order(java.nio.ByteOrder.BIG_ENDIAN)
        val len = buf.int
        assertEquals(frame.size - 4, len)
        val bid = buf.long
        assertEquals(7L, bid)
        val payload = ByteArray(frame.size - 12).also { buf.get(it) }
        val d = VsockFrameMultiplexer.decodePayload(bid, payload)
        assertEquals("tok", d.token)
        assertEquals("agent", d.origin)
        assertEquals("ls -la", d.command)
        assertNotNull(channel)
    }

    @Test fun lsEscapeDecoding() {
        val channel = VsockGuestChannel(FakeTransport(), stateDir = null)
        assertEquals("a\nb", channel.decodeLsEscape("a\\nb"))
        assertEquals("a\\b", channel.decodeLsEscape("a\\\\b"))
        assertEquals("plain", channel.decodeLsEscape("plain"))
    }

    @Test fun auditHashAndRedact() {
        val h = commandHash("rm -rf /tmp/x --token s3cr3t")
        assertEquals(16, h.length)
        assertEquals(h, commandHash("rm -rf /tmp/x --token s3cr3t"))
        val red = redactSecrets("run --token abc123 password=hunter2")
        assertFalse(red.contains("abc123"))
        assertFalse(red.contains("hunter2"))
        assertTrue(red.contains("[redacted]"))
    }

    @Test fun markdownBlocks() {
        val blocks = parseBlocks(
            listOf("# H", "", "- [x] done", "| a | b |", "|---|---|", "| 1 | 2 |", "```kt", "x", "```"),
        )
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 1 })
        assertTrue(blocks.any { it is MdBlock.ListBlock })
        assertTrue(blocks.any { it is MdBlock.Table })
        assertTrue(blocks.any { it is MdBlock.CodeFence })
        assertEquals(4000, INLINE_BUDGET)
    }

    // Minimal transport stub — never touched by these pure-function tests.
    private class FakeTransport : GuestTransport {
        override suspend fun execFrame(frame: ByteArray, blockId: Long, timeoutMs: Long) =
            TransportResult(false, reason = "stub")
        override suspend fun ping() = false
    }
}
