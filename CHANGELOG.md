# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
