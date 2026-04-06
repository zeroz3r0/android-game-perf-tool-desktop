# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.1.0] - 2026-04-06

### Added
- **Session retention policy**: hard maximum of 5 recent sessions enforced. When capturing a new session beyond the limit, the oldest one (JSON entry + video segments + HTML report) is silently removed. A passive hint appears in the history header at 5/5 capacity (`Historial: 5/5 - la próxima captura reemplazará la más antigua`).
- **PDF export**: new "Exportar PDF" buttons on HomeScreen (per history entry), ResultsScreen (post-capture), and ComparisonScreen. Exports open a native file picker (macOS NSSavePanel / Windows IFileDialog / GTK) and let the user save the report anywhere on their machine. The exported PDF is fully decoupled from the app — it lives in the user's filesystem and is never touched by the retention policy.
- **Startup filesystem cleanup (`pruneOrphans`)**: on launch, the app scans `~/GamePerf Reports/` and cleans up orphaned HTML/video files that are no longer referenced in `history.json`, and repairs entries whose paths point to missing files. Never touches the `updates/` subdirectory, `history.json`, or files outside the whitelist prefixes (`informe_`, `video_`, `recording_`, `comparativa_`).
- **Chart.js is now embedded inline** in every generated HTML report (instead of loading from CDN). Reports work 100% offline after generation and can be moved/emailed anywhere.
- **Comparison reports moved to tmpdir**: comparison HTML files no longer pollute `~/GamePerf Reports/`. They live in the system temp directory and are cleaned up when the app closes or on next launch.
- **Unit tests**: `FileCleanupTest` (16 tests covering segment-aware deletion, bidirectional prune, subdir protection, whitelist), `SessionHistoryTest` (7 tests covering retention, delete return value, updateEntry, concurrent access). New total: 61 tests.

### Fixed
- **Manual delete bug**: the trash button in HomeScreen now correctly deletes the JSON entry, all video segments, and the HTML report together. Previously it only removed the JSON entry, leaving orphaned files behind. The AlertDialog text is updated to reflect the new behavior.
- **Multi-segment video deletion**: fixed a latent bug where videos recorded in multiple segments (`video_${sessionId}_0.mp4`, `_1.mp4`, ...) were only partially deleted because the JSON only persisted the first segment path. Deletion now matches all segments for a session by regex.

### Changed
- `SessionHistory.MAX_ENTRIES` reduced from 20 to 5.
- `SessionHistory.addEntry` now returns the list of evicted entries for cleanup.
- `SessionHistory.deleteEntry` now returns the removed entry for cleanup.
- `SessionHistory` JSON writes are now `@Synchronized` to prevent concurrent corruption.
- `ReportGenerator.generateComparison` accepts an optional `outputDir` parameter (defaults to `java.io.tmpdir`).

### Dependencies
- Added `com.microsoft.playwright:playwright:1.45.0` (Apache-2.0) for HTML-to-PDF conversion via headless Chromium. First-run downloads Chromium (~180 MB) to `~/.cache/ms-playwright/`. Subsequent runs are fully offline.

### Migration notes
- **No data loss on first run**: `pruneOrphans` only removes orphaned files and repairs broken references. Existing sessions remain intact. The 5-session limit applies organically starting from the next capture.
- To preserve a session permanently, use the new "Exportar PDF" button. The PDF is saved to the user's filesystem and is never touched by the retention policy.
- First PDF export requires internet to download Chromium (shown via "Preparando motor PDF" dialog). Subsequent exports are offline.

## [3.0.0] - 2026-03-31

### Added
- Embedded video player with native FPS frame-by-frame playback
- Interactive timeline with FPS overlay, draggable playhead, and color zones
- Session markers (Interstitial, Video Reward, Loading, Scene Change, Custom notes)
- Color picker for markers (10 preset colors)
- Competition comparison mode with session tagging
- ComparisonScreen with side-by-side metrics and radar charts
- Auto-updater via GitHub Releases (checks on startup)
- CI/CD pipeline for multi-platform builds (macOS, Linux, Windows)
- Custom app icon
- Stop capture confirmation dialog
- Delete history entries with confirmation
- Keyboard shortcuts: Space (play/pause), arrows (seek ±5s), Escape (stop capture)
- Pulsing recording indicator animation
- Unit tests for HardwareScoring, AutoUpdater, and Formatting utilities
- Version single-source via gradle.properties

### Fixed
- Video player locale bug (Spanish decimal comma broke ffmpeg)
- CPU measurement now uses delta between reads (not cumulative)
- Color classification for reverse metrics (P1 FPS)
- Capture timer independent of ADB command latency
- Device disconnect detection during capture
- All String.format calls now use Locale.US

### Changed
- Video player uses ffmpeg batch extraction + Skia direct JPEG decode
- HTML report redesigned with dark theme and Chart.js 4
- Timeline pauses playback when user scrubs
- History now stores up to 20 sessions (was 5)

## [1.0.0] - 2026-03-30

### Added
- Initial release
- ADB device detection and connection
- Live FPS, frame time, memory, CPU, temperature capture
- HTML report generation with Chart.js
- Session history with persistence
- WiFi ADB mode
- Hardware scoring and device grading
- Dual grading system (general + hardware-adjusted)
- Video recording during capture sessions
- FPS-timestamped correlation table in HTML report
