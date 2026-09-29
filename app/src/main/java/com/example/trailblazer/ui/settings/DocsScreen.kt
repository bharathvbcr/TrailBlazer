package com.example.trailblazer.ui.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.example.trailblazer.ui.components.GlassCard
import com.example.trailblazer.ui.components.ScreenScaffold
import com.example.trailblazer.ui.nav.DocsRoute
import com.example.trailblazer.ui.nav.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Markdown blocks the bundled docs use: headings, paragraphs, bullets, tables (shown as text) and code. */
sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val text: String, val depth: Int) : MdBlock
    data class Code(val text: String) : MdBlock
}

object Markdown {
    fun parse(src: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.setLength(0)
        }
        val lines = src.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val t = line.trimStart()
            when {
                t.startsWith("```") -> {
                    flush()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { code.appendLine(lines[i]); i++ }
                    out += MdBlock.Code(code.toString().trimEnd())
                }
                t.startsWith("#") -> {
                    flush()
                    val level = t.takeWhile { it == '#' }.length
                    out += MdBlock.Heading(level, t.drop(level).trim())
                }
                t.startsWith("- ") || t.startsWith("* ") || Regex("^\\d+\\. ").containsMatchIn(t) -> {
                    flush()
                    out += MdBlock.Bullet(t.replaceFirst(Regex("^([-*]|\\d+\\.) "), ""), (line.length - t.length) / 2)
                }
                t.startsWith("|") -> {
                    flush()
                    if (!t.replace("|", "").trim().all { it == '-' || it == ':' || it == ' ' }) {
                        out += MdBlock.Bullet(t.trim('|').split('|').joinToString(" · ") { it.trim() }, 0)
                    }
                }
                t.isBlank() -> flush()
                else -> para.append(t).append(' ')
            }
            i++
        }
        flush()
        return out
    }

    /** Inline `code` and **bold**. */
    fun inline(text: String): AnnotatedString = buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) && text.indexOf("**", i + 2) > 0 -> {
                    val e = text.indexOf("**", i + 2)
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, e)) }
                    i = e + 2
                }
                text[i] == '`' && text.indexOf('`', i + 1) > 0 -> {
                    val e = text.indexOf('`', i + 1)
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(text.substring(i + 1, e)) }
                    i = e + 1
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
}

@Composable
fun DocsScreen(nav: Navigator, file: String?) {
    val ctx = LocalContext.current
    if (file == null) {
        val files by produceState(emptyList<String>()) {
            value = withContext(Dispatchers.IO) { ctx.assets.list("docs")?.filter { it.endsWith(".md") }?.sorted().orEmpty() }
        }
        ScreenScaffold(title = "Developer docs", onBack = { nav.back() }) {
            items(files) { f ->
                GlassCard(onClick = { nav.go(DocsRoute(f)) }, onClickLabel = "Open") {
                    Text(f.removeSuffix(".md"), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        return
    }
    val blocks by produceState(emptyList<MdBlock>(), file) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.assets.open("docs/$file").bufferedReader().use { it.readText() } }
                .map { Markdown.parse(it) }
                .getOrElse { listOf(MdBlock.Paragraph("Could not open $file.")) }
        }
    }
    ScreenScaffold(title = file.removeSuffix(".md"), onBack = { nav.back() }) {
        items(blocks) { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    b.text,
                    style = when (b.level) { 1 -> MaterialTheme.typography.headlineSmall; 2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.titleMedium },
                    modifier = Modifier.padding(top = 8.dp),
                )
                is MdBlock.Paragraph -> Text(Markdown.inline(b.text), style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.depth * 16).dp)) {
                    Text("•", Modifier.width(16.dp))
                    Text(Markdown.inline(b.text), style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> GlassCard(Modifier.fillMaxWidth(), corner = 12.dp, padding = 10.dp) {
                    Text(b.text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}
