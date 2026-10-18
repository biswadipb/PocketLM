package com.pocketlm.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A small Markdown subset: headings, lists, fenced code, **bold**, *italic* and `code`. */
private sealed interface Block {
    data class Paragraph(val text: String) : Block
    data class Heading(val level: Int, val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Numbered(val number: String, val text: String) : Block
    data class Code(val language: String, val code: String) : Block
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*+]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d+)[.)]\s+(.*)$""")

private fun isBlockStart(line: String) =
    line.trimStart().startsWith("```") || HEADING.matches(line) || BULLET.matches(line) || NUMBERED.matches(line)

private fun parseBlocks(text: String): List<Block> {
    val lines = text.split('\n')
    val blocks = ArrayList<Block>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                // While streaming the closing fence may not have arrived yet; the rest is code.
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(lines[i])
                    i++
                }
                blocks += Block.Code(language, code.toString())
                i++
            }
            line.isBlank() -> i++
            HEADING.matches(line) -> {
                val m = HEADING.find(line)!!
                blocks += Block.Heading(m.groupValues[1].length, m.groupValues[2])
                i++
            }
            BULLET.matches(line) -> {
                blocks += Block.Bullet(BULLET.find(line)!!.groupValues[1])
                i++
            }
            NUMBERED.matches(line) -> {
                val m = NUMBERED.find(line)!!
                blocks += Block.Numbered(m.groupValues[1], m.groupValues[2])
                i++
            }
            else -> {
                val para = StringBuilder(line)
                i++
                while (i < lines.size && lines[i].isNotBlank() && !isBlockStart(lines[i])) {
                    para.append('\n').append(lines[i])
                    i++
                }
                blocks += Block.Paragraph(para.toString())
            }
        }
    }
    return blocks
}

private fun inline(text: String, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > i + 2 -> {
                    val end = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(inline(text.substring(i + 2, end), codeBackground))
                    }
                    i = end + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) > i + 1 -> {
                    val end = text.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                }
                text[i] == '*' && i + 1 < text.length && text[i + 1] != ' ' &&
                    text.indexOf('*', i + 1) > i + 1 -> {
                    val end = text.indexOf('*', i + 1)
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(inline(text.substring(i + 1, end), codeBackground))
                    }
                    i = end + 1
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = MaterialTheme.colorScheme.background
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is Block.Paragraph -> Text(inline(block.text, codeBg))
                is Block.Heading -> Text(
                    inline(block.text, codeBg),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                )
                is Block.Bullet -> Row {
                    Text("•  ")
                    Text(inline(block.text, codeBg))
                }
                is Block.Numbered -> Row {
                    Text("${block.number}.  ")
                    Text(inline(block.text, codeBg))
                }
                is Block.Code -> CodeBlock(block.language, block.code)
            }
        }
    }
}

@Composable
private fun CodeBlock(language: String, code: String) {
    val clipboard = LocalClipboardManager.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    language.ifEmpty { "code" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = { clipboard.setText(AnnotatedString(code)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy code", modifier = Modifier.padding(2.dp))
                }
            }
            Text(
                code,
                Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 17.sp,
            )
        }
    }
}
