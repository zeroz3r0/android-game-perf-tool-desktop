package com.gameperf.desktop.core

import java.io.File

/**
 * Single source of truth for deleting session files and bidirectional pruning.
 *
 * Responsibilities:
 *  - Delete HTML reports and all video segments associated with a session.
 *  - Scan `~/GamePerf Reports/` and remove files not referenced in `history.json`.
 *  - Repair `history.json` entries that reference non-existent files (vacía paths, no borra entries).
 *  - Clean leftover `comparativa_*.html` in `java.io.tmpdir`.
 *
 * Tolerance contract: no method throws. All failures are logged to `System.err`.
 * Does NOT recurse into subdirectories (protege `updates/`).
 */
object FileCleanup {

    private val SEGMENT_REGEX = Regex("""video_(\d{8}_\d{6})_\d+\.mp4""")
    private val WHITELIST_PREFIXES = listOf("informe_", "video_", "recording_", "comparativa_")

    /** Test-only override. When non-null, overrides the reports directory. */
    internal var reportsDirOverride: File? = null

    private val reportsDir: File
        get() = reportsDirOverride ?: File(System.getProperty("user.home"), "GamePerf Reports")

    /**
     * Extract the sessionId from a modern video path.
     *
     * Matches `video_YYYYMMDD_HHMMSS_N.mp4` → returns `"YYYYMMDD_HHMMSS"`.
     * Returns null for empty paths, legacy `recording_*.mp4` naming, or any path
     * whose filename does not match the modern regex.
     */
    fun extractSessionId(videoPath: String): String? {
        if (videoPath.isEmpty()) return null
        val filename = File(videoPath).name
        return SEGMENT_REGEX.matchEntire(filename)?.groupValues?.get(1)
    }

    /** Private helper: list all `video_${sessionId}_*.mp4` segments in reportsDir. */
    private fun matchSegmentsForSession(sessionId: String): Array<File> =
        reportsDir.listFiles { f ->
            f.isFile && f.name.startsWith("video_${sessionId}_") && f.name.endsWith(".mp4")
        } ?: emptyArray()

    /** Private helper: delete a file swallowing any error. Never throws. */
    private fun tolerantDelete(file: File) {
        try {
            if (file.path.isEmpty()) return
            if (!file.exists()) return
            val ok = file.delete()
            if (!ok) {
                System.err.println("FileCleanup: delete returned false for ${file.absolutePath}")
            }
        } catch (t: Throwable) {
            System.err.println("FileCleanup: exception deleting ${file.absolutePath}: ${t.message}")
        }
    }

    /**
     * Delete the HTML report and all video segments associated with a history entry.
     *
     * Tolerance:
     *  - Empty `reportPath` or `videoPath` are no-ops for their respective targets.
     *  - Missing files are no-ops.
     *  - Delete failures are logged but never propagated.
     *
     * Never throws.
     */
    fun deleteSessionFiles(entry: SessionHistory.HistoryEntry) {
        // 1) HTML report
        val reportPath = entry.reportPath
        if (reportPath.isNotEmpty()) {
            tolerantDelete(File(reportPath))
        }

        // 2) Video segments (branching: modern / legacy / unknown)
        val videoPath = entry.videoPath
        if (videoPath.isNotEmpty()) {
            val file = File(videoPath)
            val name = file.name
            when {
                name.startsWith("video_") -> {
                    val sessionId = extractSessionId(videoPath)
                    if (sessionId != null) {
                        matchSegmentsForSession(sessionId).forEach { tolerantDelete(it) }
                    } else {
                        // Modern prefix but regex did not match: fallback literal delete.
                        System.err.println("FileCleanup: video_ path no matchea regex: $videoPath")
                        tolerantDelete(file)
                    }
                }
                name.startsWith("recording_") -> {
                    // Legacy naming: tolerant literal delete, no segmentation.
                    tolerantDelete(file)
                }
                else -> {
                    // Unknown pattern. Log and skip for safety (whitelist-protected).
                    System.err.println("FileCleanup: videoPath fuera de patrones conocidos: $videoPath")
                }
            }
        }
    }

    /**
     * Bidirectional prune:
     *
     * Pass 1 — files → json:
     *   List top-level files in `reportsDir` matching the whitelist prefixes (excluding
     *   `history.json`). Delete any file not referenced by `snapshot`'s `reportPath`
     *   or `videoPath` (including any video segment matching a session that IS referenced
     *   — segments with sessionIds present in the snapshot are preserved).
     *   Subdirectories are ignored (protects `updates/`).
     *
     * Pass 2 — json → files:
     *   For each entry in `snapshot`, if `reportPath` or `videoPath` point to a
     *   non-existent file, produce a repaired copy with the missing path set to `""`.
     *   Repaired entries are NOT removed — they retain their metrics.
     *
     * Never throws. Missing `reportsDir` returns `PruneResult(0, [])`.
     */
    fun pruneOrphans(snapshot: List<SessionHistory.HistoryEntry>): PruneResult {
        val dir = reportsDir
        if (!dir.exists() || !dir.isDirectory) {
            return PruneResult(deletedFiles = 0, repairedEntries = emptyList())
        }

        // Build reference set: all paths referenced by the snapshot, plus session IDs
        // so that multi-segment videos are preserved even if only _0 is in the JSON.
        val referencedPaths = HashSet<String>()
        val referencedSessionIds = HashSet<String>()
        for (entry in snapshot) {
            if (entry.reportPath.isNotEmpty()) {
                referencedPaths.add(File(entry.reportPath).absolutePath)
            }
            if (entry.videoPath.isNotEmpty()) {
                referencedPaths.add(File(entry.videoPath).absolutePath)
                val sid = extractSessionId(entry.videoPath)
                if (sid != null) referencedSessionIds.add(sid)
            }
        }

        // Pass 1: files -> json
        var deleted = 0
        val files = dir.listFiles() ?: emptyArray()
        for (f in files) {
            if (!f.isFile) continue  // skip subdirectories (protects updates/)
            val name = f.name
            if (name == "history.json") continue
            if (WHITELIST_PREFIXES.none { name.startsWith(it) }) continue

            val absolutePath = f.absolutePath
            if (absolutePath in referencedPaths) continue

            // Segment preservation: if this file is a video segment whose sessionId
            // is referenced by some snapshot entry, preserve it.
            if (name.startsWith("video_") && name.endsWith(".mp4")) {
                val match = SEGMENT_REGEX.matchEntire(name)
                val sid = match?.groupValues?.get(1)
                if (sid != null && sid in referencedSessionIds) {
                    continue
                }
            }

            tolerantDelete(f)
            if (!f.exists()) deleted++
        }

        // Pass 2: json -> files
        val repaired = mutableListOf<SessionHistory.HistoryEntry>()
        for (entry in snapshot) {
            var needsRepair = false
            var newReport = entry.reportPath
            var newVideo = entry.videoPath

            if (entry.reportPath.isNotEmpty() && !File(entry.reportPath).exists()) {
                newReport = ""
                needsRepair = true
            }
            if (entry.videoPath.isNotEmpty() && !File(entry.videoPath).exists()) {
                newVideo = ""
                needsRepair = true
            }

            if (needsRepair) {
                repaired.add(entry.copy(reportPath = newReport, videoPath = newVideo))
            }
        }

        return PruneResult(deletedFiles = deleted, repairedEntries = repaired)
    }

    /**
     * Delete leftover `comparativa_*.html` files in `java.io.tmpdir`. Used on init
     * to clean up reports left behind by previous (possibly crashed) runs.
     *
     * Never throws. Silently ignores a missing tmpdir.
     */
    fun pruneTmpComparisons() {
        try {
            val tmp = File(System.getProperty("java.io.tmpdir"))
            if (!tmp.exists() || !tmp.isDirectory) return
            val candidates = tmp.listFiles { f ->
                f.isFile && f.name.startsWith("comparativa_") && f.name.endsWith(".html")
            } ?: return
            candidates.forEach { tolerantDelete(it) }
        } catch (t: Throwable) {
            System.err.println("FileCleanup: pruneTmpComparisons failed: ${t.message}")
        }
    }

    /**
     * Result of `pruneOrphans`.
     *
     * @property deletedFiles number of physical files removed during pass 1
     * @property repairedEntries snapshot entries that need to be persisted with cleaned paths.
     *                           The caller is responsible for calling `SessionHistory.updateEntry`
     *                           on each one.
     */
    data class PruneResult(
        val deletedFiles: Int,
        val repairedEntries: List<SessionHistory.HistoryEntry>
    )

    /**
     * Repair history entries whose `videoPath` points to the first segment (`_0.mp4`)
     * of a multi-segment recording. Until v3.1.9 the recording loop only exposed the
     * first segment, so longer-than-3-minute sessions appeared truncated to ~2:56.
     *
     * For each such entry:
     *   1. Find all sibling segments (`video_${sessionId}_*.mp4`) on disk
     *   2. If 2+ segments exist, run them through ffmpeg concat into `video_${sessionId}.mp4`
     *   3. If concat succeeds, return a repaired entry pointing at the unified file
     *
     * The original `_N.mp4` segments are NOT deleted by this function. They remain on
     * disk as a backup so a failed concat can be retried, and `pruneOrphans` continues
     * preserving them via the segment-preservation rule (sessionId match).
     *
     * Returns the list of entries that were successfully repaired. Caller is responsible
     * for persisting them via `SessionHistory.updateEntry`.
     *
     * Never throws. Skips entries silently when:
     *   - videoPath is empty or non-existent
     *   - filename does not match `video_${sessionId}_${N}.mp4` (legacy or unified already)
     *   - only 1 segment exists for the session (nothing to concat)
     *   - the unified file already exists (previous repair run succeeded)
     *   - ffmpeg is not installed or concat fails
     */
    fun repairTruncatedVideos(snapshot: List<SessionHistory.HistoryEntry>): List<SessionHistory.HistoryEntry> {
        val dir = reportsDir
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        val repaired = mutableListOf<SessionHistory.HistoryEntry>()

        for (entry in snapshot) {
            try {
                val videoPath = entry.videoPath
                if (videoPath.isEmpty()) continue

                val currentFile = File(videoPath)
                if (!currentFile.exists()) continue

                // Only consider entries that look like a segment (`_0.mp4`, `_1.mp4`, ...).
                // The unified path produced by the new flow is `video_${sessionId}.mp4`
                // (no `_N` suffix) so it does NOT match SEGMENT_REGEX and is skipped here.
                val sessionId = extractSessionId(videoPath) ?: continue

                // Find all siblings.
                val segments = matchSegmentsForSession(sessionId)
                    .sortedBy { f ->
                        // Sort by the trailing _N before .mp4 to guarantee correct order.
                        val numStr = f.name
                            .removePrefix("video_${sessionId}_")
                            .removeSuffix(".mp4")
                        numStr.toIntOrNull() ?: Int.MAX_VALUE
                    }

                if (segments.size < 2) continue // single segment = nothing to repair

                val unified = File(dir, "video_${sessionId}.mp4")
                if (unified.exists() && unified.length() > 0) {
                    // Already repaired in a previous run but the entry still points
                    // at the segment. Just rewrite the path.
                    repaired.add(entry.copy(videoPath = unified.absolutePath))
                    continue
                }

                val result = AdbBridge.concatSegments(segments.toList(), unified)
                // Only count as repaired when concat produced the actual unified file.
                // If concatSegments fell back to returning a segment (ffmpeg absent/failed),
                // that is NOT a repair — the entry already points at a segment.
                if (result != null && result.exists() && result.length() > 0
                    && result.absolutePath == unified.absolutePath
                ) {
                    repaired.add(entry.copy(videoPath = result.absolutePath))
                    System.err.println(
                        "FileCleanup.repairTruncatedVideos: ${entry.id} → unified ${segments.size} segments into ${result.name}"
                    )
                }
                // On concat failure: do nothing. Entry stays pointing at _0.mp4.
                // User keeps the truncated experience but no data is lost.
            } catch (t: Throwable) {
                System.err.println("FileCleanup.repairTruncatedVideos: entry ${entry.id}: ${t.message}")
            }
        }

        return repaired
    }
}
