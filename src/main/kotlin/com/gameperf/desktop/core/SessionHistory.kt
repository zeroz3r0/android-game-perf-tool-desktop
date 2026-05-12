package com.gameperf.desktop.core

import com.gameperf.desktop.core.conclusions.Conclusion
import com.gameperf.desktop.core.devactions.DevActionBrief
import com.gameperf.desktop.core.events.DetectedEvent
import com.gameperf.desktop.core.metrics.MetricsAggregates
import com.gameperf.desktop.core.model.FPowerDiagnostic
import com.gameperf.desktop.viewmodel.DetectionMode
import com.gameperf.desktop.viewmodel.MarkerType
import com.gameperf.desktop.viewmodel.SessionMarker
import com.gameperf.desktop.viewmodel.TimedSample
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

    /**
     * Hard retention cap for non-favorite ("recent") sessions. Raised in v4.3.7 from 5 → 100
     * after a real QA user lost their entire S23 history doing five quick Fake-mode tests
     * in a row (the 5 fakes silently evicted all five real sessions). The cap is now wide
     * enough that no realistic test session burst will erase the user's real captures.
     *
     * Layered safety nets ALSO present (defense-in-depth, see Layers 2-4 in v4.3.7):
     *  - Real sessions auto-favorite on save, so they cannot be evicted at all
     *  - Every save rotates a 3-deep `.bak.{1,2,3}` backup so a corrupted history.json
     *    can be recovered from disk
     *  - The UI raises a confirmation dialog before evicting a non-fake non-favorite entry
     */
    const val MAX_ENTRIES = 100

    /**
     * Logical schema version for the session history JSON payload.
     *
     * History:
     *  - v1..v3 — pre-kotlinx.serialization, hand-rolled JSON shapes (no explicit version field).
     *  - v4 — kotlinx.serialization migration (v4.1.0). [SerializableEntry] became the canonical
     *    on-disk schema. Forward-compat is provided by `Json { ignoreUnknownKeys = true }`.
     *  - v5 — v4.4.0 schema bump for `auto-event-detection-and-clean-metrics`. The on-disk
     *    keys were promised but the data classes were NOT widened — Bug 2 (v4.4.1
     *    `auto-event-detection-not-marking`) revealed that the encoder silently dropped
     *    the new fields. v4.4.1 is the first build where the schema actually carries the
     *    payload: `events`, `detectionMode` (persisted as the enum's `.name` String for
     *    forward compat with future enum values), `detectorWarnings`, `rawAggregates`,
     *    `filteredAggregates`, `conclusions`, `captureStartMs`. All defaulted (empty list
     *    or null) so pre-v4.4.1 rows still deserialize via `ignoreUnknownKeys = true`.
     *
     * The disk file does NOT carry this constant explicitly (no breaking change to existing
     * `history.json` payloads). It exists as a SOURCE-OF-TRUTH integer that callers (export
     * pipelines, future migrations, tests) can reference. Loading is fully forward-compat:
     * a v4 / v4.4.0 file deserializes into a v5-aware code path with the new fields
     * defaulting to empty/null. There is no v4→v5 *transformation* required — only an
     * additive widening of the in-memory model.
     *
     * @since v4.4.0 (schema bump) / v4.4.1 (field set actually persisted)
     */
    const val SCHEMA_VERSION = 5

    /**
     * Threshold (as a fraction of [MAX_ENTRIES]) at which the UI counter turns red
     * to warn the user that the recents list is approaching capacity. Pulled out of
     * HomeScreen so the warning logic stays consistent with the cap.
     */
    const val WARNING_THRESHOLD_RATIO = 0.9

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
        // v4.2.0: FPS timeline data for re-viewing past sessions
        val fpsTimed: List<List<Int>> = emptyList(),
        // v4.4.1: auto-event detection payload promised by the v4.4.0 schema bump but never
        // persisted until now. All defaulted so pre-v4.4.1 history.json rows hydrate cleanly
        // via Json { ignoreUnknownKeys = true }.
        val events: List<DetectedEvent> = emptyList(),
        /** [DetectionMode.name] persisted as String for forward compat with new enum values. */
        val detectionMode: String? = null,
        val detectorWarnings: List<String> = emptyList(),
        val rawAggregates: MetricsAggregates? = null,
        val filteredAggregates: MetricsAggregates? = null,
        val conclusions: List<Conclusion> = emptyList(),
        val captureStartMs: Long? = null,
        // v4.4.1 (temperature-not-shown): availability flag for thermal data. Defaults to
        // `true` so pre-v4.4.1 history.json rows hydrate as "thermal data is trustworthy"
        // (the v4.3.x semantics — the report renders the raw value). Set to `false` by the
        // ViewModel when AdbThermalParser could not classify any CPU/SKIN zone within the
        // plausibility window (unsupported vendor, permission denied, ...). The report then
        // renders "N/D" plus a diagnostic banner instead of a misleading "0°C".
        val thermalAvailable: Boolean = true,
        // v4.5.0 — FPower metric (mW per frame, see core/model/FPowerSnapshot). Defaults
        // mirror the thermalAvailable pattern: `fpowerAvailable=true` preserves backward
        // compat with v4.4.1 history.json rows (no FPower section rendered if history is
        // empty AND fpowerAvailable=true). [FPowerDiagnostic] is itself @Serializable so
        // it nests cleanly into the entry. [fpowerTimed] uses `List<List<Double>>` of
        // shape `[second, value]` to mirror the [fpsTimed] workaround for non-@Serializable
        // [TimedSample] — converted both ways in [toSerializable] / [toHistoryEntry].
        val fpowerAvailable: Boolean = true,
        val fpowerDiagnostic: FPowerDiagnostic? = null,
        val fpowerHistory: List<Double> = emptyList(),
        val fpowerTimed: List<List<Double>> = emptyList(),
        val fpowerAvg: Double = 0.0,
        val fpowerPeak: Double = 0.0,
        // v4.5.0 Sprint 3 — DevActionBrief (spec DAB-007). Defaulted to the
        // empty brief shape (items=emptyList, topN=5) for backward compat with
        // pre-Sprint-3 history.json rows. [DevActionBrief] is @Serializable so
        // nests cleanly. The brief is never present on the wire for sessions
        // captured before Sprint 3 ships; loaders rely on Json {
        // ignoreUnknownKeys = true } AND this defaulted field to hydrate them
        // safely.
        val devActionBrief: DevActionBrief = DevActionBrief(),
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
        /** v4.2.0: FPS timeline samples (second, fps) for re-viewing sessions. */
        val fpsTimed: List<Pair<Int, Int>> = emptyList(),
        // v4.4.1: domain-side mirror of the auto-event detection payload (see
        // SerializableEntry above). detectionMode is the typed enum here; the
        // SerializableEntry stores its `.name` String so we keep core/ ignorant
        // of viewmodel/ enum class membership at the wire level.
        val events: List<DetectedEvent> = emptyList(),
        val detectionMode: DetectionMode? = null,
        val detectorWarnings: List<String> = emptyList(),
        val rawAggregates: MetricsAggregates? = null,
        val filteredAggregates: MetricsAggregates? = null,
        val conclusions: List<Conclusion> = emptyList(),
        val captureStartMs: Long? = null,
        // v4.4.1 (temperature-not-shown): mirror of [SerializableEntry.thermalAvailable].
        // Default `true` keeps every existing call site that constructs a HistoryEntry
        // without naming this argument byte-equivalent to the pre-v4.4.1 behavior.
        val thermalAvailable: Boolean = true,
        // v4.5.0 — FPower mirror of [SerializableEntry] fpower* fields. [fpowerTimed]
        // is the typed [TimedSample] form on the domain side; the wire format flattens
        // each sample to a `[second, value]` 2-list. Defaults align with v4.4.1 compat:
        // `fpowerAvailable=true`, empty history, null diagnostic — a pre-v4.5.0 row
        // hydrates as "fpower section not present, render unchanged".
        val fpowerAvailable: Boolean = true,
        val fpowerDiagnostic: FPowerDiagnostic? = null,
        val fpowerHistory: List<Double> = emptyList(),
        val fpowerTimed: List<TimedSample> = emptyList(),
        val fpowerAvg: Double = 0.0,
        val fpowerPeak: Double = 0.0,
        // v4.5.0 Sprint 3 — DevActionBrief (spec DAB-007). Mirror of the
        // [SerializableEntry] field. Defaulted to the empty-brief shape so
        // every existing call site that constructs a HistoryEntry without
        // naming this argument stays byte-equivalent to pre-Sprint-3 behavior.
        val devActionBrief: DevActionBrief = DevActionBrief(),
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
        fpsTimed = fpsTimed.map { listOf(it.first, it.second) },
        // v4.4.1: auto-event detection payload mirroring.
        events = events,
        detectionMode = detectionMode?.name,
        detectorWarnings = detectorWarnings,
        rawAggregates = rawAggregates,
        filteredAggregates = filteredAggregates,
        conclusions = conclusions,
        captureStartMs = captureStartMs,
        // v4.4.1: persist the thermal-availability flag verbatim.
        thermalAvailable = thermalAvailable,
        // v4.5.0 — FPower fields. [TimedSample] is non-@Serializable so we flatten
        // each sample to a 2-element `[second, value]` Double list (mirrors the
        // [fpsTimed] precedent).
        fpowerAvailable = fpowerAvailable,
        fpowerDiagnostic = fpowerDiagnostic,
        fpowerHistory = fpowerHistory,
        fpowerTimed = fpowerTimed.map { listOf(it.second.toDouble(), it.value) },
        fpowerAvg = fpowerAvg,
        fpowerPeak = fpowerPeak,
        // v4.5.0 Sprint 3 — DevActionBrief forward through the wire format.
        devActionBrief = devActionBrief,
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
        fpsTimed = fpsTimed.mapNotNull { if (it.size >= 2) it[0] to it[1] else null },
        // v4.4.1: decode wire String → typed enum. Unknown / future enum names fall back to
        // null instead of crashing the load (keeps the same forward-compat stance as
        // MarkerTypeSerializer above).
        events = events,
        detectionMode = detectionMode?.let {
            try { DetectionMode.valueOf(it) } catch (_: Exception) { null }
        },
        detectorWarnings = detectorWarnings,
        rawAggregates = rawAggregates,
        filteredAggregates = filteredAggregates,
        conclusions = conclusions,
        captureStartMs = captureStartMs,
        // v4.4.1: hydrate the thermal-availability flag (default true for legacy rows).
        thermalAvailable = thermalAvailable,
        // v4.5.0 — FPower hydration. The wire form is `List<List<Double>>` (each entry
        // shape `[second, value]`); skip malformed sub-arrays defensively (mirrors the
        // [fpsTimed] decoder hardening in this same converter).
        fpowerAvailable = fpowerAvailable,
        fpowerDiagnostic = fpowerDiagnostic,
        fpowerHistory = fpowerHistory,
        fpowerTimed = fpowerTimed.mapNotNull {
            if (it.size >= 2) TimedSample(it[0].toInt(), it[1]) else null
        },
        fpowerAvg = fpowerAvg,
        fpowerPeak = fpowerPeak,
        // v4.5.0 Sprint 3 — DevActionBrief hydration. Defaulted to the empty
        // brief on the SerializableEntry side, so a pre-Sprint-3 row decodes
        // as DevActionBrief() and rendering omits the section (DAB-008
        // negative case).
        devActionBrief = devActionBrief,
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

    /**
     * Maximum number of `history.json.bak.{N}` backups kept in rotation. v4.3.7 chose 3 as
     * a balance: deep enough that two consecutive bad saves still leave a usable backup,
     * shallow enough that the user's reports folder doesn't accumulate dozens of stale
     * snapshots. The chain is rotated on every [save] call.
     */
    private const val BACKUP_DEPTH = 3

    private fun backupFile(n: Int): File =
        File(historyFile.parentFile, "${historyFile.name}.bak.$n")

    /**
     * Rotate the backup chain. After this returns:
     *  - `history.json.bak.{N}` holds the contents of what was previously `bak.{N-1}`
     *  - `history.json.bak.1` holds the contents of the current `history.json`
     *  - The previous `bak.{BACKUP_DEPTH}` is evicted (oldest snapshot lost).
     *
     * If the current `history.json` does not yet exist (first save ever), no rotation
     * happens — there is nothing to back up.
     *
     * Defensive: any failure inside rotation is logged and swallowed. We never let a
     * backup-rotation failure crash the actual save path; losing a backup is recoverable
     * (we still have other backups + the live save), but losing the save itself is not.
     */
    private fun rotateBackups() {
        try {
            if (!historyFile.exists()) return
            // Walk from the OLDEST (BACKUP_DEPTH) down to 2: each becomes the next slot.
            // Order matters — going top-down would clobber files we still need.
            for (n in BACKUP_DEPTH downTo 2) {
                val src = backupFile(n - 1)
                val dst = backupFile(n)
                if (src.exists()) {
                    Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
            // Current → bak.1
            Files.copy(historyFile.toPath(), backupFile(1).toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            System.err.println("[GamePerf] Backup rotation failed (non-fatal): ${e.message}")
        }
    }

    @Synchronized
    fun save(entries: List<HistoryEntry>) {
        try {
            historyFile.parentFile?.mkdirs()

            // v4.3.7 — Layer 3: rotate backups BEFORE overwriting history.json.
            // If the new payload is wrong (corrupt entry, accidental eviction), the
            // user can recover via SessionHistory.recoverFromBackup() because the
            // pre-overwrite state lives in history.json.bak.1.
            rotateBackups()

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

    /**
     * Outcome of a [recoverFromBackup] call.
     *
     * @property entriesBefore  number of entries in `history.json` BEFORE recovery ran
     * @property entriesAfter   number of entries in `history.json` AFTER recovery — equals
     *                          [entriesBefore] when no backup beat the live count
     * @property restoredFrom   filename (NOT path) of the backup we restored from, or null
     *                          when no recovery happened
     */
    data class RecoveryReport(
        val entriesBefore: Int,
        val entriesAfter: Int,
        val restoredFrom: String?,
    )

    /**
     * Try to repopulate `history.json` from the deepest still-usable backup. v4.3.7
     * surfaces this as a "Recuperar de respaldo" UI button — only invoked on explicit
     * user request (it's destructive: the live history is overwritten).
     *
     * Picks the backup with the most parseable entries; on a tie, picks the most recent
     * by mtime. NEVER restores a backup that has fewer entries than the live history —
     * doing so would PROVOKE the data loss the feature is trying to prevent.
     *
     * Returns a [RecoveryReport] so the UI can render a meaningful toast even when
     * nothing was restored (e.g. "0 sesiones recuperadas — los respaldos no contienen
     * más datos que tu historial actual").
     */
    @Synchronized
    fun recoverFromBackup(): RecoveryReport {
        val before = load().size
        // Build (file, entryCount, mtime) for every existing backup that parses cleanly.
        val candidates = (1..BACKUP_DEPTH).mapNotNull { n ->
            val f = backupFile(n)
            if (!f.exists()) return@mapNotNull null
            val parsed = try {
                json.decodeFromString<List<SerializableEntry>>(f.readText())
            } catch (e: Exception) {
                System.err.println("[GamePerf] Backup ${f.name} unreadable, skipping: ${e.message}")
                return@mapNotNull null
            }
            Triple(f, parsed.size, f.lastModified())
        }
        if (candidates.isEmpty()) {
            return RecoveryReport(entriesBefore = before, entriesAfter = before, restoredFrom = null)
        }
        // Pick the backup with the largest entry count, tie-break on most recent mtime.
        val best = candidates.maxWithOrNull(
            compareBy<Triple<File, Int, Long>> { it.second }.thenBy { it.third }
        ) ?: return RecoveryReport(before, before, null)
        if (best.second <= before) {
            // No backup contains more data than the live history — restoring would lose data.
            return RecoveryReport(entriesBefore = before, entriesAfter = before, restoredFrom = null)
        }
        return try {
            // Copy the backup into history.json. We use copy (not move) so the backup
            // chain stays intact for any subsequent recovery attempt.
            Files.copy(best.first.toPath(), historyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            RecoveryReport(
                entriesBefore = before,
                entriesAfter = best.second,
                restoredFrom = best.first.name,
            )
        } catch (e: Exception) {
            System.err.println("[GamePerf] Recovery copy failed: ${e.message}")
            RecoveryReport(entriesBefore = before, entriesAfter = before, restoredFrom = null)
        }
    }

    /**
     * Pure classifier: returns true if [entry] looks like a fake / test / emulator session
     * that the user does NOT want auto-favorited. v4.3.7 introduced this rule after a real
     * QA user lost their entire S23 session history because five quick Fake-mode runs
     * (deviceModel = "Fake", gamePackage = "com.test.game") silently evicted the cap of
     * real sessions. Real sessions are auto-favorited on insert (see [addEntry]) so a
     * burst of fakes can never push them out again.
     *
     * Detection rules (evaluated in order; ANY match → fake/test):
     *   1. deviceModel == "Fake" (literal, used by FakeAdbBridge in tests/demo mode)
     *   2. deviceModel.isEmpty() (no device probe attached)
     *   3. deviceModel.startsWith("emulator-") (Android emulator AVDs)
     *   4. gamePackage == "com.test.game" (literal, the test placeholder package)
     *   5. gamePackage.isEmpty() (probe ran with no package selected)
     * Otherwise → real session.
     *
     * The user can still manually toggle [isFavorite] off on a real session if they
     * intentionally want it auto-evictable; addEntry only ADDS the flag, it never strips it.
     */
    fun isFakeOrTestSession(entry: HistoryEntry): Boolean {
        if (entry.deviceModel == "Fake") return true
        if (entry.deviceModel.isEmpty()) return true
        if (entry.deviceModel.startsWith("emulator-")) return true
        if (entry.gamePackage == "com.test.game") return true
        if (entry.gamePackage.isEmpty()) return true
        return false
    }

    @Synchronized
    fun addEntry(entry: HistoryEntry): List<HistoryEntry> {
        val all = load().toMutableList()
        // v4.3.7 — Layer 2: real sessions auto-favorite so a burst of fake/test runs
        // cannot silently evict them. We never CLEAR isFavorite here — if the user
        // manually starred a fake, that intent is preserved.
        val incoming = if (!entry.isFavorite && !isFakeOrTestSession(entry)) {
            entry.copy(isFavorite = true)
        } else {
            entry
        }
        all.add(0, incoming)
        // Favorites are never evicted — only recents respect MAX_ENTRIES.
        val favorites = all.filter { it.isFavorite }
        val recents = all.filter { !it.isFavorite }
        val topRecents = recents.take(MAX_ENTRIES)
        val evicted = if (recents.size > MAX_ENTRIES) recents.drop(MAX_ENTRIES) else emptyList()
        save(favorites + topRecents)
        return evicted
    }

    /**
     * v4.3.7 — Layer 4: outcome of analyzing whether inserting a new entry into the
     * current snapshot will evict any existing entry, and whether that eviction is
     * safe to perform silently. The AppViewModel uses this to decide:
     *
     *  - [NoEviction]: the recents bucket has room, just insert the new entry.
     *  - [SilentEviction]: cap reached, but the entry about to be evicted is a fake /
     *    test session, so we can drop it without bothering the user.
     *  - [ConfirmationRequired]: cap reached AND the entry about to be evicted is a
     *    REAL non-favorite session — show the [evictableEntry] in a dialog and let the
     *    user choose to (a) star it as favorite first, (b) confirm the eviction, or
     *    (c) cancel the new insert entirely.
     *  - [RequiresManualEviction]: reserved sealed branch for the future case where
     *    the analyzer detects a structurally impossible situation (e.g. an explicit
     *    favorited candidate that would push favorites past a hard cap). Not currently
     *    returned by [analyzeEvictionRisk] because Layer 2's auto-favoriting + an
     *    unbounded favorites bucket makes the situation unreachable; kept on the type
     *    so future cap policy changes don't have to break the API.
     */
    sealed interface EvictionAnalysis {
        /** No eviction needed — the recents bucket has room. */
        data class NoEviction(val candidate: HistoryEntry) : EvictionAnalysis
        /**
         * Cap reached and the entry about to be dropped is a fake/test session.
         * [evictableEntry] is the entry that will be removed when the new candidate
         * is inserted.
         */
        data class SilentEviction(
            val candidate: HistoryEntry,
            val evictableEntry: HistoryEntry,
        ) : EvictionAnalysis
        /**
         * Cap reached and the entry about to be dropped is a REAL non-favorite session.
         * The UI must show a confirmation dialog naming [evictableEntry] before
         * the candidate is allowed to land.
         */
        data class ConfirmationRequired(
            val candidate: HistoryEntry,
            val evictableEntry: HistoryEntry,
        ) : EvictionAnalysis
        /**
         * Every recent slot is taken by a favorite — no automatic choice is safe.
         * The UI must ask the user to unfavorite a session before retrying the insert.
         */
        data class RequiresManualEviction(val candidate: HistoryEntry) : EvictionAnalysis
    }

    /**
     * Pure analyzer used by AppViewModel to decide whether [candidate]'s insertion needs
     * a confirmation dialog or can proceed silently. Does NOT touch disk; takes the
     * snapshot in as a parameter so tests are deterministic and tiny.
     *
     * Logic mirrors [addEntry]'s eviction path:
     *  1. New favorites never evict anyone (they live in the unbounded favorites bucket).
     *  2. With auto-favorite (Layer 2), any insert that classifies as REAL becomes a
     *     favorite — and so this analyzer treats it as a favorite for sizing purposes.
     *  3. After accounting for the candidate, if the recents bucket would still fit
     *     under [MAX_ENTRIES] → [NoEviction].
     *  4. Otherwise the entry that WOULD be dropped is the oldest non-favorite. If it
     *     is fake → [SilentEviction]. If it is real → [ConfirmationRequired].
     *  5. Special case: every existing recent is a favorite → there is no candidate to
     *     drop → [RequiresManualEviction].
     */
    fun analyzeEvictionRisk(
        snapshot: List<HistoryEntry>,
        candidate: HistoryEntry,
    ): EvictionAnalysis {
        val candidateGoesToFavorites =
            candidate.isFavorite || !isFakeOrTestSession(candidate)
        // Recents-only view of what's already on disk.
        val recents = snapshot.filter { !it.isFavorite }
        // Will the new candidate enter the recents bucket?
        val recentsAfterInsert = recents.size + (if (candidateGoesToFavorites) 0 else 1)
        if (recentsAfterInsert <= MAX_ENTRIES) {
            return EvictionAnalysis.NoEviction(candidate)
        }
        // Eviction would happen — find the entry that would be dropped.
        // The save path stores favorites first then `recents.take(MAX_ENTRIES)`; the new
        // candidate sits at index 0, so the oldest non-favorite (last in `recents`) is
        // the one that falls off the tail. We re-order to be explicit about this:
        val evictable = recents.lastOrNull()
            // Unreachable: recentsAfterInsert > MAX_ENTRIES > 0 implies recents is non-empty,
            // OR candidate goes to recents (so even if existing recents were empty, the
            // candidate itself would be the only thing in the bucket and there's nothing
            // to drop). Surface it as a structural conflict for the UI to show a generic error.
            ?: return EvictionAnalysis.RequiresManualEviction(candidate)
        return if (isFakeOrTestSession(evictable)) {
            EvictionAnalysis.SilentEviction(candidate, evictable)
        } else {
            EvictionAnalysis.ConfirmationRequired(candidate, evictable)
        }
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
        markers: List<SessionMarker> = emptyList(),
        fpsTimed: List<Pair<Int, Int>> = emptyList()
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
            markers, fpsTimed = fpsTimed
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
