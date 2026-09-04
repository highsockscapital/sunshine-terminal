// Sunshine VsockSocketTransport — AF_VSOCK exec transport (no ssh, no IP).
//
// Host connects to (guestCid, VSOCK_AGENT_PORT) via the public SDK:
// Os.socket(AF_VSOCK) + Os.connect(VmSocketAddress) — API 31+, minSdk is 34.
// Kernel-memory buffers, no network stack, no sshd, no keys, no INTERNET
// needed for this path. Speaks the same frame protocol as the SSH path
// (VsockFrameMultiplexer request, VsockResponseCodec reply) so the guest
// agent and host stay in lockstep.
//
// HybridGuestTransport (below) prefers vsock and falls back to SSH: SSH is
// bootstrap-only (provisioning pushes files over it until the agent is
// baked into the image), vsock is steady-state.
package sunshine.terminal

import android.system.Os
import android.system.OsConstants
import android.system.VmSocketAddress
import java.io.FileDescriptor
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class VsockSocketTransport(
    private val cid: Int = VSOCK_DEFAULT_CID,
    private val port: Int = VSOCK_AGENT_PORT,
    private val connectTimeoutMs: Long = 3_000L,
) : GuestTransport {

    private val current = AtomicReference<FileDescriptor?>(null)

    private suspend fun connect(): FileDescriptor = withContext(Dispatchers.IO) {
        val fd = Os.socket(OsConstants.AF_VSOCK, OsConstants.SOCK_STREAM, 0)
        try {
            withTimeout(connectTimeoutMs) {
                withContext(Dispatchers.IO) {
                    Os.connect(fd, VmSocketAddress(cid, port))
                }
            }
            current.set(fd)
            fd
        } catch (e: Exception) {
            try {
                Os.close(fd)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    private fun writeFully(fd: FileDescriptor, bytes: ByteArray) {
        var off = 0
        while (off < bytes.size) {
            val n = Os.write(fd, bytes, off, bytes.size - off)
            if (n <= 0) throw java.io.IOException("vsock-write-eof")
            off += n
        }
    }

    private fun readFully(fd: FileDescriptor, n: Int): ByteArray {
        require(n in 0..VSOCK_FRAME_MAX_BYTES) { "vsock-bad-length" }
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = Os.read(fd, out, off, n - off)
            if (r <= 0) throw java.io.IOException("vsock-read-eof")
            off += r
        }
        return out
    }

    private fun closeQuietly(fd: FileDescriptor?) {
        if (fd == null || !fd.valid()) return
        current.compareAndSet(fd, null)
        try {
            Os.close(fd)
        } catch (_: Exception) {
        }
    }

    /** Lightweight reachability probe (connect + close). Never throws. */
    suspend fun probe(): Boolean = withContext(Dispatchers.IO) {
        var fd: FileDescriptor? = null
        try {
            fd = connect()
            true
        } catch (_: Exception) {
            false
        } finally {
            closeQuietly(fd)
        }
    }

    override suspend fun ping(): Boolean = probe()

    override suspend fun cancel(): Boolean {
        closeQuietly(current.getAndSet(null))
        return true
    }

    override suspend fun execFrame(frame: ByteArray, blockId: Long, timeoutMs: Long): TransportResult =
        withContext(Dispatchers.IO) {
            var fd: FileDescriptor? = null
            try {
                fd = try {
                    connect()
                } catch (e: Exception) {
                    val reason = if (e is SocketTimeoutException ||
                        e is TimeoutCancellationException
                    ) {
                        "vsock-connect-timeout"
                    } else {
                        "vsock-unavailable"
                    }
                    return@withContext TransportResult(false, reason = reason)
                }
                val f = fd ?: return@withContext TransportResult(false, reason = "vsock-unavailable")
                try {
                    withTimeout(timeoutMs) {
                        withContext(Dispatchers.IO) {
                            writeFully(f, frame)
                            val lenBytes = readFully(f, 4)
                            val total = java.nio.ByteBuffer.wrap(lenBytes)
                                .order(java.nio.ByteOrder.BIG_ENDIAN).int
                            if (total < 12 || total > VSOCK_FRAME_MAX_BYTES) {
                                throw java.io.IOException("vsock-bad-response-len")
                            }
                            val body = readFully(f, total)
                            val res = VsockResponseCodec.decode(lenBytes + body)
                            TransportResult(
                                res.exitCode == 0,
                                stdout = res.stdout,
                                stderr = res.stderr,
                                exitCode = res.exitCode,
                            )
                        }
                    }
                } catch (e: Exception) {
                    TransportResult(false, reason = e.message ?: "vsock-exec-failed")
                } finally {
                    closeQuietly(f)
                }
            } finally {
                current.compareAndSet(fd, null)
            }
        }
}

/**
 * Prefers [primary] (vsock) when [primaryReachable] says the guest agent
 * answers, otherwise uses [fallback] (SSH). Lifecycle (status/boot/
 * provision) always delegates to [fallback], which owns files + tokens —
 * except the ssh-missing gate, which is waived when vsock answers (see
 * SshGuestTransport's vsockProbe hook).
 */
class HybridGuestTransport(
    private val primary: GuestTransport,
    private val primaryReachable: suspend () -> Boolean,
    private val fallback: GuestTransport,
) : GuestTransport {

    override suspend fun execFrame(frame: ByteArray, blockId: Long, timeoutMs: Long): TransportResult {
        val usePrimary = try {
            primaryReachable()
        } catch (_: Exception) {
            false
        }
        return if (usePrimary) {
            try {
                primary.execFrame(frame, blockId, timeoutMs)
            } catch (_: Exception) {
                fallback.execFrame(frame, blockId, timeoutMs)
            }
        } else {
            fallback.execFrame(frame, blockId, timeoutMs)
        }
    }

    override suspend fun ping(): Boolean {
        return try {
            if (primaryReachable()) primary.ping() else fallback.ping()
        } catch (_: Exception) {
            try {
                fallback.ping()
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun cancel(): Boolean {
        var ok = false
        try {
            ok = primary.cancel() || ok
        } catch (_: Exception) {
        }
        try {
            ok = fallback.cancel() || ok
        } catch (_: Exception) {
        }
        return ok
    }

    override suspend fun guestStatus(): GuestStatus = fallback.guestStatus()
    override suspend fun boot(): GuestOpResult = fallback.boot()
    override suspend fun provision(): GuestOpResult = fallback.provision()
}
