package com.gameperf.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.gameperf.desktop.ui.theme.*

/**
 * In-app viewer for the performance testing documentation.
 * Reads markdown files from resources/docs/ and renders them with basic styling.
 * Keeps the methodology reference one click away while capturing sessions.
 */
@Composable
fun GuideDialog(onDismiss: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }

    val methodology = remember { readResource("docs/PERFORMANCE_TESTING.md") }
    val template = remember { readResource("docs/BENCHMARK_TEMPLATE.md") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            color = DarkCard,
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = Cyan, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Guía de Performance Testing", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Cerrar", tint = TextDim)
                    }
                }

                // Tabs
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = DarkSurface,
                    contentColor = Cyan
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Metodología", fontSize = 13.sp) },
                        icon = { Icon(Icons.Default.Science, null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Plantilla", fontSize = 13.sp) },
                        icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(16.dp)) }
                    )
                }

                // Content
                Box(
                    Modifier.fillMaxSize()
                        .background(DarkSurface)
                        .padding(20.dp)
                ) {
                    val content = if (selectedTab == 0) methodology else template
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        MarkdownRenderer(content)
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * Minimal markdown renderer — handles headings, bold, code, lists, tables, and hrs.
 * Not a full parser, but enough for our methodology/template docs.
 */
@Composable
private fun MarkdownRenderer(md: String) {
    val lines = md.lines()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            // H1
            line.startsWith("# ") -> {
                Text(
                    line.removePrefix("# ").trim(),
                    color = Cyan,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                )
            }
            // H2
            line.startsWith("## ") -> {
                Text(
                    line.removePrefix("## ").trim(),
                    color = Purple,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
            }
            // H3
            line.startsWith("### ") -> {
                Text(
                    line.removePrefix("### ").trim(),
                    color = Green,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                )
            }
            // Horizontal rule
            line.trim() == "---" -> {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp),
                    color = TextDim.copy(alpha = 0.3f)
                )
            }
            // Table — detect by pipe chars in current + next line starting with |---
            line.trim().startsWith("|") && i + 1 < lines.size
                && lines[i + 1].trim().startsWith("|") && lines[i + 1].contains("---") -> {
                val tableStart = i
                while (i < lines.size && lines[i].trim().startsWith("|")) i++
                RenderTable(lines.subList(tableStart, i))
                continue
            }
            // Code block
            line.startsWith("```") -> {
                val codeStart = i + 1
                var codeEnd = codeStart
                while (codeEnd < lines.size && !lines[codeEnd].startsWith("```")) codeEnd++
                val code = lines.subList(codeStart, codeEnd).joinToString("\n")
                Surface(
                    color = Color(0xFF0D1117),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        code,
                        color = Color(0xFFE6EDF3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(10.dp)
                    )
                }
                i = codeEnd
            }
            // Bullet list
            line.trim().startsWith("- ") || line.trim().startsWith("* ") -> {
                Row(Modifier.padding(start = 8.dp, top = 2.dp)) {
                    Text("•", color = Cyan, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    InlineText(line.trim().removePrefix("- ").removePrefix("* "))
                }
            }
            // Checkbox list
            line.trim().startsWith("- [ ]") -> {
                Row(Modifier.padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.Top) {
                    Text("☐", color = TextDim, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    InlineText(line.trim().removePrefix("- [ ]").trim())
                }
            }
            line.trim().startsWith("- [x]") -> {
                Row(Modifier.padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.Top) {
                    Text("☑", color = Green, fontSize = 13.sp)
                    Spacer(Modifier.width(6.dp))
                    InlineText(line.trim().removePrefix("- [x]").trim())
                }
            }
            // Numbered list
            Regex("^\\d+\\.\\s").containsMatchIn(line.trim()) -> {
                Row(Modifier.padding(start = 8.dp, top = 2.dp)) {
                    val num = Regex("^(\\d+)\\.").find(line.trim())?.groupValues?.get(1) ?: ""
                    Text("$num.", color = Cyan, fontSize = 13.sp, modifier = Modifier.widthIn(min = 24.dp))
                    InlineText(line.trim().replaceFirst(Regex("^\\d+\\.\\s"), ""))
                }
            }
            // Empty line
            line.isBlank() -> Spacer(Modifier.height(6.dp))
            // Paragraph
            else -> InlineText(line)
        }
        i++
    }
}

/** Render text with **bold** and `code` inline formatting. */
@Composable
private fun InlineText(text: String) {
    val parts = mutableListOf<Triple<String, Boolean, Boolean>>() // text, bold, code
    var remaining = text
    while (remaining.isNotEmpty()) {
        val boldStart = remaining.indexOf("**")
        val codeStart = remaining.indexOf("`")
        val firstStart = when {
            boldStart < 0 && codeStart < 0 -> -1
            boldStart < 0 -> codeStart
            codeStart < 0 -> boldStart
            else -> minOf(boldStart, codeStart)
        }
        if (firstStart < 0) {
            parts.add(Triple(remaining, false, false))
            break
        }
        if (firstStart > 0) parts.add(Triple(remaining.substring(0, firstStart), false, false))
        val isBold = remaining.substring(firstStart).startsWith("**")
        val delim = if (isBold) "**" else "`"
        val endIdx = remaining.indexOf(delim, firstStart + delim.length)
        if (endIdx < 0) {
            parts.add(Triple(remaining.substring(firstStart), false, false))
            break
        }
        parts.add(Triple(
            remaining.substring(firstStart + delim.length, endIdx),
            isBold,
            !isBold
        ))
        remaining = remaining.substring(endIdx + delim.length)
    }
    Row {
        parts.forEach { (t, bold, code) ->
            Text(
                t,
                color = if (code) Yellow else TextPrimary,
                fontSize = 13.sp,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontFamily = if (code) FontFamily.Monospace else FontFamily.Default
            )
        }
    }
}

@Composable
private fun RenderTable(rows: List<String>) {
    if (rows.size < 2) return
    val headers = rows[0].trim().trim('|').split("|").map { it.trim() }
    val dataRows = rows.drop(2).map { row ->
        row.trim().trim('|').split("|").map { it.trim() }
    }
    Surface(
        color = Color(0xFF0D1117),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(8.dp)) {
            // Header
            Row(Modifier.fillMaxWidth()) {
                headers.forEach { h ->
                    Text(
                        h,
                        color = Cyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f).padding(4.dp)
                    )
                }
            }
            HorizontalDivider(color = TextDim.copy(alpha = 0.3f))
            // Rows
            dataRows.forEach { cells ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    cells.forEach { c ->
                        Text(
                            c.replace("**", ""),
                            color = TextPrimary,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f).padding(4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun readResource(path: String): String {
    return try {
        object {}.javaClass.classLoader.getResourceAsStream(path)
            ?.bufferedReader()?.use { it.readText() }
            ?: "Documento no encontrado: $path"
    } catch (e: Exception) {
        "Error al cargar $path: ${e.message}"
    }
}
