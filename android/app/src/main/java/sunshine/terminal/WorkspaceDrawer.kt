// Sunshine WorkspaceDrawer — touch file drawer + preview (SPEC for app module).
// Mirrors CLI drawer.js / workspace.js:
//   listWorkspace → left tree (dirs first, node_modules/.git hidden)
//   previewLinesFor → right preview (rich markdown via FilePreviewRich)
// Backend: GuestChannel.listWorkspace / readWorkspaceFile (VsockGuestChannel
// runs pwd+ls and sed inside the live Debian guest).
package sunshine.terminal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sunshine.design.SunshineShape
import sunshine.design.SunshineTokens

@Composable
fun WorkspaceDrawer(
    workspace: WorkspaceListing,
    selectedFile: FileContent?,
    onToggle: () -> Unit,
    onRefresh: () -> Unit,
    onParent: () -> Unit,
    onOpen: (WorkspaceEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = SunshineShape.canvas,
        colors = CardDefaults.cardColors(containerColor = SunshineTokens.cardSurface),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "📁 ${workspace.cwd}",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = SunshineTokens.textPrimary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onParent) { Text("↑") }
                TextButton(onClick = onRefresh) { Text("↻") }
                TextButton(onClick = onToggle) { Text("✕") }
            }
            if (workspace.error != null) {
                Text(
                    text = "Cannot list guest FS (${workspace.error}). Is the pVM booted?",
                    fontSize = 12.sp,
                    color = SunshineTokens.error,
                )
            } else if (workspace.entries.isEmpty()) {
                Text(
                    text = "Empty directory — guest returned no entries.",
                    fontSize = 12.sp,
                    color = SunshineTokens.textSecondary,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(workspace.entries, key = { it.path }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(entry) }
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = if (entry.isDirectory) "📁" else "📄",
                                fontSize = 13.sp,
                            )
                            Text(
                                text = entry.name + if (entry.isDirectory) "/" else "",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp,
                                color = SunshineTokens.textPrimary,
                            )
                        }
                    }
                }
            }
            selectedFile?.let { file ->
                if (file.error != null) {
                    Text(
                        text = "Cannot read ${file.path} (${file.error})",
                        fontSize = 12.sp,
                        color = SunshineTokens.error,
                    )
                } else {
                    FilePreviewRich(file = file, collapsedLines = 60)
                }
            }
        }
    }
}

@Preview
@Composable
private fun WorkspaceDrawerPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkspaceDrawer(
                workspace = WorkspaceListing(
                    cwd = "/home/sunshine",
                    entries = listOf(
                        WorkspaceEntry("guest", "/home/sunshine/guest", true),
                        WorkspaceEntry("README.md", "/home/sunshine/README.md", false),
                        WorkspaceEntry("run.sh", "/home/sunshine/run.sh", false),
                    ),
                ),
                selectedFile = FileContent(
                    path = "/home/sunshine/README.md",
                    lines = listOf("# Hello", "", "Guest filesystem preview."),
                    isMarkdown = true,
                ),
                onToggle = {}, onRefresh = {}, onParent = {}, onOpen = {},
            )
        }
    }
}
