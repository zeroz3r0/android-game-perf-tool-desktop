package com.gameperf.desktop.core

import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Persists the last N test sessions to a JSON file in ~/GamePerf Reports/history.json.
 * Simple manual JSON serialization (no external dependencies).
 */
object SessionHistory {

    private const val MAX_ENTRIES = 20
    private val historyFile = File(System.getProperty("user.home"), "GamePerf Reports/history.json")

    /** Tag to classify sessions as our game or a competitor's game. */
    enum class SessionTag { OUR_GAME, COMPETITION }

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
        val videoPath: String,
        val tag: SessionTag = SessionTag.OUR_GAME,
        val competitorName: String = "",
        val p1Fps: Int = 0,
        val p5Fps: Int = 0,
        val avgFrameTime: Double = 0.0,
        val p95FrameTime: Double = 0.0,
        val p99FrameTime: Double = 0.0,
        val peakMemMb: Long = 0,
        val avgCpu: Int = 0,
        val maxTemp: Double = 0.0,
        val score: Int = 0,
        val markers: List<SessionMarker> = emptyList()
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
        avgFps: Int, duration: Int, reportPath: String, videoPath: String,
        tag: SessionTag = SessionTag.OUR_GAME, competitorName: String = "",
        p1Fps: Int = 0, p5Fps: Int = 0, avgFrameTime: Double = 0.0,
        p95FrameTime: Double = 0.0, p99FrameTime: Double = 0.0,
        peakMemMb: Long = 0, avgCpu: Int = 0, maxTemp: Double = 0.0, score: Int = 0,
        markers: List<SessionMarker> = emptyList()
    ) {
        val entries = load().toMutableList()
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())
        val id = System.currentTimeMillis().toString()
        val displayName = if (tag == SessionTag.COMPETITION && competitorName.isNotEmpty())
            "$competitorName - $deviceModel"
        else "$gamePackage - $deviceModel"
        entries.add(0, HistoryEntry(
            id, displayName, gamePackage, deviceModel, grade, deviceGrade, avgFps, duration, date,
            reportPath, videoPath, tag, competitorName,
            p1Fps, p5Fps, avgFrameTime, p95FrameTime, p99FrameTime, peakMemMb, avgCpu, maxTemp, score,
            markers
        ))
        save(entries.take(MAX_ENTRIES))
    }

    fun updateTag(id: String, tag: SessionTag, competitorName: String = "") {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(tag = tag, competitorName = competitorName)
            save(entries)
        }
    }

    fun updateName(id: String, newName: String) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(name = newName)
            save(entries)
        }
    }

    fun deleteEntry(id: String) {
        val entries = load().toMutableList()
        entries.removeAll { it.id == id }
        save(entries)
    }

    // ===== Simple JSON serialization (no dependencies) =====

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun markersToJson(markers: List<SessionMarker>): String {
        if (markers.isEmpty()) return "[]"
        return markers.joinToString(",", "[", "]") { m ->
            """{"id":"${esc(m.id)}","tsMs":${m.timestampMs},"ts":${m.timestampSeconds},"type":"${m.type.name}","title":"${esc(m.title)}","note":"${esc(m.note)}","color":"${esc(m.colorHex)}"}"""
        }
    }

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
    "videoPath": "${esc(e.videoPath)}",
    "tag": "${e.tag.name}",
    "competitorName": "${esc(e.competitorName)}",
    "p1Fps": ${e.p1Fps},
    "p5Fps": ${e.p5Fps},
    "avgFrameTime": ${e.avgFrameTime},
    "p95FrameTime": ${e.p95FrameTime},
    "p99FrameTime": ${e.p99FrameTime},
    "peakMemMb": ${e.peakMemMb},
    "avgCpu": ${e.avgCpu},
    "maxTemp": ${e.maxTemp},
    "score": ${e.score},
    "markers": ${markersToJson(e.markers)}
  }""")
            if (i < entries.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }

    private fun parseMarkers(obj: String): List<SessionMarker> {
        val markersMatch = Regex("\"markers\"\\s*:\\s*\\[([^\\]]*)\\]").find(obj) ?: return emptyList()
        val inner = markersMatch.groupValues[1].trim()
        if (inner.isEmpty()) return emptyList()
        val result = mutableListOf<SessionMarker>()
        for (m in Regex("\\{[^}]*\\}").findAll(inner)) {
            try {
                val mObj = m.value
                // Read tsMs first (new format), fallback to ts * 1000 (legacy)
                val tsMs = Regex("\"tsMs\"\\s*:\\s*(\\d+)").find(mObj)?.groupValues?.get(1)?.toLongOrNull()
                val tsLegacy = Regex("\"ts\"\\s*:\\s*(\\d+)").find(mObj)?.groupValues?.get(1)?.toIntOrNull()
                val timestampMs = tsMs ?: ((tsLegacy ?: continue).toLong() * 1000)
                val typeName = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(mObj)?.groupValues?.get(1) ?: continue
                val type = try { MarkerType.valueOf(typeName) } catch (_: Exception) { MarkerType.CUSTOM }
                val id = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(mObj)?.groupValues?.get(1)
                    ?: java.util.UUID.randomUUID().toString()
                val title = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(mObj)?.groupValues?.get(1)
                    ?.replace("\\\\", "\\")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: type.label
                val note = Regex("\"note\"\\s*:\\s*\"([^\"]*)\"").find(mObj)?.groupValues?.get(1)
                    ?.replace("\\\\", "\\")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                val colorHex = Regex("\"color\"\\s*:\\s*\"([^\"]+)\"").find(mObj)?.groupValues?.get(1) ?: type.colorHex
                result.add(SessionMarker(
                    id = id,
                    timestampMs = timestampMs,
                    type = type,
                    title = title,
                    note = note,
                    colorHex = colorHex
                ))
            } catch (_: Exception) {}
        }
        return result
    }

    private fun parseEntries(json: String): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        // Parse top-level objects — handles nested markers array by matching balanced braces
        val topObjects = mutableListOf<String>()
        var depth = 0; var start = -1
        for (i in json.indices) {
            when (json[i]) {
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { topObjects.add(json.substring(start, i + 1)); start = -1 } }
            }
        }
        for (obj in topObjects) {
            try {
                fun field(name: String): String {
                    val r = Regex("\"$name\"\\s*:\\s*\"([^\"]*?)\"")
                    return r.find(obj)?.groupValues?.get(1)?.replace("\\\\", "\\")?.replace("\\n", "\n")?.replace("\\\"", "\"") ?: ""
                }
                fun intField(name: String): Int {
                    val r = Regex("\"$name\"\\s*:\\s*(\\d+)")
                    return r.find(obj)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                fun longField(name: String): Long {
                    val r = Regex("\"$name\"\\s*:\\s*(\\d+)")
                    return r.find(obj)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                }
                fun doubleField(name: String): Double {
                    val r = Regex("\"$name\"\\s*:\\s*([\\d.]+)")
                    return r.find(obj)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                }
                val tagStr = field("tag")
                val tag = try { SessionTag.valueOf(tagStr) } catch (_: Exception) { SessionTag.OUR_GAME }
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
                    videoPath = field("videoPath"),
                    tag = tag,
                    competitorName = field("competitorName"),
                    p1Fps = intField("p1Fps"),
                    p5Fps = intField("p5Fps"),
                    avgFrameTime = doubleField("avgFrameTime"),
                    p95FrameTime = doubleField("p95FrameTime"),
                    p99FrameTime = doubleField("p99FrameTime"),
                    peakMemMb = longField("peakMemMb"),
                    avgCpu = intField("avgCpu"),
                    maxTemp = doubleField("maxTemp"),
                    score = intField("score"),
                    markers = parseMarkers(obj)
                ))
            } catch (_: Exception) {}
        }
        return entries
    }
}
