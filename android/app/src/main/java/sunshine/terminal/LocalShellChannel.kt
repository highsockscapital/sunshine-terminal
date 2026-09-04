// Sunshine LocalShellChannel — on-device shell with zero dependencies.
// Runs commands via `sh -c` in the app sandbox (bionic, no ssh, no crosvm,
// no Termux, no root). This is what makes first launch seamless: the
// terminal is useful from second zero, and TerminalViewModel silently
// upgrades to the Debian pVM (VsockGuestChannel) once it is booted.
//
// Safety: same policy gate as the VM path (classifyRisk in
// VsockGuestChannel.kt). Tier 2+ without approval → NeedsApproval, so the
// risk modal behaves identically on both transports.
// Workspace root defaults to the app filesDir; callers may scope it to the
// vm dir or any other sandbox directory.
package sunshine.terminal

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

class LocalShellChannel(
    private val rootDir: File,
    private val execTimeoutMs: Long = 60_000L,
) : GuestChannel {

    override val stdout: Flow<ChannelLine> = emptyFlow()
    override val thermal: Flow<ThermalSnapshot> = emptyFlow()
    override val connection: Flow<ConnectionState> = emptyFlow()

    private val ids = AtomicLong(-1L)
    private val running = AtomicReference<Process?>(null)

    /**
     * Guest-side tooling (scli/sunshine/sunshine-exec) lives in the Debian
     * pVM or the Termux checkout — never in the app sandbox. Intercept it
     * here so users get a pointer instead of `inaccessible or not found`.
     * Returns null for ordinary shell commands (→ run locally).
     */
    private fun scliShim(command: String): ChannelOutcome? {
        val first = command.trimStart().substringBefore(" ").substringBefore("\t")
        if (first != "scli" && first != "sunshine" && first != "sunshine-exec") return null
        val lines = listOf(
            "$first is not available in the on-device shell (this file tree is the app sandbox).",
            "It runs inside the Debian guest once the pVM is booted:",
            "  1. Sidebar → Guest → Boot pVM (needs debian.img + Image, see Guest status)",
            "  2. Then Provision — $first works from this same prompt.",
            "Or run it from a Termux checkout: scli vm status",
        )
        return ChannelOutcome.Completed(
            lines = lines,
            exitCode = 127,
            tier = RiskTier.SAFE,
        )
    }

    private fun resolve(path: String): File {
        val p = path.trim()
        if (p.isEmpty() || p == ".") return rootDir
        val f = File(p)
        if (f.isAbsolute) return f
        return File(rootDir, p)
    }

    override suspend fun exec(
        command: String,
        origin: String,
        approved: Boolean,
        blockId: Long?,
    ): ChannelOutcome = withContext(Dispatchers.IO) {
        // Guest-side CLIs don't exist in the app sandbox: answer with
        // guidance instead of a bare `sh: scli: not found` (exit 127).
        scliShim(command)?.let { return@withContext it }
        val (tier, reason) = classifyRisk(command)
        val bid = blockId ?: ids.getAndDecrement()
        if (tier != RiskTier.SAFE && !approved) {
            return@withContext ChannelOutcome.NeedsApproval(
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
        try {
            val proc = ProcessBuilder("sh", "-c", command)
                .directory(rootDir.apply { mkdirs() })
                .redirectErrorStream(false)
                .start()
            running.set(proc)
            try {
                val finished = proc.waitFor(execTimeoutMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    proc.destroyForcibly()
                    return@withContext ChannelOutcome.Denied("local-exec-timeout")
                }
                val out = try {
                    proc.inputStream.bufferedReader().readText()
                } catch (_: Exception) {
                    ""
                }
                val err = try {
                    proc.errorStream.bufferedReader().readText()
                } catch (_: Exception) {
                    ""
                }
                val code = proc.exitValue()
                val combined = out + err
                val lines = combined.split("\n").let {
                    if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it
                }
                ChannelOutcome.Completed(
                    lines = lines,
                    exitCode = code,
                    tier = tier,
                )
            } finally {
                running.compareAndSet(proc, null)
            }
        } catch (e: Exception) {
            ChannelOutcome.Denied(e.message ?: "local-exec-failed")
        }
    }

    override suspend fun sendControl(key: ControlKey) {
        if (key == ControlKey.CTRL_C) {
            try {
                running.get()?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    override suspend fun continueLoop() = Unit

    override suspend fun listWorkspace(path: String): WorkspaceListing =
        withContext(Dispatchers.IO) {
            try {
                val dir = resolve(path)
                if (!dir.exists() || !dir.isDirectory) {
                    return@withContext WorkspaceListing(cwd = path, error = "not-a-directory")
                }
                val cwd = try {
                    dir.canonicalPath
                } catch (_: Exception) {
                    dir.absolutePath
                }
                val entries = (dir.listFiles() ?: emptyArray())
                    .filter { it.name != "node_modules" && it.name != ".git" }
                    .map { f ->
                        WorkspaceEntry(
                            name = f.name,
                            path = try {
                                f.canonicalPath
                            } catch (_: Exception) {
                                f.absolutePath
                            },
                            isDirectory = f.isDirectory,
                        )
                    }
                    .sortedWith(compareByDescending<WorkspaceEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
                WorkspaceListing(cwd = cwd, entries = entries)
            } catch (e: Exception) {
                WorkspaceListing(cwd = path, error = e.message ?: "list-failed")
            }
        }

    override suspend fun readWorkspaceFile(path: String): FileContent =
        withContext(Dispatchers.IO) {
            try {
                val f = resolve(path)
                if (!f.exists() || !f.isFile) {
                    return@withContext FileContent(path = path, error = "not-found")
                }
                if (f.length() > 204_800) {
                    return@withContext FileContent(path = path, error = "preview-skipped-large")
                }
                val bytes = f.readBytes()
                if (bytes.contains(0.toByte())) {
                    return@withContext FileContent(path = path, error = "preview-skipped-binary")
                }
                val text = bytes.toString(Charsets.UTF_8)
                val lines = text.split("\n").let {
                    if (it.isNotEmpty() && it.last() == "") it.dropLast(1) else it
                }.take(200)
                FileContent(path = path, lines = lines, isMarkdown = f.name.endsWith(".md", ignoreCase = true))
            } catch (e: Exception) {
                FileContent(path = path, error = e.message ?: "read-failed")
            }
        }

    // The pVM is not this channel's job — TerminalViewModel routes guest
    // ops to the VM channel. Report honestly so the sidebar shows the
    // on-device state instead of fake-ready.
    override suspend fun guestStatus(): GuestStatus =
        GuestStatus(missing = listOf("Debian pVM (on-device shell active)"))

    override suspend fun bootGuest(): GuestOpResult =
        GuestOpResult(ok = false, remediation = "boot unsupported on on-device shell")

    override suspend fun provisionGuest(): GuestOpResult =
        GuestOpResult(ok = false, remediation = "provision unsupported on on-device shell")
}
