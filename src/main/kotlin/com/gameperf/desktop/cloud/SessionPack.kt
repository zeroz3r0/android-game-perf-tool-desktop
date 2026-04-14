package com.gameperf.desktop.cloud

import com.gameperf.desktop.core.AppVersion
import com.gameperf.desktop.core.SessionHistory
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Session Pack — the `.gameperf` portable session exchange format.
 *
 * A `.gameperf` file is a ZIP archive containing:
 *   manifest.json  — pack version + session metadata (ALL metrics)
 *   report.html    — full HTML report with charts  (optional)
 *
 * Videos are NOT bundled (too large). The session.json records the original
 * local video path; cloud video upload is a separate opt-in action.
 *
 * Design goals:
 *  - Self-contained: open in any browser without the app
 *  - Forward-compatible: ignoreUnknownKeys = true on import
 *  - Round-trip safe: export → import recreates an equivalent HistoryEntry
 */
object SessionPack {

    const val EXTENSION = ".gameperf"
    const val FORMAT_VERSION = 1

    @Serializable
    data class Manifest(
        val formatVersion: Int = FORMAT_VERSION,
        val appVersion: String = AppVersion.NAME,
        val exportedAt: String = Instant.now().toString(),
        val session: SessionHistory.SerializableEntry,
        val hasReport: Boolean = false,
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Export
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Export a [HistoryEntry] to a `.gameperf` file in [destDir].
     * The file is named `sess_<id>_<sanitizedName>.gameperf`.
     */
    fun export(entry: SessionHistory.HistoryEntry, destDir: File): File {
        destDir.mkdirs()
        val safeName = entry.name
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .take(40)
        val packFile = File(destDir, "sess_${entry.id}_${safeName}$EXTENSION")

        val reportFile = if (entry.reportPath.isNotEmpty()) File(entry.reportPath) else null
        val hasReport = reportFile?.exists() == true

        val manifest = Manifest(
            session = serializableEntryFrom(entry),
            hasReport = hasReport,
        )

        ZipOutputStream(packFile.outputStream().buffered()).use { zip ->
            // manifest.json
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(json.encodeToString(Manifest.serializer(), manifest).toByteArray())
            zip.closeEntry()

            // report.html (optional)
            if (hasReport && reportFile != null) {
                zip.putNextEntry(ZipEntry("report.html"))
                reportFile.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }

        return packFile
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Import
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Import a `.gameperf` file and return the [HistoryEntry] it contains.
     * If the pack contains a `report.html`, it is extracted to [reportsDir].
     * The returned entry's [HistoryEntry.reportPath] is updated to the
     * extracted path (or empty if no report in the pack).
     */
    fun import(packFile: File, reportsDir: File): SessionHistory.HistoryEntry {
        var manifest: Manifest? = null
        var reportBytes: ByteArray? = null

        ZipInputStream(packFile.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "manifest.json" -> {
                        manifest = json.decodeFromString(
                            Manifest.serializer(),
                            zip.readBytes().toString(Charsets.UTF_8)
                        )
                    }
                    "report.html" -> {
                        reportBytes = zip.readBytes()
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val m = manifest ?: error("Invalid .gameperf file: missing manifest.json")

        // Extract report if present
        val reportPath = if (reportBytes != null) {
            reportsDir.mkdirs()
            val reportFile = File(reportsDir, "imported_${m.session.id}.html")
            reportFile.writeBytes(reportBytes!!)
            reportFile.absolutePath
        } else {
            ""
        }

        return m.session.toHistoryEntry().copy(
            reportPath = reportPath,
            videoPath = "",  // video is never in the pack — clear any stale local path
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /** Quick metadata extraction without fully unpacking — for Drive listing. */
    fun readManifest(packFile: File): Manifest? {
        return try {
            ZipInputStream(packFile.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == "manifest.json") {
                        return json.decodeFromString(
                            Manifest.serializer(),
                            zip.readBytes().toString(Charsets.UTF_8)
                        )
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Map of Drive appProperties to store as quick metadata (no download needed for listing). */
    fun appPropertiesFrom(entry: SessionHistory.HistoryEntry): Map<String, String> = mapOf(
        "gameperf_version"  to FORMAT_VERSION.toString(),
        "session_id"        to entry.id,
        "grade"             to entry.grade.toString(),
        "avg_fps"           to entry.avgFps.toString(),
        "device"            to entry.deviceModel,
        "game"              to entry.gamePackage.substringAfterLast('.'),
        "duration_s"        to entry.duration.toString(),
        "date"              to entry.date,
        "score"             to entry.score.toString(),
        "tag"               to entry.tag.name,
        "session_name"      to entry.name.take(80),
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Private
    // ─────────────────────────────────────────────────────────────────────────

    /** Converts HistoryEntry → SerializableEntry using SessionHistory's internal converter. */
    private fun serializableEntryFrom(e: SessionHistory.HistoryEntry): SessionHistory.SerializableEntry {
        // SessionHistory.toSerializable() is private, so we reconstruct manually.
        // This mirrors the exact mapping in SessionHistory.
        return SessionHistory.SerializableEntry(
            id             = e.id,
            name           = e.name,
            gamePackage    = e.gamePackage,
            deviceModel    = e.deviceModel,
            grade          = e.grade.toString(),
            deviceGrade    = e.deviceGrade.toString(),
            avgFps         = e.avgFps,
            duration       = e.duration,
            date           = e.date,
            reportPath     = e.reportPath,
            videoPath      = e.videoPath,
            tag            = e.tag.name,
            competitorName = e.competitorName,
            p1Fps          = e.p1Fps,
            p5Fps          = e.p5Fps,
            avgFrameTime   = e.avgFrameTime,
            p95FrameTime   = e.p95FrameTime,
            p99FrameTime   = e.p99FrameTime,
            peakMemMb      = e.peakMemMb,
            avgCpu         = e.avgCpu,
            maxTemp        = e.maxTemp,
            score          = e.score,
            markers        = e.markers.map { m ->
                SessionHistory.SerializableMarker(
                    id    = m.id,
                    tsMs  = m.timestampMs,
                    ts    = m.timestampSeconds,
                    type  = m.type,
                    title = m.title,
                    note  = m.note,
                    color = m.colorHex,
                )
            },
            isFavorite     = e.isFavorite,
        )
    }

    /** Expose toHistoryEntry() for use in import. */
    private fun SessionHistory.SerializableEntry.toHistoryEntry(): SessionHistory.HistoryEntry =
        SessionHistory.HistoryEntry(
            id             = id,
            name           = name,
            gamePackage    = gamePackage,
            deviceModel    = deviceModel,
            grade          = grade.firstOrNull() ?: 'F',
            deviceGrade    = deviceGrade.firstOrNull() ?: ' ',
            avgFps         = avgFps,
            duration       = duration,
            date           = date,
            reportPath     = reportPath,
            videoPath      = videoPath,
            tag            = try { SessionHistory.SessionTag.valueOf(tag) }
                             catch (_: Exception) { SessionHistory.SessionTag.OUR_GAME },
            competitorName = competitorName,
            p1Fps          = p1Fps,
            p5Fps          = p5Fps,
            avgFrameTime   = avgFrameTime,
            p95FrameTime   = p95FrameTime,
            p99FrameTime   = p99FrameTime,
            peakMemMb      = peakMemMb,
            avgCpu         = avgCpu,
            maxTemp        = maxTemp,
            score          = score,
            markers        = markers.map { it.toSessionMarker() },
            isFavorite     = isFavorite,
        )
}
