// Sunshine TerminalViewModel + VSOCK flow connector (SPEC for app module).
// Backend counterparts:
//   exec pipeline → vm.js executeAVF + resolveVerdict (ALLOW/CONFIRM/
//     CONFIRM_EXPLICIT/DENIED, agent-step-cap)
//   streaming    → bridge.js ring buffer (droppedLines surfaced per block)
//   thermal chip → thermal.js snapshot (powerSaver + reasons)
//   heartbeat    → heartbeat.js via VmControllerService (connection dot)
//
// The GuestChannel interface is transport-agnostic: the VSOCK multiplexer
// implements it in production; fakes drive @Previews and unit tests.
// Deps (Gradle): lifecycle-viewmodel, coroutines, compose runtime.
package sunshine.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/** One streamed line tagged to its block (stderr flagged for coloring). */
data class ChannelLine(val blockId: Long, val text: String, val stderr: Boolean = false)

/** Thermal snapshot feeding the Power Saver chip. */
data class ThermalSnapshot(
    val powerSaver: Boolean,
    val reasons: List<String> = emptyList(),
)

/** Backend exec outcomes, mirroring vm.js result shapes. */
sealed interface ChannelOutcome {
    data class Completed(
        val lines: List<String>,
        val exitCode: Int,
        val tier: RiskTier,
        val droppedLines: Int = 0,
        val powerSaver: Boolean = false,
    ) : ChannelOutcome

    data class NeedsApproval(val request: ApprovalRequest) : ChannelOutcome
    data class Denied(val reason: String) : ChannelOutcome
    data class LoopPaused(val steps: Int, val cap: Int) : ChannelOutcome
}

/** Transport port. Production impl: Kotlin VSOCK multiplexer over the
 *  host↔guest channel (token handshake + ring-buffered stdout/stderr). */
interface GuestChannel {
    /** Hot stream of output lines; ViewModel routes by blockId. */
    val stdout: Flow<ChannelLine>
    /** Thermal snapshots for the Power Saver chip. */
    val thermal: Flow<ThermalSnapshot>
    /** Connection health for the status dot. */
    val connection: Flow<ConnectionState>
    /** Execute; approved=true carries the user's modal confirmation. */
    suspend fun exec(command: String, origin: String, approved: Boolean = false): ChannelOutcome
    /** Control keys (ctrl+c → SIGINT the running guest command, etc). */
    suspend fun sendControl(key: ControlKey)
    /** Reset the agent step counter (vm continue). */
    suspend fun continueLoop()
}

class TerminalViewModel(
    private val channel: GuestChannel,
) : ViewModel() {

    private val ids = AtomicLong(1L)
    private val _state = MutableStateFlow(TerminalUiState())
    val state: StateFlow<TerminalUiState> = _state.asStateFlow()

    private var streamJob: Job? = null
    private var railContext = RailContext.DEFAULT

    init {
        streamJob = viewModelScope.launch {
            channel.stdout.collect { line ->
                _state.update { s ->
                    s.copy(blocks = s.blocks.map { b ->
                        if (b.id == line.blockId) b.copy(lines = b.lines + line.text) else b
                    })
                }
            }
        }
        viewModelScope.launch {
            channel.thermal.collect { t ->
                _state.update { it.copy(powerSaver = t.powerSaver, powerSaverReasons = t.reasons) }
            }
        }
        viewModelScope.launch {
            channel.connection.collect { c ->
                _state.update { it.copy(connection = c) }
            }
        }
    }

    fun onInputChange(text: String) {
        _state.update { it.copy(input = text) }
    }

    fun railContext(): RailContext = railContext

    fun onRailInsert(text: String) {
        _state.update { it.copy(input = it.input + text) }
    }

    fun onRailControl(key: ControlKey) {
        viewModelScope.launch { channel.sendControl(key) }
    }

    fun send(origin: String = "human") {
        val command = _state.value.input.trim()
        if (command.isEmpty()) return
        railContext = deriveRailContext(command)
        val block = TerminalBlock(
            id = ids.getAndIncrement(),
            command = command,
            origin = origin,
            status = BlockStatus.RUNNING,
        )
        _state.update {
            it.copy(
                blocks = it.blocks + block,
                input = "",
                history = (it.history + command).takeLast(100),
            )
        }
        viewModelScope.launch {
            when (val out = channel.exec(command, origin)) {
                is ChannelOutcome.Completed -> _state.update { s ->
                    s.copy(blocks = s.blocks.map { b ->
                        if (b.id == block.id) {
                            b.copy(
                                lines = b.lines + out.lines,
                                exitCode = out.exitCode,
                                tier = out.tier,
                                status = if (out.exitCode == 0) BlockStatus.DONE else BlockStatus.FAILED,
                                droppedLines = out.droppedLines,
                                powerSaver = out.powerSaver,
                            )
                        } else b
                    })
                }
                is ChannelOutcome.NeedsApproval -> _state.update {
                    it.copy(pendingApproval = out.request)
                }
                is ChannelOutcome.Denied -> _state.update { s ->
                    s.copy(blocks = s.blocks.map { b ->
                        if (b.id == block.id) b.copy(status = BlockStatus.DENIED) else b
                    })
                }
                is ChannelOutcome.LoopPaused -> _state.update {
                    it.copy(loopPause = LoopPause(out.steps, out.cap))
                }
            }
        }
    }

    fun approvePending() {
        val req = _state.value.pendingApproval ?: return
        _state.update { it.copy(pendingApproval = null) }
        viewModelScope.launch {
            // Re-exec with the user's confirmation (engine verdict path).
            when (val out = channel.exec(req.command, req.origin, approved = true)) {
                is ChannelOutcome.Completed -> _state.update { s ->
                    s.copy(blocks = s.blocks.map { b ->
                        if (b.id == req.blockId) {
                            b.copy(
                                lines = b.lines + out.lines,
                                exitCode = out.exitCode,
                                status = if (out.exitCode == 0) BlockStatus.DONE else BlockStatus.FAILED,
                            )
                        } else b
                    })
                }
                else -> _state.update { s ->
                    s.copy(blocks = s.blocks.map { b ->
                        if (b.id == req.blockId) b.copy(status = BlockStatus.DENIED) else b
                    })
                }
            }
        }
    }

    fun denyPending() {
        val req = _state.value.pendingApproval ?: return
        _state.update { s ->
            s.copy(
                pendingApproval = null,
                blocks = s.blocks.map { b ->
                    if (b.id == req.blockId) b.copy(status = BlockStatus.DENIED) else b
                },
            )
        }
    }

    fun continueLoop() {
        viewModelScope.launch {
            channel.continueLoop()
            _state.update { it.copy(loopPause = null) }
        }
    }

    fun clear() {
        _state.update { it.copy(blocks = emptyList()) }
    }

    private fun deriveRailContext(command: String): RailContext = when {
        command.startsWith("git ") -> RailContext.GIT
        command.startsWith("docker ") -> RailContext.DOCKER
        command.startsWith("scli vm") -> RailContext.GUEST
        else -> RailContext.DEFAULT
    }

    override fun onCleared() {
        streamJob?.cancel()
        super.onCleared()
    }
}
