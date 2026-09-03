// Sunshine TerminalBlockCanvas — structured-block terminal output (SPEC).
// Why blocks, not a flat ANSI view: desktop terminal paradigms break on
// touchscreens (tiny text, keyboard stealing 60% of the screen, broken
// selection). Each command + its stream is a discrete, natively selectable
// card; long outputs collapse behind an expansion chip instead of
// crushing scroll performance.
package sunshine.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sunshine.design.SunshineShape
import sunshine.design.SunshineTokens

/** Lines shown before the "Show full log" chip appears. */
const val BLOCK_COLLAPSE_AFTER = 50

@Composable
fun TerminalBlockCanvas(
    blocks: List<TerminalBlock>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    // Follow the tail while new output streams in.
    LaunchedEffect(blocks.size, blocks.lastOrNull()?.lines?.size) {
        if (blocks.isNotEmpty()) {
            listState.animateScrollToItem(blocks.size - 1)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(blocks, key = { it.id }) { block ->
            TerminalBlockCard(block = block)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TerminalBlockCard(block: TerminalBlock) {
    // Chat layout: user command bubble RIGHT (white #ffffff, rounded),
    // guest result card LEFT (surfaceVariant, as before).
    // Long-press anywhere on the block opens copy actions (command / output /
    // all). Free-form select + select-all handles come from the
    // SelectionContainers below; paste lives in the input field.
    var menu by remember(block.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val outputText = remember(block.lines) { block.lines.joinToString("\n") }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = { menu = true },
                ),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
        // Outgoing: right-aligned white bubble.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Card(
                shape = SunshineShape.canvas,
                colors = CardDefaults.cardColors(containerColor = SunshineTokens.chatBubbleBg),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                border = BorderStroke(1.dp, SunshineTokens.strokeBorder),
                modifier = Modifier.fillMaxWidth(0.85f),
            ) {
                SelectionContainer {
                    Text(
                        text = block.command,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SunshineTokens.chatBubbleText,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
        }
        // Incoming: left-aligned result, unchanged styling.
        Card(
            shape = SunshineShape.canvas,
            colors = CardDefaults.cardColors(containerColor = SunshineTokens.surfaceVariant),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = when (block.status) {
                BlockStatus.FAILED -> BorderStroke(1.dp, SunshineTokens.error)
                BlockStatus.DENIED -> BorderStroke(1.dp, SunshineTokens.strokeBorderLight)
                else -> null
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Origin + tier meta row.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BlockMetaChip(
                        text = if (block.origin == "agent") "agent" else "human",
                        accent = block.origin == "agent",
                    )
                    if (block.tier != RiskTier.SAFE) {
                        BlockMetaChip(
                            text = if (block.tier == RiskTier.DESTRUCTIVE) "tier 3" else "tier 2",
                            accent = block.tier == RiskTier.DESTRUCTIVE,
                        )
                    }
                    if (block.powerSaver) {
                        BlockMetaChip(text = "power saver", accent = true)
                    }
                }
                // Output body with collapse.
                OutputBody(block = block)
                // Status footer.
                BlockFooter(block = block)
            }
        }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Copy command") },
                onClick = {
                    clipboard.setText(AnnotatedString(block.command))
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Copy output") },
                enabled = outputText.isNotEmpty(),
                onClick = {
                    clipboard.setText(AnnotatedString(outputText))
                    menu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Copy all") },
                onClick = {
                    clipboard.setText(AnnotatedString("sunshine ❯ ${block.command}\n$outputText"))
                    menu = false
                },
            )
        }
    }
}

@Composable
private fun OutputBody(block: TerminalBlock) {
    var expanded by remember(block.id) { mutableStateOf(false) }
    val visible = if (expanded || block.lines.size <= BLOCK_COLLAPSE_AFTER) {
        block.lines
    } else {
        block.lines.take(BLOCK_COLLAPSE_AFTER)
    }
    SelectionContainer {
        Text(
            text = visible.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            color = SunshineTokens.textPrimary,
        )
    }
    if (!expanded && block.lines.size > BLOCK_COLLAPSE_AFTER) {
        AssistChip(
            onClick = { expanded = true },
            label = { Text("Show full log (${block.lines.size} lines)") },
        )
    }
}

@Composable
private fun BlockFooter(block: TerminalBlock) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (block.status) {
            BlockStatus.RUNNING -> BlockMetaChip(text = "running…", accent = true)
            BlockStatus.DONE -> BlockMetaChip(
                text = if ((block.exitCode ?: 0) == 0) "✓ exit 0" else "exit ${block.exitCode}",
                accent = false,
            )
            BlockStatus.FAILED -> BlockMetaChip(text = "✕ failed", accent = false)
            BlockStatus.DENIED -> BlockMetaChip(text = "denied", accent = false)
        }
        if (block.droppedLines > 0) {
            BlockMetaChip(text = "truncated ${block.droppedLines} lines", accent = false)
        }
    }
}

@Composable
private fun BlockMetaChip(text: String, accent: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp), // badge radius per shape spec
        color = if (accent) SunshineTokens.primaryAccent else SunshineTokens.cardSurface,
        border = BorderStroke(1.dp, SunshineTokens.strokeBorderLight),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (accent) SunshineTokens.onPrimaryAccent else SunshineTokens.textSecondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Preview
@Composable
private fun TerminalBlockCardPreview() {
    MaterialTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TerminalBlockCard(
                TerminalBlock(
                    id = 1,
                    command = "apt install htop",
                    origin = "agent",
                    tier = RiskTier.STATE_CHANGE,
                    lines = listOf("Reading package lists…", "Setting up htop (3.3.0)…"),
                    exitCode = 0,
                    status = BlockStatus.DONE,
                ),
            )
            TerminalBlockCard(
                TerminalBlock(
                    id = 2,
                    command = "rm -rf /tmp/cache",
                    tier = RiskTier.DESTRUCTIVE,
                    lines = emptyList(),
                    status = BlockStatus.DENIED,
                ),
            )
        }
    }
}
