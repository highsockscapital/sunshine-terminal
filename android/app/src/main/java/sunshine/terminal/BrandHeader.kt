// Sunshine BrandHeader — screen title / brand header.
// Spec: high-contrast bold dark text (#161610 = SunshineTokens.textPrimary),
// clean high-legibility sans-serif (FontFamily.Default → Roboto/Inter).
// Monospace is reserved for terminal output + code; titles stay sans.
package sunshine.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import sunshine.design.SunshineTokens
import sunshine.design.SunshineType

@Composable
fun BrandHeader(
    text: String = "Housewife",
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = SunshineType.screenTitle,
        maxLines = 1,
        modifier = modifier,
    )
}

@Preview
@Composable
private fun BrandHeaderPreview() {
    MaterialTheme {
        BrandHeader()
    }
}
