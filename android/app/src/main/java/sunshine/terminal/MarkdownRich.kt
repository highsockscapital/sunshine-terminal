// Sunshine MarkdownRich — rich markdown preview for Compose (zero deps).
// Mirrors src/markdown.js block/inline coverage:
//   blocks: headings, fenced code, quotes, lists (bullet/ordered/task,
//     nested), tables, hr, paragraphs
//   inline: bold, italic, `code`, links, images (![alt](src))
// Theme roles come from SunshineTokens (accent/primary/secondary/error).
package sunshine.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sunshine.design.SunshineTokens

internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class CodeFence(val lang: String, val lines: List<String>) : MdBlock
    data class Quote(val lines: List<String>) : MdBlock
    data class ListBlock(val items: List<MdListItem>) : MdBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Hr : MdBlock
}

internal data class MdListItem(
    val ordered: Boolean,
    val index: Int,
    val taskDone: Boolean?,
    val indent: Int,
    val text: String,
)

/** Max chars per line fed to inline regex formatting (mirrors
 *  markdown.js INLINE_BUDGET — lazy-quantifier regexes backtrack
 *  catastrophically on huge single lines). Past the budget, plain text. */
internal const val INLINE_BUDGET = 4000

internal fun isTableDelim(line: String): Boolean =
    Regex("""^\s*\|?(\s*:?-+:?\s*\|)+\s*$""").containsMatchIn(line)

internal fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split("|").map { it.trim() }

internal fun parseBlocks(lines: List<String>): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        // Fenced code
        val fence = Regex("""^```(\w*)\s*$""").matchEntire(line)
        if (fence != null) {
            val lang = fence.groupValues[1]
            val buf = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].startsWith("```")) {
                buf.add(lines[i])
                i++
            }
            i++ // consume closing fence (or EOF)
            out.add(MdBlock.CodeFence(lang, buf))
            continue
        }
        // Heading
        val h = Regex("""^(#{1,6})\s+(.*)$""").matchEntire(line)
        if (h != null) {
            out.add(MdBlock.Heading(h.groupValues[1].length, h.groupValues[2]))
            i++
            continue
        }
        // Hr
        if (Regex("""^\s*(---|\*\*\*|___)\s*$""").matches(line)) {
            out.add(MdBlock.Hr)
            i++
            continue
        }
        // Quote run
        if (Regex("""^\s*>""").containsMatchIn(line)) {
            val buf = mutableListOf<String>()
            while (i < lines.size && Regex("""^\s*>""").containsMatchIn(lines[i])) {
                buf.add(lines[i].replaceFirst(Regex("""^\s*> ?"""), ""))
                i++
            }
            out.add(MdBlock.Quote(buf))
            continue
        }
        // Table
        if (line.contains("|") && i + 1 < lines.size && isTableDelim(lines[i + 1])) {
            val header = splitRow(line)
            i += 2
            val rows = mutableListOf<List<String>>()
            while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                rows.add(splitRow(lines[i]))
                i++
            }
            out.add(MdBlock.Table(header, rows))
            continue
        }
        // List run
        val lm = Regex("""^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$""").matchEntire(line)
        if (lm != null) {
            val items = mutableListOf<MdListItem>()
            var ord = 1
            while (i < lines.size) {
                val m = Regex("""^(\s*)([-*+]|\d+[.)])\s+(\[[ xX]\]\s+)?(.*)$""").matchEntire(lines[i])
                    ?: break
                val ordered = Regex("""^\d+[.)]$""").matches(m.groupValues[2])
                val taskRaw = m.groupValues[3]
                val done = if (taskRaw.isNotEmpty()) taskRaw.contains("x", true) else null
                items.add(
                    MdListItem(
                        ordered = ordered,
                        index = ord++,
                        taskDone = done,
                        indent = (m.groupValues[1].length / 2).coerceIn(0, 4),
                        text = m.groupValues[4],
                    ),
                )
                i++
            }
            out.add(MdBlock.ListBlock(items))
            continue
        }
        // Blank
        if (line.isBlank()) {
            i++
            continue
        }
        out.add(MdBlock.Paragraph(line))
        i++
    }
    return out
}

private fun inlineRich(
    text: String,
    base: Color,
    accent: Color = SunshineTokens.primaryAccent,
    secondary: Color = SunshineTokens.textSecondary,
): AnnotatedString {
    // Protect `code` spans first, then links/images/bold/italic.
    val codes = mutableListOf<String>()
    val src = if (text.length > INLINE_BUDGET) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = base)) {
                append(text.take(INLINE_BUDGET))
            }
            withStyle(SpanStyle(color = secondary)) { append("…[line truncated]") }
        }
    } else {
        text
    }
    var t = Regex("`([^`]+?)`").replace(src) {
        codes.add(it.groupValues[1])
        "${codes.size - 1}"
    }
    data class Rep(val start: Int, val end: Int, val build: (AnnotatedString.Builder) -> Unit)
    // Collect plain segments via token scan for **[bold]*, __bold__, *it*, _it_,
    // [label](url), ![alt](src). Simple left-to-right scan keeps nesting sane.
    return buildAnnotatedString {
        var i = 0
        fun pushPlain(s: String) {
            withStyle(SpanStyle(color = base)) { append(s) }
        }
        while (i < t.length) {
            // code placeholder
            if (t[i] == '') {
                val j = t.indexOf('', i + 1)
                if (j > 0) {
                    val idx = t.substring(i + 1, j).toIntOrNull()
                    val code = idx?.let { codes.getOrNull(it) } ?: ""
                    withStyle(
                        SpanStyle(
                            color = accent,
                            fontFamily = FontFamily.Monospace,
                            background = secondary.copy(alpha = 0.15f),
                        ),
                    ) { append("`$code`") }
                    i = j + 1
                    continue
                }
            }
            // image ![alt](src)
            val img = Regex("""^!\[([^\]]*?)\]\(([^)]+?)\)""").find(t.substring(i))
            if (img != null && img.range.first == 0) {
                val alt = img.groupValues[1].ifEmpty { "image" }
                withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) {
                    append("🖼 $alt")
                }
                withStyle(SpanStyle(color = secondary, fontSize = 11.sp)) {
                    append(" (${img.groupValues[2]})")
                }
                i += img.value.length
                continue
            }
            // link [label](url)
            val link = Regex("""^\[([^\]]+?)\]\(([^)]+?)\)""").find(t.substring(i))
            if (link != null && link.range.first == 0) {
                withStyle(
                    SpanStyle(
                        color = accent,
                        fontWeight = FontWeight.Bold,
                        textDecoration = TextDecoration.Underline,
                    ),
                ) { append(link.groupValues[1]) }
                withStyle(SpanStyle(color = secondary, fontSize = 11.sp)) {
                    append(" (${link.groupValues[2]})")
                }
                i += link.value.length
                continue
            }
            // bold **a** / __a__
            val bold = Regex("""^(\*\*([^*]+?)\*\*|__([^_]+?)__)""").find(t.substring(i))
            if (bold != null && bold.range.first == 0) {
                val inner = bold.groupValues[2].ifEmpty { bold.groupValues[3] }
                withStyle(SpanStyle(color = base, fontWeight = FontWeight.Bold)) {
                    append(inner)
                }
                i += bold.value.length
                continue
            }
            // italic *a* / _a_
            val ital = Regex("""^(\*([^*\n]+?)\*|_([^_\n]+?)_)""").find(t.substring(i))
            if (ital != null && ital.range.first == 0) {
                val inner = ital.groupValues[2].ifEmpty { ital.groupValues[3] }
                withStyle(
                    SpanStyle(
                        color = secondary,
                        fontStyle = FontStyle.Italic,
                    ),
                ) { append(inner) }
                i += ital.value.length
                continue
            }
            pushPlain(t[i].toString())
            i++
        }
    }
}

/** Rich markdown body — headings, code, quotes, lists, tables, hr. */
@Composable
fun MarkdownRichBody(
    lines: List<String>,
    modifier: Modifier = Modifier,
    maxLines: Int = 200,
) {
    val blocks = remember(lines) { parseBlocks(lines.take(maxLines)) }
    SelectionContainer {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Heading -> {
                        val size = when (b.level) {
                            1 -> 18.sp
                            2 -> 16.sp
                            else -> 14.sp
                        }
                        Text(
                            text = inlineRich(b.text, SunshineTokens.textPrimary),
                            fontSize = size,
                            fontWeight = FontWeight.Bold,
                            color = SunshineTokens.textPrimary,
                        )
                        if (b.level <= 2) HorizontalDivider(color = SunshineTokens.strokeBorderLight)
                    }
                    is MdBlock.Paragraph -> {
                        Text(
                            text = inlineRich(b.text, SunshineTokens.textPrimary),
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = SunshineTokens.textPrimary,
                        )
                    }
                    is MdBlock.CodeFence -> {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            color = SunshineTokens.surfaceVariant,
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                if (b.lang.isNotEmpty()) {
                                    Text(
                                        text = b.lang,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SunshineTokens.primaryAccent,
                                    )
                                }
                                b.lines.forEachIndexed { idx, cl ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(
                                            text = String.format("%3d", idx + 1),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            color = SunshineTokens.textSecondary,
                                        )
                                        Text(
                                            text = cl,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            lineHeight = 16.sp,
                                            color = SunshineTokens.textPrimary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                    is MdBlock.Quote -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        SunshineTokens.strokeBorderLight,
                                        androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
                                    )
                                    .padding(horizontal = 2.dp),
                            ) { Text(" ", fontSize = 12.sp) }
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                b.lines.forEach { q ->
                                    Text(
                                        text = inlineRich(
                                            q.ifEmpty { " " },
                                            SunshineTokens.textSecondary,
                                        ),
                                        fontSize = 13.sp,
                                        fontStyle = FontStyle.Italic,
                                        color = SunshineTokens.textSecondary,
                                    )
                                }
                            }
                        }
                    }
                    is MdBlock.ListBlock -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            b.items.forEach { item ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(start = (item.indent * 12).dp),
                                ) {
                                    val marker = when {
                                        item.taskDone == true -> "[✓]"
                                        item.taskDone == false -> "[ ]"
                                        item.ordered -> "${item.index}."
                                        else -> "•"
                                    }
                                    Text(
                                        text = marker,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = SunshineTokens.primaryAccent,
                                    )
                                    Text(
                                        text = inlineRich(item.text, SunshineTokens.textPrimary),
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        color = SunshineTokens.textPrimary,
                                    )
                                }
                            }
                        }
                    }
                    is MdBlock.Table -> {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                b.header.forEach { h ->
                                    Text(
                                        text = inlineRich(h, SunshineTokens.textPrimary),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = SunshineTokens.primaryAccent,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            HorizontalDivider(color = SunshineTokens.strokeBorderLight)
                            b.rows.forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { c ->
                                        Text(
                                            text = inlineRich(c, SunshineTokens.textPrimary),
                                            fontSize = 12.sp,
                                            color = SunshineTokens.textPrimary,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    MdBlock.Hr -> HorizontalDivider(color = SunshineTokens.strokeBorderLight)
                }
            }
        }
    }
}

/** File preview entry: rich markdown for .md, mono block otherwise. */
@Composable
fun FilePreviewRich(
    file: FileContent,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 200,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "👁 ${file.path}" + if (file.isMarkdown) " — rendered" else "",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = SunshineTokens.textSecondary,
        )
        if (file.isMarkdown) {
            MarkdownRichBody(lines = file.lines, maxLines = collapsedLines)
        } else {
            SelectionContainer {
                Text(
                    text = file.lines.take(collapsedLines).joinToString("\n").ifEmpty { "(empty file)" },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = SunshineTokens.textPrimary,
                )
            }
        }
        if (file.lines.size > collapsedLines) {
            Text(
                text = "… ${file.lines.size - collapsedLines} more lines",
                fontSize = 12.sp,
                color = SunshineTokens.textSecondary,
            )
        }
    }
}

@Preview
@Composable
private fun MarkdownRichPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilePreviewRich(
                file = FileContent(
                    path = "README.md",
                    isMarkdown = true,
                    lines = listOf(
                        "# Hello",
                        "",
                        "Bold **strong** italic *soft* `code` [link](https://x) ![pic](p.png)",
                        "",
                        "> quoted line",
                        "",
                        "- [x] done task",
                        "- [ ] todo",
                        "- bullet",
                        "1. first",
                        "",
                        "| a | b |",
                        "|---|---|",
                        "| 1 | 2 |",
                        "",
                        "```kotlin",
                        "val x = 1 // comment",
                        "```",
                    ),
                ),
            )
        }
    }
}
