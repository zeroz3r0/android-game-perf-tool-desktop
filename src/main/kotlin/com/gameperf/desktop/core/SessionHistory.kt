package com.gameperf.desktop.core

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Persists the last N test sessions to a JSON file in ~/GamePerf Reports/history.json.
 * Simple manual JSON serialization (no external dependencies).
 */
object SessionHistory {

    private const val MAX_ENTRIES = 5
    private val historyFile = File(System.getProperty("user.home"), "GamePerf Reports/history.json")

    data class HistoryEntry(
        val id: String,
        val name: String,
        val gamePackage: String,
        val deviceModel: String,
        val grade: Char,
        val deviceGrade: Char,
        val avgFps: Int,
        val duration: Int,
        val date: String,
        val reportPath: String,
        val videoPath: String
    )

    fun load(): List<HistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val text = historyFile.readText()
            parseEntries(text)
        } catch (_: Exception) { emptyList() }
    }

    fun save(entries: List<HistoryEntry>) {
        try {
            historyFile.parentFile?.mkdirs()
            historyFile.writeText(toJson(entries.take(MAX_ENTRIES)))
        } catch (_: Exception) {}
    }

    fun addEntry(
        gamePackage: String, deviceModel: String, grade: Char, deviceGrade: Char,
        avgFps: Int, duration: Int, reportPath: String, videoPath: String
    ) {
        val entries = load().toMutableList()
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())
        val id = System.currentTimeMillis().toString()
        val name = "$gamePackage - $deviceModel"
        entries.add(0, HistoryEntry(id, name, gamePackage, deviceModel, grade, deviceGrade, avgFps, duration, date, reportPath, videoPath))
        save(entries.take(MAX_ENTRIES))
    }

    fun updateName(id: String, newName: String) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(name = newName)
            save(entries)
        }
    }

    // ===== Simple JSON serialization (no dependencies) =====

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun toJson(entries: List<HistoryEntry>): String {
        val sb = StringBuilder("[\n")
        entries.forEachIndexed { i, e ->
            sb.append("""  {
    "id": "${esc(e.id)}",
    "name": "${esc(e.name)}",
    "gamePackage": "${esc(e.gamePackage)}",
    "deviceModel": "${esc(e.deviceModel)}",
    "grade": "${e.grade}",
    "deviceGrade": "${e.deviceGrade}",
    "avgFps": ${e.avgFps},
    "duration": ${e.duration},
    "date": "${esc(e.date)}",
    "reportPath": "${esc(e.reportPath)}",
    "videoPath": "${esc(e.videoPath)}"
  }""")
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseEntries(json: String): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        // Simple regex-based parsing for our known format
        val objectPattern = Regex("\\{[^}]+\\}", RegexOption.DOT_MATCHES_ALL)
        for (match in objectPattern.findAll(json)) {
            try {
                val obj = match.value
                fun field(name: String): String {
                    val r = Regex("\"$name\"\\s*:\\s*\"([^\"]*?)\"")
                    return r.find(obj)?.groupValues?.get(1)?.replace("\\\\", "\\")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                }
                fun intField(name: String): Int {
                    val r = Regex("\"$name\"\\s*:\\s*(\\d+)")
                    return r.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                entries.add(HistoryEntry(
                    id = field("id"),
                    name = field("name"),
                    gamePackage = field("gamePackage"),
                    deviceModel = field("deviceModel"),
                    grade = field("grade").firstOrNull() ?: 'F',
                    deviceGrade = field("deviceGrade").firstOrNull() ?: ' ',
                    avgFps = intField("avgFps"),
                    duration = intField("duration"),
                    date = field("date"),
                    reportPath = field("reportPath"),
                    videoPath = field("videoPath")
                ))
            } catch (_: Exception) {}
        }
        return entries
    }
}
