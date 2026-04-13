package com.gameperf.desktop.viewmodel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * v4.1.0 — Manages video playback state for ResultsScreen.
 *
 * Extracted from AppViewModel to reduce its size. Pure state holder —
 * no coroutines, no IO. The actual video rendering is in EmbeddedVideoPlayer.
 */
class VideoDelegate {

    private val _videoPosition = MutableStateFlow(0L)
    val videoPosition: StateFlow<Long> = _videoPosition

    private val _isVideoPlaying = MutableStateFlow(false)
    val isVideoPlaying: StateFlow<Boolean> = _isVideoPlaying

    private val _videoDuration = MutableStateFlow(0L)
    val videoDuration: StateFlow<Long> = _videoDuration

    private val _playbackSpeed = MutableStateFlow(1.0)
    val playbackSpeed: StateFlow<Double> = _playbackSpeed

    fun setVideoPosition(positionMs: Long) { _videoPosition.value = positionMs }
    fun setVideoPlaying(playing: Boolean) { _isVideoPlaying.value = playing }
    fun setVideoDuration(durationMs: Long) { _videoDuration.value = durationMs }
    fun setPlaybackSpeed(speed: Double) { _playbackSpeed.value = speed }

    /** Reset all playback state (called on goHome). */
    fun reset() {
        _videoPosition.value = 0L
        _isVideoPlaying.value = false
        _videoDuration.value = 0L
        _playbackSpeed.value = 1.0
    }
}
