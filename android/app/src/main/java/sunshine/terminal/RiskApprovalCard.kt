// Sunshine RiskApprovalCard — Tier 2/3 interactive approval (SPEC).
// Backend counterpart: vm.js resolveVerdict + policy.js tiers. The CLI
// asks inline ([y/N], Tier 3 types YES); the app renders this modal with
// the same data: exact command, tier, origin, reason. Approve/deny maps
// to the engine `verdict: 'confirmed'` gate — Deny never executes.
package sunshine.terminal

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.window.Dialog
import sunshine.design.SunshineShape
import sunshine.design.SunshineTokens

@Composable
fun RiskApprovalCard(
    request: ApprovalRequest,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    Dialog(onDismissRequest = onDeny) {
        // Modal surface: 16dp radius per shape spec; error border for
        // Tier 3, standard outline for Tier 2.
        Card(
            shape = SunshineShape.modal,
            colors = CardDefaults.cardColors(containerColor = SunshineTokens.cardSurface),
            border = BorderStroke(
                2.dp,
                if (request.explicit) SunshineTokens.error else SunshineTokens.strokeBorder,
            ),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (request.explicit) "⛔ Destructive command" else "⚠ State-changing command",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = if (request.explicit) SunshineTokens.error else SunshineTokens.textPrimary,
                )
                // Exact command in a mono block — always shown, never trimmed.
                Card(
                    shape = SunshineShape.canvas,
                    colors = CardDefaults.cardColors(containerColor = SunshineTokens.surfaceVariant),
                ) {
                    SelectionContainer {
                        Text(
                            text = request.command,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = SunshineTokens.textPrimary,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
                // Plain-language breakdown (backend reason, humanized here).
                RiskRow(label = "Risk", value = if (request.tier == RiskTier.DESTRUCTIVE) "Tier 3 — data loss, privilege, or exposure possible" else "Tier 2 — changes guest state")
                RiskRow(label = "Origin", value = if (request.origin == "agent") "Agent autonomous command" else "Your command")
                RiskRow(label = "Why flagged", value = request.reason)
                // Tier 3 mirrors the CLI's explicit gate (vm.js: type YES):
                // a single tap must never fire a destructive command.
                var confirmText by remember(request.blockId, request.command) { mutableStateOf("") }
                if (request.explicit) {
                    OutlinedTextField(
                        value = confirmText,
                        onValueChange = { confirmText = it },
                        placeholder = { Text("Type YES to confirm") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) {
                        Text("Deny", style = sunshine.design.SunshineType.button)
                    }
                    Button(
                        onClick = onApprove,
                        enabled = !request.explicit || confirmText.trim() == "YES",
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (request.explicit) SunshineTokens.error else SunshineTokens.primaryAccent,
                            contentColor = if (request.explicit) SunshineTokens.cardSurface else SunshineTokens.onPrimaryAccent,
                        ),
                    ) {
                        Text(
                            if (request.explicit) "Approve anyway" else "Approve",
                            style = sunshine.design.SunshineType.button,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RiskRow(label: String, value: String) {
    Column {
        Text(text = label, fontSize = 12.sp, color = SunshineTokens.textSecondary)
        Text(text = value, fontSize = 14.sp, color = SunshineTokens.textPrimary)
    }
}

@Preview
@Composable
private fun RiskApprovalCardPreview() {
    MaterialTheme {
        RiskApprovalCard(
            request = ApprovalRequest(
                blockId = 7,
                command = "rm -rf /tmp/cache",
                tier = RiskTier.DESTRUCTIVE,
                origin = "agent",
                reason = "matched destructive pattern rm -rf",
                explicit = true,
            ),
            onApprove = {},
            onDeny = {},
        )
    }
}
