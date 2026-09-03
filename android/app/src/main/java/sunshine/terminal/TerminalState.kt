// Sunshine terminal canvas — shared UI models (SPEC for the app module).
// Mirrors the backend contracts 1:1 so CLI and app render identically:
//   policy.js  → RiskTier / ApprovalVerdict
//   provider.js → ExecResult.ok / reason / droppedLines
//   vm.js      → AGENT_STEP_COPY (loop pause), POWER_SAVER_COPY (chip)
//   heartbeat  → ConnectionState
package sunshine.terminal

/** Backend risk tiers (policy.js TIERS). */
enum class RiskTier { SAFE, STATE_CHANGE, DESTRUCTIVE }

/** Backend verdicts (policy.js decideExec + vm.js resolveVerdict). */
enum class ApprovalVerdict { ALLOW, CONFIRM, CONFIRM_EXPLICIT, DENIED }

/** Lifecycle of one executed block. */
enum class BlockStatus { RUNNING, DONE, FAILED, DENIED }

/** Guest channel health for the connection dot. */
enum class ConnectionState { CONNECTED, DEGRADED, LOST }

/** One command + its output stream, rendered as a single card. */
data class TerminalBlock(
    val id: Long,
    val command: String,
    val origin: String = "human", // human | agent
    val tier: RiskTier = RiskTier.SAFE,
    val lines: List<String> = emptyList(),
    val exitCode: Int? = null,
    val status: BlockStatus = BlockStatus.RUNNING,
    val droppedLines: Int = 0, // ring-buffer evictions (bridge.js)
    val powerSaver: Boolean = false, // exec ran throttled (thermal.js)
)

/** Pending approval surfaced by the backend (vm.js resolveVerdict). */
data class ApprovalRequest(
    val blockId: Long,
    val command: String,
    val tier: RiskTier,
    val origin: String,
    val reason: String,
    val explicit: Boolean, // true = Tier 3 "type YES" equivalent
)

/** Agent loop pause (vm.js agent-step-cap). */
data class LoopPause(
    val steps: Int,
    val cap: Int,
)

/** Whole-screen state held by TerminalViewModel. */
data class TerminalUiState(
    val blocks: List<TerminalBlock> = emptyList(),
    val pendingApproval: ApprovalRequest? = null,
    val loopPause: LoopPause? = null,
    val powerSaver: Boolean = false,
    val powerSaverReasons: List<String> = emptyList(),
    val connection: ConnectionState = ConnectionState.CONNECTED,
    val input: String = "",
    val history: List<String> = emptyList(),
)
