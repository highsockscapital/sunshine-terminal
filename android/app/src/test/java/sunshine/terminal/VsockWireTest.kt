package sunshine.terminal

import org.junit.Assert.*
import org.junit.Test

class VsockWireTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "")
        return ByteArray(clean.length / 2) { i ->
            clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }

    @Test fun goldenVectorMatchesGuestAgent() {
        // Shared with guest/sunshine-vsock-agent.py --selftest (CI runs both).
        val golden = hex("0000000F 00000000 00000003 68690A 00000000")
        val enc = VsockResponseCodec.encode(0, "hi\n", "")
        assertArrayEquals(golden, enc)
        val dec = VsockResponseCodec.decode(golden)
        assertEquals(0, dec.exitCode)
        assertEquals("hi\n", dec.stdout)
        assertEquals("", dec.stderr)
    }

    @Test fun roundTripPreservesNulAndUnicode() {
        val out = "a\u0000b ☀ line\n"
        val err = "warn: x"
        val dec = VsockResponseCodec.decode(VsockResponseCodec.encode(3, out, err))
        assertEquals(3, dec.exitCode)
        assertEquals(out, dec.stdout)
        assertEquals(err, dec.stderr)
    }

    @Test fun malformedRejected() {
        for (bad in listOf(
            byteArrayOf(0, 0),
            // Declared length longer than the buffer.
            hex("000000FF 00000000 00000000 00000000 00000000"),
        )) {
            try {
                VsockResponseCodec.decode(bad)
                fail("accepted malformed response")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun hybridPrefersVsockFallsBackToSsh() = kotlinx.coroutines.runBlocking {
        val viaVsock = TransportResult(true, stdout = "vsock-out")
        val viaSsh = TransportResult(true, stdout = "ssh-out")
        var primaryCalls = 0
        var fallbackCalls = 0
        fun stub(result: TransportResult, counter: () -> Unit) = object : GuestTransport {
            override val stdout = kotlinx.coroutines.flow.emptyFlow<ChannelLine>()
            override val thermal = kotlinx.coroutines.flow.emptyFlow<ThermalSnapshot>()
            override val connection = kotlinx.coroutines.flow.emptyFlow<ConnectionState>()
            override suspend fun execFrame(frame: ByteArray, blockId: Long, timeoutMs: Long): TransportResult {
                counter()
                return result
            }
            override suspend fun ping() = true
        }
        val frame = VsockFrameMultiplexer.encode(1L, "t", "human", "echo hi")

        // Agent answers → primary serves.
        val hybridUp = HybridGuestTransport(
            stub(viaVsock) { primaryCalls += 1 },
            { true },
            stub(viaSsh) { fallbackCalls += 1 },
        )
        assertEquals("vsock-out", hybridUp.execFrame(frame, 1L).stdout)
        assertEquals(1, primaryCalls)
        assertEquals(0, fallbackCalls)

        // Agent silent → SSH fallback serves.
        val hybridDown = HybridGuestTransport(
            stub(viaVsock) { primaryCalls += 1 },
            { false },
            stub(viaSsh) { fallbackCalls += 1 },
        )
        assertEquals("ssh-out", hybridDown.execFrame(frame, 1L).stdout)
        assertEquals(1, primaryCalls)
        assertEquals(1, fallbackCalls)
    }
}
