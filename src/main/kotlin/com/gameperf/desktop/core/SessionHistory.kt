package com.gameperf.desktop.core

import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Persists the last N test sessions to a JSON file in ~/GamePerf Reports/history.json.
 *
 * v4.1.0: Migrated from hand-rolled regex-based JSON parser to kotlinx.serialization.
 * The on-disk format is identical (same field names, same types), so existing history.json
 * files are read seamlessly. The `ignoreUnknownKeys = true` setting ensures forward
 * compatibility if future versions add fields.
 *
 * Retention policy: hard cap of [MAX_ENTRIES] sessions.
 * Thread-safety: all write operations are `@Synchronized` on the SessionHistory singleton.
 */
object SessionHistory {

    const val MAX_ENTRIES = 5

    /** Test-only override. When non-null, overrides the history file location. */
    internal var historyFileOverride: File? = null

    private val historyFile: File
        get() = historyFileOverride ?: File(System.getProperty("user.home"), "GamePerf Reports/history.json")

    /** Lenient JSON config: ignores unknown keys for forward compat, pretty prints for readability. */
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        isLenient = true
    }

    /** Tag to classify sessions as our game or a competitor's game. */
    enum class SessionTag { OUR_GAME, COMPETITION }

    // ===== Custom serializers for non-@Serializable types =====

    /** Serialize [MarkerType] as its enum name string. */
    object MarkerTypeSerializer : KSerializer<MarkerType> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MarkerType", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: MarkerType) = encoder.encodeString(value.name)
        override fun deserialize(decoder: Decoder): MarkerType {
            val name = decoder.decodeString()
            return try { MarkerType.valueOf(name) } catch (_: Exception) { MarkerType.CUSTOM }
        }
    }

    @Serializable
    data class SerializableMarker(
        val id: String = "",
        val tsMs: Long = 0,
        val ts: Int = 0,
        @Serializable(with = MarkerTypeSerializer::class)
        val type: MarkerType = MarkerType.CUSTOM,
        val title: String = "",
        val note: String = "",
        val color: String = "#FF0000",
    ) {
        fun toSessionMarker(): SessionMarker = SessionMarker(
            id = id.ifEmpty { java.util.UUID.randomUUID().toString() },
            timestampMs = if (tsMs > 0) tsMs else ts.toLong() * 1000,
            type = type,
            title = title.ifEmpty { type.label },
            note = note,
            colorHex = color,
        )

        companion object {
            fun from(m: SessionMarker) = SerializableMarker(
                id = m.id,
                tsMs = m.timestampMs,
                ts = m.timestampSeconds,
                type = m.type,
                title = m.title,
                note = m.note,
                color = m.colorHex,
            )
        }
    }

    @Serializable
    data class SerializableEntry(
        val id: String = "",
        val name: String = "",
        val gamePackage: String = "",
        val deviceModel: String = "",
        val grade: String = "F",
        val deviceGrade: String = " ",
        val avgFps: Int = 0,
        val duration: Int = 0,
        val date: String = "",
        val reportPath: String = "",
        val videoPath: String = "",
        val tag: String = "OUR_GAME",
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
        val markers: List<SerializableMarker> = emptyList(),
        // v4.2: favoritos — default false preserva compatibilidad con history.json existente
        val isFavorite: Boolean = false,
    )

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
        val markers: List<SessionMarker> = emptyList(),
        /** Favoritos nunca se auto-evictan. Solo se borran manualmente. */
        val isFavorite: Boolean = false,
    )

    // ===== Conversion =====

    private fun HistoryEntry.toSerializable() = SerializableEntry(
        id = id, name = name, gamePackage = gamePackage, deviceModel = deviceModel,
        grade = grade.toString(), deviceGrade = deviceGrade.toString(), avgFps = avgFps, duration = duration,
        date = date, reportPath = reportPath, videoPath = videoPath,
        tag = tag.name, competitorName = competitorName,
        p1Fps = p1Fps, p5Fps = p5Fps, avgFrameTime = avgFrameTime,
        p95FrameTime = p95FrameTime, p99FrameTime = p99FrameTime,
        peakMemMb = peakMemMb, avgCpu = avgCpu, maxTemp = maxTemp, score = score,
        markers = markers.map { SerializableMarker.from(it) },
        isFavorite = isFavorite,
    )

    private fun SerializableEntry.toHistoryEntry() = HistoryEntry(
        id = id, name = name, gamePackage = gamePackage, deviceModel = deviceModel,
        grade = grade.firstOrNull() ?: 'F', deviceGrade = deviceGrade.firstOrNull() ?: ' ',
        avgFps = avgFps, duration = duration,
        date = date, reportPath = reportPath, videoPath = videoPath,
        tag = try { SessionTag.valueOf(tag) } catch (_: Exception) { SessionTag.OUR_GAME },
        competitorName = competitorName,
        p1Fps = p1Fps, p5Fps = p5Fps, avgFrameTime = avgFrameTime,
        p95FrameTime = p95FrameTime, p99FrameTime = p99FrameTime,
        peakMemMb = peakMemMb, avgCpu = avgCpu, maxTemp = maxTemp, score = score,
        markers = markers.map { it.toSessionMarker() },
        isFavorite = isFavorite,
    )

    // ===== Public API (unchanged contract) =====

    fun load(): List<HistoryEntry> {
        if (!historyFile.exists()) return emptyList()
        return try {
            val text = historyFile.readText()
            val entries = json.decodeFromString<List<SerializableEntry>>(text)
            entries.map { it.toHistoryEntry() }
        } catch (e: Exception) {
            System.err.println("[GamePerf] Failed to load session history: ${e.message}")
            emptyList()
        }
    }

    @Synchronized
    fun save(entries: List<HistoryEntry>) {
        try {
            historyFile.parentFile?.mkdirs()
            // Favorites are always persisted. Recents are capped at MAX_ENTRIES.
            val favorites = entries.filter { it.isFavorite }
            val recents = entries.filter { !it.isFavorite }.take(MAX_ENTRIES)
            val serializable = (favorites + recents).map { it.toSerializable() }
            val text = json.encodeToString(ListSerializer(SerializableEntry.serializer()), serializable)
            // Atomic write: write to .tmp first, then move.
            // File.renameTo() silently fails on Windows when the destination already exists.
            // Files.move with REPLACE_EXISTING is atomic on most OS and works cross-platform.
            val tmpFile = File(historyFile.parentFile, "${historyFile.name}.tmp")
            tmpFile.writeText(text)
            Files.move(tmpFile.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            System.err.println("[GamePerf] Failed to save session history: ${e.message}")
        }
    }

    @Synchronized
    fun addEntry(entry: HistoryEntry): List<HistoryEntry> {
        val all = load().toMutableList()
        all.add(0, entry)
        // Favorites are never evicted — only recents respect MAX_ENTRIES.
        val favorites = all.filter { it.isFavorite }
        val recents = all.filter { !it.isFavorite }
        val topRecents = recents.take(MAX_ENTRIES)
        val evicted = if (recents.size > MAX_ENTRIES) recents.drop(MAX_ENTRIES) else emptyList()
        save(favorites + topRecents)
        return evicted
    }

    /** Toggle the favorite flag for a session. Favorited sessions are never auto-evicted. */
    @Synchronized
    fun toggleFavorite(id: String) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(isFavorite = !entries[idx].isFavorite)
            save(entries)
        }
    }

    fun addEntry(
        gamePackage: String, deviceModel: String, grade: Char, deviceGrade: Char,
        avgFps: Int, duration: Int, reportPath: String, videoPath: String,
        tag: SessionTag = SessionTag.OUR_GAME, competitorName: String = "",
        p1Fps: Int = 0, p5Fps: Int = 0, avgFrameTime: Double = 0.0,
        p95FrameTime: Double = 0.0, p99FrameTime: Double = 0.0,
        peakMemMb: Long = 0, avgCpu: Int = 0, maxTemp: Double = 0.0, score: Int = 0,
        markers: List<SessionMarker> = emptyList()
    ): List<HistoryEntry> {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm").format(Date())
        val id = System.currentTimeMillis().toString()
        val displayName = if (tag == SessionTag.COMPETITION && competitorName.isNotEmpty())
            "$competitorName - $deviceModel"
        else "$gamePackage - $deviceModel"
        val entry = HistoryEntry(
            id, displayName, gamePackage, deviceModel, grade, deviceGrade, avgFps, duration, date,
            reportPath, videoPath, tag, competitorName,
            p1Fps, p5Fps, avgFrameTime, p95FrameTime, p99FrameTime, peakMemMb, avgCpu, maxTemp, score,
            markers
        )
        return addEntry(entry)
    }

    @Synchronized
    fun updateTag(id: String, tag: SessionTag, competitorName: String = "") {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(tag = tag, competitorName = competitorName)
            save(entries)
        }
    }

    @Synchronized
    fun updateName(id: String, newName: String) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == id }
        if (idx >= 0) {
            entries[idx] = entries[idx].copy(name = newName)
            save(entries)
        }
    }

    @Synchronized
    fun updateEntry(entry: HistoryEntry) {
        val entries = load().toMutableList()
        val idx = entries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            entries[idx] = entry
            save(entries)
        }
    }

    @Synchronized
    fun deleteEntry(id: String): HistoryEntry? {
        val entries = load().toMutableList()
        val removed = entries.firstOrNull { it.id == id }
        if (removed != null) {
            entries.removeAll { it.id == id }
            save(entries)
        }
        return removed
    }
}
