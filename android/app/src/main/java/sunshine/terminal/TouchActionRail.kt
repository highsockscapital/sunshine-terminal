// Sunshine touch-first action rail (SPEC).
// Sits directly above the soft keyboard / input prompt: context-aware
// quick chips so mobile users rarely type full commands. Special keys
// (ctrl+c, tab) emit control events instead of text — a flat terminal
// view cannot do this without escape-sequence gymnastics.
package sunshine.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/** Control keys the rail can emit (handled by the ViewModel, not typed). */
enum class ControlKey { CTRL_C, TAB, ESC }

/** One rail chip: either text to insert or a control key to send. */
sealed interface RailAction {
    data class Insert(val text: String) : RailAction
    data class Control(val key: ControlKey) : RailAction
    // Human labels shown on the chips.
    val label: String
        get() = when (this) {
            is Insert -> text
            is Control -> when (key) {
                ControlKey.CTRL_C -> "ctrl+c"
                ControlKey.TAB -> "tab"
                ControlKey.ESC -> "esc"
            }
        }
}

/** Rail context follows the last command family (ViewModel decides). */
enum class RailContext { DEFAULT, GIT, DOCKER, GUEST }

fun railActionsFor(context: RailContext): List<RailAction> = buildList {
    add(RailAction.Control(ControlKey.CTRL_C))
    add(RailAction.Control(ControlKey.TAB))
    when (context) {
        RailContext.GIT -> {
            add(RailAction.Insert("git status"))
            add(RailAction.Insert("git log --oneline -5"))
            add(RailAction.Insert("git diff"))
        }
        RailContext.DOCKER -> {
            add(RailAction.Insert("docker ps"))
            add(RailAction.Insert("docker logs "))
            add(RailAction.Insert("| grep "))
        }
        RailContext.GUEST -> {
            add(RailAction.Insert("scli vm status"))
            add(RailAction.Insert("scli vm logs"))
            add(RailAction.Insert("scli vm thermal"))
        }
        RailContext.DEFAULT -> {
            add(RailAction.Insert("ls -la"))
            add(RailAction.Insert("| grep "))
            add(RailAction.Insert("scli vm status"))
        }
    }
}

@Composable
fun TouchActionRail(
    context: RailContext,
    onInsert: (String) -> Unit,
    onControl: (ControlKey) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(railActionsFor(context)) { action ->
            when (action) {
                is RailAction.Control -> FilterChip(
                    selected = false,
                    onClick = { onControl(action.key) },
                    label = { Text(action.label) },
                )
                is RailAction.Insert -> AssistChip(
                    onClick = { onInsert(action.text) },
                    label = { Text(action.label) },
                )
            }
        }
    }
}

@Preview
@Composable
private fun TouchActionRailPreview() {
    MaterialTheme {
        Column {
            TouchActionRail(context = RailContext.DEFAULT, onInsert = {}, onControl = {})
            TouchActionRail(context = RailContext.GIT, onInsert = {}, onControl = {})
        }
    }
}
