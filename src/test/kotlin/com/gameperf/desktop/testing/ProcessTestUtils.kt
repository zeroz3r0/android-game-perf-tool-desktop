package com.gameperf.desktop.testing

/**
 * Cross-platform helpers for spawning short-lived processes in unit tests.
 *
 * Tests that validate process-lifecycle logic (validateScreenRecordProcess,
 * startSegmentWithRetry, etc.) previously used Unix-only commands (`sh -c`,
 * `sleep`, `cat`). These helpers detect the OS at runtime and pick the
 * appropriate command so the same test runs correctly on macOS, Linux,
 * and Windows.
 */
object ProcessTestUtils {

    val isWindows: Boolean =
        System.getProperty("os.name")?.lowercase()?.contains("win") == true

    /**
     * Spawn a process that writes [stderr] to its output stream and immediately
     * exits with [exitCode]. With [ProcessBuilder.redirectErrorStream] = true
     * the output lands on the inputStream regardless of the originating stream.
     */
    fun spawnFastFail(stderr: String = "encoder rejected", exitCode: Int = 1): Process {
        val pb = if (isWindows)
            // cmd /c: echo writes to stdout (merged via redirectErrorStream); exit /b N sets exit code
            ProcessBuilder("cmd", "/c", "echo $stderr & exit /b $exitCode")
        else
            ProcessBuilder("sh", "-c", "echo '$stderr' >&2; exit $exitCode")
        return pb.redirectErrorStream(true).start()
    }

    /**
     * Spawn a process that stays alive for approximately [seconds] seconds.
     * Suitable for "process survives warmup" assertions.
     */
    fun spawnSleeping(seconds: Int = 2): Process {
        val pb = if (isWindows)
            // ping loops: N+1 pings ≈ N seconds (1 per second by default)
            ProcessBuilder("cmd", "/c", "ping -n ${seconds + 1} 127.0.0.1 > NUL")
        else
            ProcessBuilder("sh", "-c", "sleep $seconds")
        return pb.redirectErrorStream(true).start()
    }

    /** Returns the command list for a fast-failing process (for [FakeAdbBridge.queueFastFail]). */
    fun fastFailCommand(stderr: String = "encoder rejected"): List<String> =
        if (isWindows) listOf("cmd", "/c", "echo $stderr & exit /b 1")
        else listOf("sh", "-c", "echo '$stderr' >&2; echo '$stderr'; exit 1")

    /** Returns the command list for a sleeping process (for [FakeAdbBridge.queueAlive]). */
    fun sleepCommand(seconds: Int = 2): List<String> =
        if (isWindows) listOf("cmd", "/c", "ping -n ${seconds + 1} 127.0.0.1 > NUL")
        else listOf("sh", "-c", "sleep $seconds")

    /**
     * Spawn a process that exits immediately with exit code 0.
     * Useful as a stand-in for a real process handle in dispatch tests.
     */
    fun spawnImmediate(): Process {
        val pb = if (isWindows)
            ProcessBuilder("cmd", "/c", "exit /b 0")
        else
            ProcessBuilder("sh", "-c", "true")
        return pb.redirectErrorStream(true).start()
    }
}
