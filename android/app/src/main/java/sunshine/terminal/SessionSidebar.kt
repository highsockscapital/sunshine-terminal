// Sunshine SessionSidebar — collapsible navigation drawer (sessions + filetree).
// Sections top→bottom: Sessions (switch/new/delete), Files (cwd + tree + preview).
// Backed by TerminalViewModel sessions + GuestChannel workspace listing.
package sunshine.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sunshine.design.SunshineTokens

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionSidebar(
    state: TerminalUiState,
    onCreateSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onRenameSession: (String, String) -> Unit = { _, _ -> },
    onRefreshWorkspace: () -> Unit,
    onWorkspaceParent: () -> Unit,
    onOpenWorkspace: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<TerminalSession?>(null) }
    var renameText by remember { mutableStateOf("") }
    // Single LazyColumn (no nested scroll): sessions + filetree + preview
    // are sections of one scrollable list.
    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 280.dp, max = 340.dp)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "brand") { BrandHeader() }
        // ---- Sessions ----
        item(key = "sessions-header") {
            Text(
                text = "Sessions (${state.sessions.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = SunshineTokens.textPrimary,
            )
        }
        if (state.sessions.isEmpty()) {
            item(key = "sessions-empty") {
                Text(
                    text = "No sessions yet.",
                    fontSize = 12.sp,
                    color = SunshineTokens.textSecondary,
                )
            }
        } else {
            items(state.sessions, key = { "sess-${it.id}" }) { sess ->
                val active = sess.id == state.activeSessionId
                Box {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { onSwitchSession(sess.id) },
                                onLongClick = { menuFor = sess.id },
                            )
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = if (active) "●" else "○",
                            fontSize = 12.sp,
                            color = if (active) SunshineTokens.primaryAccent
                            else SunshineTokens.textSecondary,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = sess.title,
                                fontSize = 13.sp,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                color = SunshineTokens.textPrimary,
                            )
                            Text(
                                text = "${sess.blocks.size} blocks · ${sess.history.size} cmds",
                                fontSize = 11.sp,
                                color = SunshineTokens.textSecondary,
                            )
                        }
                        if (state.sessions.size > 1) {
                            TextButton(onClick = { onDeleteSession(sess.id) }) {
                                Text("✕", fontSize = 12.sp)
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = menuFor == sess.id,
                        onDismissRequest = { menuFor = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                renameText = sess.title
                                renaming = sess
                                menuFor = null
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            enabled = state.sessions.size > 1,
                            onClick = {
                                onDeleteSession(sess.id)
                                menuFor = null
                            },
                        )
                    }
                }
            }
        }
        item(key = "new-session") {
            OutlinedButton(onClick = onCreateSession, modifier = Modifier.fillMaxWidth()) {
                Text("+ New session", fontSize = 13.sp)
            }
        }

        item(key = "divider") { HorizontalDivider() }

        // ---- Filetree ----
        item(key = "files-header") {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "📁 ${state.workspace.cwd}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = SunshineTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onWorkspaceParent) { Text("↑") }
                TextButton(onClick = onRefreshWorkspace) { Text("↻") }
            }
        }
        if (state.workspace.error != null) {
            item(key = "files-error") {
                Text(
                    text = "Cannot list guest FS (${state.workspace.error}). Is the pVM booted?",
                    fontSize = 12.sp,
                    color = SunshineTokens.error,
                )
            }
        } else if (state.workspace.entries.isEmpty()) {
            item(key = "files-empty") {
                Text(
                    text = "Empty directory.",
                    fontSize = 12.sp,
                    color = SunshineTokens.textSecondary,
                )
            }
        } else {
            items(state.workspace.entries, key = { "file-${it.path}" }) { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenWorkspace(entry) }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = if (entry.isDirectory) "📁" else "📄", fontSize = 13.sp)
                    Text(
                        text = entry.name + if (entry.isDirectory) "/" else "",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = SunshineTokens.textPrimary,
                    )
                }
            }
        }
        state.selectedFile?.let { file ->
            item(key = "preview") {
                if (file.error != null) {
                    Text(
                        text = "Cannot read ${file.path} (${file.error})",
                        fontSize = 12.sp,
                        color = SunshineTokens.error,
                    )
                } else {
                    FilePreviewRich(file = file)
                }
            }
        }
    }
    // Rename dialog (opened from the long-press menu).
    renaming?.let { target ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename session", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    placeholder = { Text(target.title) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onRenameSession(target.id, renameText)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renaming = null }) { Text("Cancel") }
            },
        )
    }
}

@Preview
@Composable
private fun SessionSidebarPreview() {
    MaterialTheme {
        SessionSidebar(
            state = TerminalUiState(
                sessions = listOf(
                    TerminalSession(id = "s1", title = "Session 1"),
                    TerminalSession(id = "s2", title = "Session 2"),
                ),
                activeSessionId = "s1",
                workspace = WorkspaceListing(
                    cwd = "/home/sunshine",
                    entries = listOf(
                        WorkspaceEntry("guest", "/home/sunshine/guest", true),
                        WorkspaceEntry("README.md", "/home/sunshine/README.md", false),
                    ),
                ),
            ),
            onCreateSession = {}, onSwitchSession = {}, onDeleteSession = {},
            onRefreshWorkspace = {}, onWorkspaceParent = {}, onOpenWorkspace = {},
        )
    }
}
