// Sunshine VsockGuestChannel — live Debian pVM transport (production GuestChannel).
// Replaces FakeGuestChannel (which only echoes "(preview) ran: …") with a real
// framed path into the Debian guest:
//
//   UI block → TerminalViewModel.send() → GuestChannel.exec()
//     → VsockFrameMultiplexer.encode(blockId, token, origin, command)
//     → GuestTransport.execFrame() → guest sunshine-exec via SSH
//       (stdin: line1=token, line2=origin, rest=command — guest/sunshine-exec)
//     → framed stdout/stderr → ChannelOutcome.Completed
//
// Backend mirrors (keep in sync):
//   framing/policy → src/runtimes/policy.js (TIERS, decideExec, enforcePolicy)
//   exec/shim      → src/runtimes/debian.js (shimInput, capGuestOutput, ping)
//   auth           → src/runtimes/auth.js (.session-token, tokenId = sha256/16)
//   heartbeat      → src/runtimes/heartbeat.js (2.5s cadence, 2-miss budget)
//   thermal        → src/runtimes/thermal.js (battery + thermal_zone, Power Saver)
//   audit          → src/runtimes/bridge.js (commandHash, never raw token)
package sunshine.terminal

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Ring-buffer cap for guest stdout/stderr (bridge.js RING_BUFFER_LINES). */
const val VSOCK_RING_BUFFER_LINES = 10_000

/** Agent-step cap before loop pause (vm.js DEFAULT_AGENT_MAX_STEPS). */
const val VSOCK_AGENT_MAX_STEPS = 20

/** Heartbeat cadence/budget (heartbeat.js + VmControllerService). */
const val VSOCK_HEARTBEAT_INTERVAL_MS = 2_500L
const val VSOCK_MAX_MISSES = 2

/** Default Debian guest CID (debian.js defaultDebianConfig). */
const val VSOCK_DEFAULT_CID = 42

// ---------------------------------------------------------------------------
// Policy classifier — full Kotlin port of policy.js classifyCommand/
// decideExec (keep in sync; the guest-side sunshine-exec shim re-checks
// catastrophic patterns as defense in depth).
// Tier 1 SAFE → auto-allow. Tier 2 STATE_CHANGE → confirm. Tier 3
// DESTRUCTIVE → confirm-explicit.
// ---------------------------------------------------------------------------

private val T3_PATTERNS = listOf(
    Regex("""\brm\s+.*-[a-z]*r[a-z]*f\b"""),
    Regex("""\brm\s+-r\b"""),
    Regex("""\bmkfs\b"""),
    Regex("""\bdd\b.*\bof="""),
    Regex(""":\(\)\s*\{\s*:\|\:&\s*\}\s*;?\s*:"""),
    Regex("""\bshutdown\b"""), Regex("""\bpoweroff\b"""),
    Regex("""\breboot\b"""), Regex("""\bhalt\b"""),
    Regex("""\bsudo\b"""),
    Regex("""\bchmod\s+(-r\s+)?777\b"""),
    Regex("""\bchown\s+-r\b.*\/"""),
    Regex("""\bcurl\b.*\|\s*(sh|bash)\b"""),
    Regex("""\bwget\b.*\|\s*(sh|bash)\b"""),
    Regex("""base64\s+(-d|--decode)\b.*\|\s*(sh|bash)\b"""),
    Regex("""\bnc\b.*\s-l\b"""),
    Regex("""\bncat\b.*\s-l\b"""),
    Regex("""\bsocat\b.*listen\b"""),
    Regex("""\biptables\b"""), Regex("""\bnft\b"""),
    Regex("""\bdrop\s+table\b"""), Regex("""\bdelete\s+from\b"""),
    Regex(""">\s*\/dev\/sd[a-z]\b"""),
    Regex("""\bapt\s+(purge|autoremove)\b"""),
    Regex("""\bkill\s+-9\s+-1\b"""),
    Regex("""\bpkill\s+-9\b"""),
)

private val T2_PATTERNS = listOf(
    Regex("""\bapt(-get)?\s+install\b"""),
    Regex("""\bpip\s+install\b"""),
    Regex("""\bnpm\s+(install|i)\b"""),
    Regex("""\bcargo\s+(install|add)\b"""),
    Regex("""\bgit\s+(commit|push|publish)\b"""),
    Regex("""\bdocker\s+(run|build|push|rm|rmi|compose)\b"""),
    Regex("""\bsystemctl\s+(restart|stop|start|enable|disable)\b"""),
    Regex("""\bservice\s+\w+\s+(restart|stop|start)\b"""),
    Regex("""\bmv\b"""), Regex("""\bcp\s+-r\b"""),
    Regex("""\btar\s+.*-[a-z]*x"""),
    Regex("""\bunzip\b"""),
    Regex("""\buseradd\b"""), Regex("""\bpasswd\b"""),
    Regex("""\bssh\b"""),
    Regex("""\bscp\b"""), Regex("""\brsync\b"""),
    Regex("""\bupload\b"""), Regex("""\bpublish\b"""),
)

internal fun normalizeCommand(cmd: String): String = cmd
    .replace("\\", "")
    .replace("\"", "").replace("'", "")
    .replace(Regex("\\s+"), " ").trim().lowercase()

// Drop multi-word quoted spans (string literals); keep single-word spans
// (`"rm" -rf /` evasion). Mirrors policy.js stripQuotedSpans.
internal fun stripQuotedSpans(s: String): String {
    var out = s
    out = Regex("\"([^\"]*)\"").replace(out) { m ->
        val inner = m.groupValues[1]
        if (inner.any { it.isWhitespace() }) " " else inner
    }
    out = Regex("'([^']*)'").replace(out) { m ->
        val inner = m.groupValues[1]
        if (inner.any { it.isWhitespace() }) " " else inner
    }
    return out
}

// Bodies of $() and `` substitutions — these execute even inside quotes.
internal fun substitutionBodies(command: String): List<String> {
    val bodies = mutableListOf<String>()
    Regex("""\$\(([^()]*)\)""").findAll(command).forEach { bodies.add(it.groupValues[1]) }
    Regex("`([^`]*)`").findAll(command).forEach { bodies.add(it.groupValues[1]) }
    return bodies
}

internal fun classifyRisk(command: String): Pair<RiskTier, String> {
    val norm = normalizeCommand(command)
    if (norm.isEmpty()) return RiskTier.SAFE to "empty-command"
    val bare = normalizeCommand(stripQuotedSpans(command))
    fun t3Hit(s: String): Regex? = T3_PATTERNS.firstOrNull { it.containsMatchIn(s) }
    t3Hit(bare)?.let { return RiskTier.DESTRUCTIVE to "matched destructive pattern ${it.pattern.take(40)}" }
    for (body in substitutionBodies(command)) {
        t3Hit(normalizeCommand(body))?.let {
            return RiskTier.DESTRUCTIVE to "destructive pattern in command substitution"
        }
    }
    if (Regex("""\|\s*(sh|bash|zsh)\b""").containsMatchIn(bare)) {
        return RiskTier.DESTRUCTIVE to "pipe-to-shell"
    }
    if (Regex("""\$\(.+\)""").containsMatchIn(norm) || Regex("`[^`]+`").containsMatchIn(norm)) {
        return RiskTier.STATE_CHANGE to "command-substitution"
    }
    for (re in T2_PATTERNS) {
        if (re.containsMatchIn(norm)) return RiskTier.STATE_CHANGE to "matched state-change pattern ${re.pattern.take(40)}"
    }
    return RiskTier.SAFE to "no-risk-patterns"
}

// ---------------------------------------------------------------------------
// VsockFrameMultiplexer — length-prefixed frames multiplexed by blockId.
// Wire format (big-endian): [u32 totalLen][u64 blockId][payload bytes].
// Payload = "token\norigin\ncommand" (debian.js shimInput), so the guest
// sunshine-exec shim validates per-exec without any protocol change when
// the transport moves from SSH today to AF_VSOCK tomorrow.
// ---------------------------------------------------------------------------

object VsockFrameMultiplexer {
    fun encode(blockId: Long, token: String, origin: String, command: String): ByteArray {
        val payload = "$token\n$origin\n$command".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(4 + 8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8 + payload.size)
        buf.putLong(blockId)
        buf.put(payload)
        return buf.array()
    }

    data class DecodedFrame(val blockId: Long, val token: String, val origin: String, val command: String)

    fun decodePayload(blockId: Long, payload: ByteArray): DecodedFrame {
        val text = payload.toString(Charsets.UTF_8)
        val lines = text.split("\n")
        val token = lines.getOrElse(0) { "" }
        val origin = lines.getOrElse(1) { "human" }
        val command = if (lines.size > 2) lines.subList(2, lines.size).joinToString("\n") else ""
        return DecodedFrame(blockId, token, origin, command)
    }
}

// ---------------------------------------------------------------------------
// Transport abstraction. Today: SSH to the live Debian pVM (same path as
// scli — ephemeral port in vm-state.json, token in .session-token, remote
// `sunshine-exec`). Tomorrow: swap in an AF_VSOCK socket transport without
// touching TerminalViewModel — the frame format is already VSOCK-ready.
// ---------------------------------------------------------------------------

data class TransportResult(
    val ok: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val exitCode: Int = 0,
    val reason: String? = null,
)

data class GuestOpResult(
    val ok: Boolean,
    val note: String? = null,
    val remediation: String? = null,
)

data class GuestStatus(
    val imagePresent: Boolean = false,
    val kernelPresent: Boolean = false,
    val crosvm: String? = null,
    val sshPresent: Boolean = false,
    /** Vsock agent answers on (cid, port) — the preferred exec path. */
    val vsockPresent: Boolean = false,
    val hasToken: Boolean = false,
    val sshPort: Int? = null,
    val missing: List<String> = emptyList(),
)

interface GuestTransport {
    suspend fun execFrame(frame: ByteArray, blockId: Long, timeoutMs: Long = 60_000L): TransportResult
    suspend fun ping(): Boolean
    /** Best-effort cancel of a running exec (CTRL_C). Default no-op. */
    suspend fun cancel(): Boolean = false
    /** Probe boot prerequisites (default: unsupported transport). */
    suspend fun guestStatus(): GuestStatus = GuestStatus(missing = listOf("unsupported-transport"))
    /** Boot the guest (default: unsupported). */
    suspend fun boot(): GuestOpResult = GuestOpResult(ok = false, remediation = "boot unsupported on this transport")
    /** Provision the guest bundle (default: unsupported). */
    suspend fun provision(): GuestOpResult =
        GuestOpResult(ok = false, remediation = "provision unsupported on this transport")
}

// ---------------------------------------------------------------------------
// Audit log — Android port of bridge.js appendAuditLog.
// JSONL, hash-only: commandHash = sha256/16, secrets redacted, raw token
// never persisted. Best-effort: never throws, never blocks exec.
// ---------------------------------------------------------------------------

internal fun redactSecrets(text: String): String = text
    .replace(Regex("""(--token[=\s]+)(\S+)""", RegexOption.IGNORE_CASE), "$1[redacted]")
    .replace(
        Regex("""((?:password|passwd|secret|api[_-]?key|bearer)[=\s:]+)(\S+)""", RegexOption.IGNORE_CASE),
        "$1[redacted]",
    )

internal fun commandHash(command: String): String = try {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(command.toByteArray(Charsets.UTF_8))
    bytes.joinToString("") { "%02x".format(it) }.take(16)
} catch (_: Exception) {
    command.length.toString(16)
}

interface GuestAuditLog {
    fun append(event: String, fields: Map<String, String?>)
}

class FileAuditLog(private val dir: File?) : GuestAuditLog {
    override fun append(event: String, fields: Map<String, String?>) {
        if (dir == null) return
        try {
            dir.mkdirs()
            val rec = linkedMapOf<String, String?>("at" to java.time.Instant.now().toString(), "event" to event)
            rec.putAll(fields)
            val json = buildString {
                append("{")
                rec.entries.forEachIndexed { i, (k, v) ->
                    if (i > 0) append(",")
                    append("\"").append(k).append("\":")
                    if (v == null) append("null")
                    else append("\"").append(v.replace("\\", "\\\\").replace("\"", "\\\"").take(300)).append("\"")
                }
                append("}")
            }
            File(dir, "audit.log").appendText(json + "\n")
        } catch (_: Exception) {
        }
    }
}

class NoopAuditLog : GuestAuditLog {
    override fun append(event: String, fields: Map<String, String?>) = Unit
}

/**
 * SSH transport into the live Debian pVM.
 * Reads <vmDir>/sunshine-vm.json (base ssh config), <vmDir>/vm-state.json
 * (ephemeral sshPort from `scli vm boot`), and <vmDir>/.session-token
 * (auth.js record { token, id }). Never logs the token — only the 16-hex
 * token id prefix appears in errors (auth.js convention).
 *
 * Host keys: StrictHostKeyChecking=accept-new with a dedicated known_hosts
 * file (default <vmDir>/known_hosts) — first connection pins, later
 * connections verify. No silent MITM window on every exec.
 */
class SshGuestTransport(
    private val vmDir: File,
    private val sshUser: String = "sunshine",
    private val sshKey: File? = null,
    private val fallbackPort: Int = 2222,
    fallbackDirs: List<File> = listOf(File("/data/data/com.termux/files/home/.sunshine/vm")),
    private val knownHostsFile: File? = null,
    private val bundleDir: File? = null,
    /**
     * Vsock reachability probe (VsockSocketTransport::probe). When the agent
     * answers, the ssh client is demoted from boot-blocker to soft fallback:
     * exec prefers vsock, so "ssh client missing" no longer gates boot.
     * Null (default) preserves the legacy all-ssh behavior.
     */
    private val vsockProbe: (suspend () -> Boolean)? = null,
) : GuestTransport {

    private val extraDirs: List<File> = fallbackDirs
    private val running = java.util.concurrent.atomic.AtomicReference<Process?>(null)
    @Volatile private var sshChecked: Boolean? = null

    private fun readJson(file: File): JSONObject? = try {
        if (!file.exists()) null else JSONObject(file.readText())
    } catch (_: Exception) {
        null
    }

    // Termux `scli` provisions ~/.sunshine/vm while the app uses filesDir.
    // Search both so `scli vm boot` state is visible to the UI without copy.
    // extraDirs is constructor-injected (default Termux path) so non-Termux
    // devices can override it instead of carrying a hardcoded path.
    private fun candidateDirs(): List<File> = listOf(vmDir) + extraDirs

    private fun firstExisting(name: String): File? =
        candidateDirs().map { File(it, name) }.firstOrNull { it.exists() }

    private fun sessionToken(): Pair<String, String>? {
        val rec = firstExisting(".session-token")?.let { readJson(it) } ?: return null
        val token = rec.optString("token", "")
        if (token.isEmpty()) return null
        val id = rec.optString("id", token.take(16))
        return token to id
    }

    private fun sshPort(): Int {
        val state = firstExisting("vm-state.json")?.let { readJson(it) }
        val port = state?.optInt("sshPort", fallbackPort) ?: fallbackPort
        if (port > 0) return port
        val cfg = firstExisting("sunshine-vm.json")?.let { readJson(it) }
        return cfg?.optJSONObject("ssh")?.optInt("port", fallbackPort) ?: fallbackPort
    }

    private fun sshKeyFile(): String? {
        sshKey?.let { if (it.exists()) return it.absolutePath }
        val cfg = firstExisting("sunshine-vm.json")?.let { readJson(it) }
        val fromCfg = cfg?.optJSONObject("ssh")?.optString("key", "")
        if (!fromCfg.isNullOrEmpty() && File(fromCfg).exists()) return fromCfg
        val def = firstExisting("id_sunshine")
        if (def != null) return def.absolutePath
        return null
    }

    private fun knownHosts(): String {
        val f = knownHostsFile ?: File(vmDir, "known_hosts")
        try {
            f.parentFile?.mkdirs()
            if (!f.exists()) f.createNewFile()
        } catch (_: Exception) {
        }
        return f.absolutePath
    }

    private fun baseSshArgs(port: Int, connectTimeoutSec: Int): List<String> {
        val keyArg = sshKeyFile()?.let { listOf("-i", it) } ?: emptyList()
        return listOf("ssh") + keyArg + listOf(
            "-p", port.toString(),
            "-o", "StrictHostKeyChecking=accept-new",
            "-o", "UserKnownHostsFile=${knownHosts()}",
            "-o", "ConnectTimeout=$connectTimeoutSec",
            "-o", "BatchMode=yes",
        )
    }

    suspend fun sshAvailable(): Boolean = withContext(Dispatchers.IO) {
        sshChecked?.let { return@withContext it }
        val ok = try {
            val p = ProcessBuilder("sh", "-c", "command -v ssh").start()
            p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
        } catch (_: Exception) {
            false
        }
        sshChecked = ok
        ok
    }

    override suspend fun cancel(): Boolean {
        val p = running.get() ?: return false
        return try {
            p.destroy()
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun execFrame(frame: ByteArray, blockId: Long, timeoutMs: Long): TransportResult =
        withContext(Dispatchers.IO) {
            // Frame → (token, origin, command) per multiplexer protocol.
            val buf = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
            if (frame.size < 12) {
                return@withContext TransportResult(false, reason = "frame-too-short")
            }
            buf.int // totalLen
            val frameBlockId = buf.long
            val payload = ByteArray(frame.size - 12).also { buf.get(it) }
            val decoded = VsockFrameMultiplexer.decodePayload(frameBlockId, payload)

            val (token, _) = sessionToken()
                ?: return@withContext TransportResult(
                    false,
                    reason = "no-session-token",
                    stderr = "No session token. Boot the guest (`scli vm boot`) to rotate one in.",
                )
            if (!sshAvailable()) {
                return@withContext TransportResult(
                    false,
                    reason = "no-ssh-client",
                    stderr = "OpenSSH client not found on host; install openssh to reach the guest.",
                )
            }
            val port = sshPort()
            val cmd = baseSshArgs(port, 10) + listOf(
                "$sshUser@127.0.0.1",
                "sunshine-exec",
            )
            try {
                val proc = spawn(cmd)
                    .redirectErrorStream(false)
                    .start()
                running.set(proc)
                try {
                    // stdin carries token/origin/command — never argv (debian.js).
                    try {
                        proc.outputStream.bufferedWriter(Charsets.UTF_8).use {
                            it.write("$token\n${decoded.origin}\n${decoded.command}")
                            it.flush()
                        }
                    } catch (e: Exception) {
                        return@withContext TransportResult(
                            false, reason = "guest-stdin-failed",
                            stderr = e.message ?: "stdin write failed",
                        )
                    }
                    val finished = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (!finished) {
                        proc.destroyForcibly()
                        return@withContext TransportResult(false, reason = "guest-exec-timeout")
                    }
                    val out = proc.inputStream.bufferedReader().readText()
                    val err = proc.errorStream.bufferedReader().readText()
                    val code = proc.exitValue()
                    if (err.contains("SUNSHINE-AUTH-DENIED")) {
                        return@withContext TransportResult(
                            false, stdout = out, stderr = err,
                            exitCode = code, reason = "guest-auth-denied",
                        )
                    }
                    if (code != 0 && out.isEmpty() && err.isEmpty()) {
                        return@withContext TransportResult(
                            false, stdout = out, stderr = err,
                            exitCode = code, reason = "guest-exec-failed",
                        )
                    }
                    // Non-zero exit with output = guest ran it, command failed.
                    TransportResult(code == 0, stdout = out, stderr = err, exitCode = code)
                } finally {
                    running.compareAndSet(proc, null)
                }
            } catch (e: Exception) {
                TransportResult(false, reason = e.message ?: "ssh-spawn-failed")
            }
        }

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sshAvailable()) return@withContext false
            val port = sshPort()
            val cmd = baseSshArgs(port, 5) + listOf(
                "$sshUser@127.0.0.1",
                "true",
            )
            val proc = spawn(cmd).start()
            val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            finished && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    // ------------------------------------------------------------------
    // Boot + provision (debian.js port). PATH includes Termux usr/bin so
    // the app finds the Termux openssh client when present on-device.
    // ------------------------------------------------------------------

    private fun pathEnv(): String {
        val sys = System.getenv("PATH") ?: "/system/bin:/vendor/bin"
        val termux = "/data/data/com.termux/files/usr/bin"
        return if (sys.split(":").contains(termux)) sys else "$termux:$sys"
    }

    private fun spawn(cmd: List<String>): ProcessBuilder {
        val pb = ProcessBuilder(cmd).redirectErrorStream(false)
        try {
            pb.environment()["PATH"] = pathEnv()
        } catch (_: Exception) {
        }
        return pb
    }

    private fun findBinary(name: String, extraAbs: List<String> = emptyList()): String? {
        for (abs in extraAbs) {
            try {
                if (File(abs).exists()) return abs
            } catch (_: Exception) {
            }
        }
        return try {
            val p = spawn(listOf("sh", "-c", "command -v $name")).start()
            val ok = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0
            if (!ok) null
            else p.inputStream.bufferedReader().readText().trim().ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun cfg(): JSONObject? = firstExisting("sunshine-vm.json")?.let { readJson(it) }

    private fun guestImage(): String =
        cfg()?.optString("image", "")?.ifEmpty { null } ?: File(vmDir, "debian.img").absolutePath

    private fun guestKernel(): String =
        cfg()?.optString("kernel", "")?.ifEmpty { null } ?: File(vmDir, "Image").absolutePath

    private fun guestCid(): Int = cfg()?.optInt("cid", VSOCK_DEFAULT_CID) ?: VSOCK_DEFAULT_CID

    private fun execLog(): String =
        cfg()?.optString("execLog", "")?.ifEmpty { null } ?: File(vmDir, "debian-exec.log").absolutePath

    private fun consoleLog(): String =
        cfg()?.optString("consoleLog", "")?.ifEmpty { null } ?: File(vmDir, "debian-console.log").absolutePath

    override suspend fun guestStatus(): GuestStatus = withContext(Dispatchers.IO) {
        val image = guestImage()
        val kernel = guestKernel()
        val imageOk = try { File(image).exists() } catch (_: Exception) { false }
        val kernelOk = try { File(kernel).exists() } catch (_: Exception) { false }
        val crosvm = findBinary("crosvm", listOf("/apex/com.android.virt/bin/vm"))
        val ssh = findBinary("ssh")
        // Vsock agent reachable? Then ssh is a fallback, not a requirement.
        val vsockOk = try {
            vsockProbe?.invoke() == true
        } catch (_: Exception) {
            false
        }
        val tok = sessionToken() != null
        val port = try {
            firstExisting("vm-state.json")?.let { readJson(it) }?.optInt("sshPort", -1) ?: -1
        } catch (_: Exception) {
            -1
        }
        val missing = mutableListOf<String>()
        if (!imageOk) missing.add("guest image ($image)")
        if (!kernelOk) missing.add("guest kernel ($kernel)")
        if (crosvm == null) missing.add("crosvm binary")
        if (ssh == null && !vsockOk) missing.add("ssh client (Termux openssh)")
        GuestStatus(
            imagePresent = imageOk, kernelPresent = kernelOk,
            crosvm = crosvm, sshPresent = ssh != null, vsockPresent = vsockOk,
            hasToken = tok, sshPort = if (port > 0) port else null,
            missing = missing,
        )
    }

    private fun issueToken(): Pair<String, String> {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        val id = commandHash(token)
        try {
            vmDir.mkdirs()
            val f = File(vmDir, ".session-token")
            f.writeText("{\"token\":\"$token\",\"id\":\"$id\"}")
            try {
                f.setReadable(false, false)
                f.setWritable(false, false)
                f.setReadable(true, true)
                f.setWritable(true, true)
            } catch (_: Exception) {
            }
        } catch (_: Exception) {
        }
        return token to id
    }

    private fun saveBootState(port: Int, tokenId: String, pid: Long?) {
        try {
            vmDir.mkdirs()
            val f = File(vmDir, "vm-state.json")
            val cur = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            cur.put("sshPort", port)
            cur.put("tokenId", tokenId)
            if (pid != null) cur.put("pid", pid)
            f.writeText(cur.toString(2))
        } catch (_: Exception) {
        }
    }

    private fun runSsh(
        remoteCmd: String,
        stdin: String?,
        timeoutMs: Long,
    ): Triple<Int, String, String> {
        val port = sshPort()
        val cmd = baseSshArgs(port, (timeoutMs / 1000).toInt().coerceAtLeast(10)) + listOf(
            "$sshUser@127.0.0.1", remoteCmd,
        )
        val proc = spawn(cmd).start()
        running.set(proc)
        try {
            if (stdin != null) {
                try {
                    proc.outputStream.bufferedWriter(Charsets.UTF_8).use {
                        it.write(stdin)
                        it.flush()
                    }
                } catch (e: Exception) {
                    return Triple(-1, "", e.message ?: "stdin failed")
                }
            } else {
                try {
                    proc.outputStream.close()
                } catch (_: Exception) {
                }
            }
            val done = proc.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            if (!done) {
                proc.destroyForcibly()
                return Triple(-1, "", "timeout")
            }
            return Triple(
                proc.exitValue(),
                proc.inputStream.bufferedReader().readText(),
                proc.errorStream.bufferedReader().readText(),
            )
        } finally {
            running.compareAndSet(proc, null)
        }
    }

    override suspend fun boot(): GuestOpResult = withContext(Dispatchers.IO) {
        val st = guestStatus()
        if (st.missing.isNotEmpty()) {
            return@withContext GuestOpResult(
                ok = false,
                remediation = "Missing: ${st.missing.joinToString(", ")}. " +
                    "Place a Debian rootfs + kernel at the paths in sunshine-vm.json " +
                    "and install Termux openssh, then retry.",
            )
        }
        val port = (22000..22999).random()
        val crosvmBin = st.crosvm ?: return@withContext GuestOpResult(
            ok = false, remediation = "crosvm binary not found.",
        )
        val cfgObj = cfg()
        val mem = cfgObj?.optInt("memoryMb", 2048) ?: 2048
        val cpus = cfgObj?.optInt("cpus", 2) ?: 2
        val cmdline = cfgObj?.optString("cmdline", "")?.ifEmpty { null }
            ?: "root=/dev/vda1 rw console=hvc0"
        val args = mutableListOf(
            crosvmBin, "run",
            "--cid", guestCid().toString(),
            "--mem", mem.toString(),
            "--cpus", cpus.toString(),
            "--kernel", guestKernel(),
            "--cmdline", cmdline,
            "--serial", "file:${consoleLog()}",
            guestImage(),
        )
        return@withContext try {
            val log = File(execLog())
            try {
                log.parentFile?.mkdirs()
            } catch (_: Exception) {
            }
            val proc = spawn(args)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log))
                .redirectError(ProcessBuilder.Redirect.appendTo(log))
                .start()
            val (_, tokenId) = issueToken()
            saveBootState(port, tokenId, pid = null)
            // NOTE: channel port is plumbed via vm-state.json sshPort; the
            // guest agent forwards host→guest on boot (see provision).
            GuestOpResult(
                ok = true,
                note = "Booting (CID ${guestCid()}, channel port $port, token $tokenId). " +
                    "Wait ~30s, then Provision.",
            )
        } catch (e: Exception) {
            GuestOpResult(ok = false, remediation = e.message ?: "boot-spawn-failed")
        }
    }

    private fun jsonEscape(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    override suspend fun provision(): GuestOpResult = withContext(Dispatchers.IO) {
        if (!sshAvailable()) {
            return@withContext GuestOpResult(
                ok = false, remediation = "OpenSSH client not found; install Termux openssh.",
            )
        }
        val rec = sessionToken() ?: return@withContext GuestOpResult(
            ok = false, remediation = "No session token. Boot first.",
        )
        val token = rec.first
        val dir = bundleDir ?: return@withContext GuestOpResult(
            ok = false, remediation = "Guest bundle not packaged with this build.",
        )
        val names = listOf(
            "provision.sh", "sunshine-exec", "sunshine-vsock-agent.py",
            "sunshine-vsock-agent.service", "sunshine-agent.slice", "nftables-sunshine.nft",
        )
        val bundle = mutableMapOf<String, String>()
        for (n in names) {
            if (n == "provision.sh") continue
            val f = File(dir, n)
            if (!f.exists()) {
                return@withContext GuestOpResult(
                    ok = false, remediation = "Guest bundle unreadable: $n missing.",
                )
            }
            try {
                bundle[n] = f.readText()
            } catch (e: Exception) {
                return@withContext GuestOpResult(
                    ok = false, remediation = "Guest bundle unreadable: ${e.message}",
                )
            }
        }
        val installer = try {
            File(dir, "provision.sh").readText()
        } catch (e: Exception) {
            return@withContext GuestOpResult(
                ok = false, remediation = "Guest bundle unreadable: ${e.message}",
            )
        }
        val port = sshPort()
        var r = runSsh(
            "mkdir -p /tmp/sunshine-guest && cat > /tmp/sunshine-guest/provision.sh",
            installer, 30_000,
        )
        if (r.first != 0) {
            return@withContext GuestOpResult(
                ok = false,
                remediation = "Guest not reachable on port $port. Wait for boot, then retry. (ssh: ${r.third.take(120)})",
            )
        }
        val bundleJson = buildString {
            append("{")
            bundle.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) append(",")
                append("\"").append(k).append("\":\"").append(jsonEscape(v)).append("\"")
            }
            append("}")
        }
        r = runSsh("cat > /tmp/sunshine-guest/bundle.json", bundleJson, 30_000)
        if (r.first != 0) {
            return@withContext GuestOpResult(
                ok = false, remediation = "Bundle transfer failed (ssh: ${r.third.take(120)}).",
            )
        }
        r = runSsh(
            "sudo bash /tmp/sunshine-guest/provision.sh",
            "$token\n2\nopen\n", 120_000,
        )
        if (r.first != 0) {
            return@withContext GuestOpResult(
                ok = false,
                remediation = "Activation failed: ${(r.second + r.third).take(300)}",
            )
        }
        try {
            vmDir.mkdirs()
            val f = File(vmDir, "vm-state.json")
            val cur = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            cur.put("guestVersion", 2)
            f.writeText(cur.toString(2))
        } catch (_: Exception) {
        }
        GuestOpResult(ok = true, note = "Guest bundle v2 active.")
    }
}

// ---------------------------------------------------------------------------
// VsockGuestChannel — production GuestChannel over the framed transport.
// Mirrors vm.js executeAVF: policy gate → agent-step-cap → provider.exec →
// ring-buffer cap → Completed / NeedsApproval / Denied / LoopPaused.
// ---------------------------------------------------------------------------

class VsockGuestChannel(
    private val transport: GuestTransport,
    private val thermalSource: GuestThermalSource = AndroidThermalSource(),
    stateDir: File? = null,
    audit: GuestAuditLog? = null,
) : GuestChannel {

    private val auditLog: GuestAuditLog = audit ?: stateDir?.let { FileAuditLog(it) } ?: NoopAuditLog()
    private val loopStore: File? = stateDir?.let { File(it, "vm-state.json") }

    // IDs for visible terminal blocks come from the caller (ViewModel) so the
    // hot stdout flow routes to the right card. Internal frames (workspace
    // listing, previews) use an isolated negative sequence — never colliding.
    private val internalIds = AtomicLong(-1L)

    private suspend fun readAgentSteps(): Int = withContext(Dispatchers.IO) {
        try {
            val f = loopStore ?: return@withContext 0
            if (!f.exists()) 0 else (JSONObject(f.readText()).optInt("agentStepCount", 0))
        } catch (_: Exception) {
            0
        }
    }

    private suspend fun agentCap(): Int = withContext(Dispatchers.IO) {
        try {
            val f = loopStore ?: return@withContext VSOCK_AGENT_MAX_STEPS
            if (!f.exists()) return@withContext VSOCK_AGENT_MAX_STEPS
            val cap = JSONObject(f.readText()).optInt("agentMaxSteps", VSOCK_AGENT_MAX_STEPS)
            if (cap > 0) cap else VSOCK_AGENT_MAX_STEPS
        } catch (_: Exception) {
            VSOCK_AGENT_MAX_STEPS
        }
    }

    private suspend fun writeAgentSteps(n: Int) = withContext(Dispatchers.IO) {
        val f = loopStore ?: return@withContext
        try {
            f.parentFile?.mkdirs()
            val cur = try {
                if (f.exists()) JSONObject(f.readText()) else JSONObject()
            } catch (_: Exception) {
                JSONObject()
            }
            cur.put("agentStepCount", n)
            f.writeText(cur.toString(2))
        } catch (_: Exception) {
        }
    }

    private val _stdout = MutableSharedFlow<ChannelLine>(extraBufferCapacity = 256)
    override val stdout: Flow<ChannelLine> = _stdout.asSharedFlow()

    private val _thermal = MutableStateFlow(ThermalSnapshot(false))
    override val thermal: Flow<ThermalSnapshot> = _thermal.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.CONNECTED)
    override val connection: Flow<ConnectionState> = _connection.asStateFlow()

    private fun capLines(text: String, cap: Int = VSOCK_RING_BUFFER_LINES): Pair<String, Int> {
        if (text.isEmpty()) return "" to 0
        val lines = text.split("\n")
        val trailingEmpty = if (lines.isNotEmpty() && lines.last() == "") 1 else 0
        val content = lines.dropLast(trailingEmpty)
        if (content.size <= cap) return text to 0
        val kept = content.takeLast(cap)
        return (kept.joinToString("\n") + "\n") to (content.size - cap)
    }

    override suspend fun exec(
        command: String,
        origin: String,
        approved: Boolean,
        blockId: Long?,
    ): ChannelOutcome {
        val (tier, reason) = classifyRisk(command)
        val bid = blockId ?: internalIds.getAndDecrement()
        val via: String

        // Policy gate (policy.js enforcePolicy): Tier 2+ needs confirmation.
        if (tier != RiskTier.SAFE && !approved) {
            auditLog.append(
                "exec-decision",
                mapOf(
                    "origin" to origin, "tier" to tier.ordinal.plus(1).toString(),
                    "verdict" to "confirm", "via" to "prompt",
                    "commandHash" to commandHash(command),
                    "commandPreview" to redactSecrets(command).take(60),
                ),
            )
            return ChannelOutcome.NeedsApproval(
                ApprovalRequest(
                    blockId = bid,
                    command = command,
                    tier = tier,
                    origin = origin,
                    reason = reason,
                    explicit = tier == RiskTier.DESTRUCTIVE,
                ),
            )
        }
        via = if (tier == RiskTier.SAFE) "auto-tier1" else "flag"

        // Agent loop guard (vm.js agent-step-cap, persisted in vm-state.json):
        // pause after N agent execs. Approval unlocks the tier gate, not the
        // loop budget. Human exec resets (the "tap to continue").
        val cap = agentCap()
        if (origin == "agent") {
            val steps = readAgentSteps()
            if (steps >= cap) {
                auditLog.append(
                    "exec-decision",
                    mapOf(
                        "origin" to origin, "tier" to tier.ordinal.plus(1).toString(),
                        "verdict" to "denied", "via" to "agent-step-cap",
                        "commandHash" to commandHash(command),
                    ),
                )
                return ChannelOutcome.LoopPaused(steps, cap)
            }
            writeAgentSteps(steps + 1)
        } else if (origin == "human") {
            if (readAgentSteps() != 0) writeAgentSteps(0)
        }

        // Token travels inside the frame (never logged); empty here is filled
        // by the transport from .session-token. The frame keeps the VSOCK
        // shape even on the SSH transport (see VsockFrameMultiplexer docs).
        // SSH transport re-reads the token itself, so no re-encode needed.
        val frame = VsockFrameMultiplexer.encode(bid, token = "", origin = origin, command = command)
        val res = try {
            transport.execFrame(frame, bid)
        } catch (e: Exception) {
            _connection.emit(ConnectionState.LOST)
            auditLog.append(
                "exec-result",
                mapOf(
                    "origin" to origin, "tier" to tier.ordinal.plus(1).toString(),
                    "ok" to "false", "reason" to (e.message ?: "transport-failed"),
                    "commandHash" to commandHash(command),
                ),
            )
            return ChannelOutcome.Denied(e.message ?: "transport-failed")
        }

        if (!res.ok && (res.reason == "no-session-token" || res.reason == "guest-exec-failed" || res.reason == "guest-exec-timeout")) {
            _connection.emit(ConnectionState.DEGRADED)
        } else {
            _connection.emit(ConnectionState.CONNECTED)
        }

        // Single thermal snapshot per exec (thermal.js probeThermal).
        val snap = try {
            thermalSource.snapshot()
        } catch (_: Exception) {
            ThermalReading(false)
        }
        try {
            _thermal.emit(ThermalSnapshot(snap.powerSaver, snap.reasons))
        } catch (_: Exception) {
        }
        auditLog.append(
            "exec-result",
            mapOf(
                "origin" to origin, "tier" to tier.ordinal.plus(1).toString(),
                "ok" to res.ok.toString(), "reason" to res.reason,
                "code" to res.exitCode.toString(), "via" to via,
                "commandHash" to commandHash(command),
                "commandPreview" to redactSecrets(command).take(60),
            ),
        )

        if (!res.ok && res.stdout.isEmpty() && res.stderr.isNotEmpty() &&
            (res.reason == "guest-auth-denied" || res.reason == "no-session-token")
        ) {
            return ChannelOutcome.Denied(res.reason ?: "denied")
        }
        if (!res.ok && res.stdout.isEmpty() && res.stderr.isEmpty()) {
            return ChannelOutcome.Denied(res.reason ?: "guest-exec-failed")
        }

        val (outCapped, droppedOut) = capLines(res.stdout)
        val (errCapped, droppedErr) = capLines(res.stderr)
        val lines = (outCapped + if (errCapped.isNotEmpty()) errCapped else "")
            .split("\n").filterIndexed { i, s -> !(i == 0 && s.isEmpty()) }
            .let { if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it }

        // Stream live lines for the tail-following canvas (routed by the
        // caller-supplied block id, so they land on the right card).
        for (line in lines) {
            _stdout.emit(ChannelLine(bid, line, stderr = false))
        }

        return ChannelOutcome.Completed(
            lines = lines,
            exitCode = res.exitCode,
            tier = tier,
            droppedLines = droppedOut + droppedErr,
            powerSaver = snap.powerSaver,
        )
    }

    override suspend fun sendControl(key: ControlKey) {
        // CTRL_C cancels the running guest exec (destroys the ssh child);
        // TAB/ESC are readline-scoped and stay no-ops on this transport.
        if (key == ControlKey.CTRL_C) {
            try {
                transport.cancel()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun continueLoop() {
        writeAgentSteps(0)
    }

    private fun shQuote(s: String): String = "'" + s.replace("'", "'\"'\"'") + "'"

    // Decode `ls -b` C-style escapes (newline → \n, backslash → \\, …)
    // so hostile filenames can't split the listing into ghost entries.
    internal fun decodeLsEscape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                out.append(c)
                i++
                continue
            }
            when (val n = s[i + 1]) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                '\\' -> out.append('\\')
                '\'' -> out.append('\'')
                '"' -> out.append('"')
                ' ' -> out.append(' ')
                else -> {
                    // Octal \NNN (ls -b emits these for non-printables).
                    if (n in '0'..'7' && i + 3 < s.length) {
                        val oct = s.substring(i + 1, i + 4)
                        val v = oct.toIntOrNull(8)
                        if (v != null) {
                            out.append(v.toChar())
                            i += 4
                            continue
                        }
                    }
                    out.append(n)
                }
            }
            i += 2
        }
        return out.toString()
    }

    // Workspace listing mirrors workspace.js listWorkspace + drawer.js
    // DEFAULT_IGNORE. `ls -1 -b -p` escapes hostile names (see
    // decodeLsEscape); the guest FS is shown, not the app sandbox.
    override suspend fun listWorkspace(path: String): WorkspaceListing {
        val target = if (path.isBlank()) "." else path
        val cmd = "cd ${shQuote(target)} 2>/dev/null || cd .; pwd; ls -1 -b -p 2>&1"
        val bid = internalIds.getAndDecrement()
        val frame = VsockFrameMultiplexer.encode(bid, token = "", origin = "human", command = cmd)
        val res = try {
            transport.execFrame(frame, bid)
        } catch (e: Exception) {
            return WorkspaceListing(cwd = target, error = e.message ?: "transport-failed")
        }
        if (!res.ok && res.stdout.isBlank()) {
            return WorkspaceListing(cwd = target, error = res.reason ?: "guest-exec-failed")
        }
        val rawLines = res.stdout.split("\n").filter { it.isNotEmpty() }
        if (rawLines.isEmpty()) return WorkspaceListing(cwd = target)
        val cwd = rawLines.first().trim()
        val entries = rawLines.drop(1)
            .filter { it != "./" && it != "../" }
            .filter { it != "node_modules/" && it != ".git/" }
            .map { decodeLsEscape(it) }
            .map { line ->
                val isDir = line.endsWith("/")
                val name = if (isDir) line.dropLast(1) else line
                val full = if (cwd == "/") "/$name" else "$cwd/$name"
                WorkspaceEntry(name = name, path = full, isDirectory = isDir)
            }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
        return WorkspaceListing(cwd = cwd, entries = entries)
    }

    override suspend fun readWorkspaceFile(path: String): FileContent {
        val isMd = path.endsWith(".md", ignoreCase = true)
        val q = shQuote(path)
        // Size + binary guards first (drawer.js previewLinesFor skips binary/
        // large files): >200 KiB or NUL byte → honest skip, no dump into UI.
        val probe = "sz=\$(stat -c%s $q 2>/dev/null || echo 0); " +
            "if [ \"\$sz\" -gt 204800 ]; then echo SUNSHINE-TOO-LARGE; " +
            "elif LC_ALL=C grep -q -m1 \"\$(printf '\\x00')\" $q 2>/dev/null; then echo SUNSHINE-BINARY; " +
            "else sed -n '1,200p' $q 2>&1; fi"
        val bid = internalIds.getAndDecrement()
        val frame = VsockFrameMultiplexer.encode(bid, token = "", origin = "human", command = probe)
        val res = try {
            transport.execFrame(frame, bid)
        } catch (e: Exception) {
            return FileContent(path = path, error = e.message ?: "transport-failed")
        }
        if (!res.ok && res.stdout.isBlank()) {
            return FileContent(path = path, error = res.reason ?: "guest-exec-failed")
        }
        val first = res.stdout.lineSequence().firstOrNull()
        if (first == "SUNSHINE-TOO-LARGE") {
            return FileContent(path = path, error = "preview-skipped-large")
        }
        if (first == "SUNSHINE-BINARY") {
            return FileContent(path = path, error = "preview-skipped-binary")
        }
        val lines = res.stdout.split("\n").let {
            if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it
        }
        return FileContent(path = path, lines = lines, isMarkdown = isMd)
    }

    override suspend fun guestStatus(): GuestStatus = try {
        transport.guestStatus()
    } catch (e: Exception) {
        GuestStatus(missing = listOf(e.message ?: "status-failed"))
    }

    override suspend fun bootGuest(): GuestOpResult {
        val res = try {
            transport.boot()
        } catch (e: Exception) {
            GuestOpResult(ok = false, remediation = e.message ?: "boot-failed")
        }
        auditLog.append(
            "boot",
            mapOf("ok" to res.ok.toString(), "reason" to res.remediation),
        )
        if (res.ok) _connection.emit(ConnectionState.CONNECTED)
        return res
    }

    override suspend fun provisionGuest(): GuestOpResult {
        val res = try {
            transport.provision()
        } catch (e: Exception) {
            GuestOpResult(ok = false, remediation = e.message ?: "provision-failed")
        }
        auditLog.append(
            "provision",
            mapOf("ok" to res.ok.toString(), "reason" to res.remediation),
        )
        return res
    }

    suspend fun refreshHealth(): ConnectionState {
        val alive = try { transport.ping() } catch (_: Exception) { false }
        val next = if (alive) ConnectionState.CONNECTED else ConnectionState.LOST
        _connection.emit(next)
        return next
    }
}

// ---------------------------------------------------------------------------
// Thermal source (thermal.js probeThermal port): sysfs battery + zones.
// Absent sensors → powerSaver=false (never blocks exec).
// ---------------------------------------------------------------------------

data class ThermalReading(val powerSaver: Boolean, val reasons: List<String> = emptyList())

interface GuestThermalSource {
    suspend fun snapshot(): ThermalReading
}

class AndroidThermalSource(
    private val batteryLowPct: Int = 15,
    private val warmTempC: Double = 43.0,
    private val severeTempC: Double = 48.0,
) : GuestThermalSource {
    override suspend fun snapshot(): ThermalReading = withContext(Dispatchers.IO) {
        val reasons = mutableListOf<String>()
        var maxTempC: Double? = null
        for (i in 0..7) {
            try {
                val raw = File("/sys/class/thermal/thermal_zone$i/temp").readText().trim().toDoubleOrNull()
                if (raw != null) {
                    val c = raw / 1000.0
                    maxTempC = maxOf(maxTempC ?: c, c)
                }
            } catch (_: Exception) {
            }
        }
        var batteryLow = false
        try {
            val pct = File("/sys/class/power_supply/battery/capacity").readText().trim().toIntOrNull()
            if (pct != null && pct <= batteryLowPct) {
                batteryLow = true
                reasons.add("battery $pct% ≤ $batteryLowPct%")
            }
        } catch (_: Exception) {
        }
        var throttled = batteryLow
        maxTempC?.let { t ->
            if (t >= severeTempC) {
                throttled = true
                reasons.add("skin ${t.toInt()}°C ≥ severe ${severeTempC.toInt()}°C")
            } else if (t >= warmTempC) {
                reasons.add("skin ${t.toInt()}°C ≥ warm ${warmTempC.toInt()}°C")
            }
        }
        ThermalReading(powerSaver = throttled, reasons = reasons)
    }
}
