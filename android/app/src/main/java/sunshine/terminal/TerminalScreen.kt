// Sunshine TerminalScreen — assembles canvas + rail + input + modals (SPEC).
// Layout top→bottom: connection/power-saver banner, file drawer, block
// canvas, sticky footer (touch rail ABOVE input row, imePadding-lifted).
// Approval modal overlays.
package sunshine.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
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
        onRefreshWorkspace = { viewModel.refreshWorkspace(state.workspace.cwd) },
        onWorkspaceParent = viewModel::goWorkspaceParent,
        onOpenWorkspace = viewModel::openWorkspacePath,
        onToggleSidebar = viewModel::toggleSidebar,
        onCloseSidebar = { viewModel.setSidebarOpen(false) },
        onCreateSession = viewModel::createSession,
        onSwitchSession = viewModel::switchSession,
        onDeleteSession = viewModel::deleteSession,
        onRenameSession = viewModel::renameSession,
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
    onRefreshWorkspace: () -> Unit = {},
    onWorkspaceParent: () -> Unit = {},
    onOpenWorkspace: (WorkspaceEntry) -> Unit = {},
    onToggleSidebar: () -> Unit = {},
    onCloseSidebar: () -> Unit = {},
    onCreateSession: () -> Unit = {},
    onSwitchSession: (String) -> Unit = {},
    onDeleteSession: (String) -> Unit = {},
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    // Sync ViewModel sidebar flag → drawer sheet.
    LaunchedEffect(state.sidebarOpen) {
        if (state.sidebarOpen && !drawerState.isOpen) drawerState.open()
        else if (!state.sidebarOpen && drawerState.isOpen) drawerState.close()
    }
    // Sync sheet dismiss (edge swipe / scrim) → ViewModel flag.
    LaunchedEffect(drawerState.currentValue) {
        if (drawerState.currentValue == DrawerValue.Closed && state.sidebarOpen) {
            onCloseSidebar()
        }
    }
    val activeTitle = state.sessions.firstOrNull { it.id == state.activeSessionId }?.title
        ?: "Session"
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionSidebar(
                    state = state,
                    onCreateSession = onCreateSession,
                    onSwitchSession = { id ->
                        onSwitchSession(id)
                        scope.launch { drawerState.close() }
                    },
                    onDeleteSession = onDeleteSession,
                    onRenameSession = onRenameSession,
                    onRefreshWorkspace = onRefreshWorkspace,
                    onWorkspaceParent = onWorkspaceParent,
                    onOpenWorkspace = onOpenWorkspace,
                )
            }
        },
        modifier = modifier,
    ) {
    Scaffold(
        containerColor = SunshineTokens.windowBackground,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top bar: circular sidebar button (white bg, dark border, icon).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = CircleShape,
                    color = SunshineTokens.cardSurface,
                    border = BorderStroke(1.dp, SunshineTokens.strokeBorder),
                    modifier = Modifier.size(40.dp),
                    onClick = {
                        onToggleSidebar()
                        scope.launch {
                            if (drawerState.isClosed) drawerState.open()
                            else drawerState.close()
                        }
                    },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "☰",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = SunshineTokens.strokeBorder,
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    BrandHeader()
                    StatusBanner(state = state)
                    Text(
                        text = "$activeTitle · ${state.workspace.cwd} (${state.workspace.entries.size})",
                        fontFamily = FontFamily.Default,
                        fontSize = 11.sp,
                        color = SunshineTokens.textSecondary,
                    )
                }
            }
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
            // Input row + touch rail: sticky footer above the keyboard.
            // Rail sits ABOVE the input (spec) inside an imePadding block so
            // the chips ride up with the keyboard instead of hiding under it.
            // Previously the rail was below the input with no IME inset, so
            // the keyboard covered it — visible only when collapsed.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TouchActionRail(
                    context = railContext,
                    onInsert = onRailInsert,
                    onControl = onRailControl,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        placeholder = {
                            Text(
                                "sunshine ❯",
                                fontFamily = FontFamily.Monospace,
                                color = SunshineTokens.textSecondary,
                            )
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        shape = SunshineShape.canvas,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SunshineTokens.cardSurface,
                            unfocusedContainerColor = SunshineTokens.cardSurface,
                            disabledContainerColor = SunshineTokens.cardSurface,
                            focusedBorderColor = SunshineTokens.inputBorder,
                            unfocusedBorderColor = SunshineTokens.inputBorder,
                            disabledBorderColor = SunshineTokens.inputBorder,
                            focusedTextColor = SunshineTokens.textPrimary,
                            unfocusedTextColor = SunshineTokens.textPrimary,
                            disabledTextColor = SunshineTokens.textPrimary,
                            cursorColor = SunshineTokens.textPrimary,
                            focusedPlaceholderColor = SunshineTokens.textSecondary,
                            unfocusedPlaceholderColor = SunshineTokens.textSecondary,
                        ),
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
            }
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
}

@Composable
private fun StatusBanner(state: TerminalUiState) {
    // Connection dot removed per design — only the Power Saver warning remains,
    // rendered as an accent fill + dark text chip (contrast-safe: #161610 on
    // #FF9E43) instead of bare accent text on background.
    if (state.powerSaver) {
        androidx.compose.material3.Surface(
            shape = SunshineShape.badge,
            color = SunshineTokens.primaryAccent,
        ) {
            Text(
                text = "Power Saver: Sunshine throttled to prevent overheating.",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = SunshineTokens.onPrimaryAccent,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Preview-only channel for @Preview renders: echoes input, never touches a guest.
 *  Production uses VsockGuestChannel (VsockFrameMultiplexer → live Debian pVM).
 *  DEBUG-ONLY BY CONVENTION: never wire this into MainActivity — that is what
 *  causes Mock/Preview Mode. (No BuildConfig reference: the design module owns
 *  tokens; keep this file free of generated-code imports.) */
class FakeGuestChannel : GuestChannel {
    override val stdout: Flow<ChannelLine> = emptyFlow()
    override val thermal: Flow<ThermalSnapshot> = emptyFlow()
    override val connection: Flow<ConnectionState> = emptyFlow()
    override suspend fun exec(
        command: String,
        origin: String,
        approved: Boolean,
        blockId: Long?,
    ): ChannelOutcome {
        return ChannelOutcome.Completed(
            lines = listOf("(preview) ran: $command"),
            exitCode = 0,
            tier = RiskTier.SAFE,
        )
    }
    override suspend fun sendControl(key: ControlKey) = Unit
    override suspend fun continueLoop() = Unit
    override suspend fun listWorkspace(path: String): WorkspaceListing = WorkspaceListing(
        cwd = "/home/sunshine",
        entries = listOf(
            WorkspaceEntry("guest", "/home/sunshine/guest", true),
            WorkspaceEntry("README.md", "/home/sunshine/README.md", false),
        ),
    )
    override suspend fun readWorkspaceFile(path: String): FileContent = FileContent(
        path = path,
        lines = listOf("# Preview", "", "Fake file content for @Preview."),
        isMarkdown = path.endsWith(".md"),
    )
}

@Preview
@Composable
private fun TerminalScreenPreview() {
    MaterialTheme {
        TerminalScreen(viewModel = TerminalViewModel(FakeGuestChannel()))
    }
}
