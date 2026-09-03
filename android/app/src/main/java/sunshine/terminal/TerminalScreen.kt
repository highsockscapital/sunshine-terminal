// Sunshine TerminalScreen — assembles canvas + rail + input + modals (SPEC).
// Layout top→bottom: connection/power-saver banner, block canvas,
// loop-pause card, input row, touch rail. Approval modal overlays.
package sunshine.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import sunshine.design.SunshineShape
import sunshine.design.SunshineTokens

@Composable
fun TerminalScreen(
    viewModel: TerminalViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    TerminalScreenContent(
        state = state,
        railContext = viewModel.railContext(),
        onInputChange = viewModel::onInputChange,
        onSend = { viewModel.send() },
        onRailInsert = viewModel::onRailInsert,
        onRailControl = viewModel::onRailControl,
        onApprove = viewModel::approvePending,
        onDeny = viewModel::denyPending,
        onContinueLoop = viewModel::continueLoop,
        modifier = modifier,
    )
}

@Composable
fun TerminalScreenContent(
    state: TerminalUiState,
    railContext: RailContext,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onRailInsert: (String) -> Unit,
    onRailControl: (ControlKey) -> Unit,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
    onContinueLoop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        containerColor = SunshineTokens.windowBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Status banner: connection dot + power saver chip.
            StatusBanner(state = state)
            // Block canvas takes all remaining space.
            TerminalBlockCanvas(
                blocks = state.blocks,
                modifier = Modifier.weight(1f),
            )
            // Loop pause card (agent-step-cap).
            state.loopPause?.let { pause ->
                Card(
                    shape = SunshineShape.modal,
                    colors = CardDefaults.cardColors(containerColor = SunshineTokens.cardSurface),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Sunshine has run ${pause.steps} commands. Tap to continue execution.",
                            fontWeight = FontWeight.Bold,
                            color = SunshineTokens.textPrimary,
                        )
                        Button(
                            onClick = onContinueLoop,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SunshineTokens.primaryAccent,
                                contentColor = SunshineTokens.onPrimaryAccent,
                            ),
                        ) {
                            Text("Continue")
                        }
                    }
                }
            }
            // Input row.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    placeholder = { Text("sunshine ❯", fontFamily = FontFamily.Monospace) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = onSend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SunshineTokens.primaryAccent,
                        contentColor = SunshineTokens.onPrimaryAccent,
                    ),
                ) {
                    Text("Send")
                }
            }
            // Touch rail above the keyboard zone.
            TouchActionRail(
                context = railContext,
                onInsert = onRailInsert,
                onControl = onRailControl,
            )
        }
        // Approval modal overlays everything.
        state.pendingApproval?.let { req ->
            RiskApprovalCard(
                request = req,
                onApprove = onApprove,
                onDeny = onDeny,
            )
        }
    }
}

@Composable
private fun StatusBanner(state: TerminalUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val dot = when (state.connection) {
            ConnectionState.CONNECTED -> "● connected"
            ConnectionState.DEGRADED -> "◐ degraded"
            ConnectionState.LOST -> "○ guest lost"
        }
        Text(text = dot, fontSize = 12.sp, color = SunshineTokens.textSecondary)
        if (state.powerSaver) {
            Text(
                text = "Power Saver: Sunshine throttled to prevent overheating.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SunshineTokens.primaryAccent,
            )
        }
    }
}

/** In-memory channel for previews: echoes input, never touches a guest. */
class FakeGuestChannel : GuestChannel {
    override val stdout: Flow<ChannelLine> = emptyFlow()
    override val thermal: Flow<ThermalSnapshot> = emptyFlow()
    override val connection: Flow<ConnectionState> = emptyFlow()
    private var n = 0L
    override suspend fun exec(command: String, origin: String, approved: Boolean): ChannelOutcome =
        ChannelOutcome.Completed(
            lines = listOf("(preview) ran: $command"),
            exitCode = 0,
            tier = RiskTier.SAFE,
        )
    override suspend fun sendControl(key: ControlKey) = Unit
    override suspend fun continueLoop() = Unit
}

@Preview
@Composable
private fun TerminalScreenPreview() {
    MaterialTheme {
        TerminalScreen(viewModel = TerminalViewModel(FakeGuestChannel()))
    }
}
