package com.gameperf.desktop.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * v3.2.0 — Unit tests for the pure parsers introduced for the wireless ADB
 * feature ([parseMdnsServicesOutput], [parsePairStderr], [parseConnectStderr],
 * [parseAdbVersion]).
 *
 * These tests are intentionally 100% offline: no ProcessBuilder, no adb, no
 * file IO. They lock in the parsing contracts that the [AdbBridge] wireless
 * methods rely on, following the same precedent set by
 * `SurfaceFlingerListParserTest` — pure string-in / value-out, dead fast.
 *
 * Coverage (18 tests total):
 *  - 6 parseMdnsServicesOutput:
 *      - pairing only
 *      - connect only
 *      - mixed pairing + connect (same instance)
 *      - empty input
 *      - header-only input
 *      - malformed lines are skipped (not null'd into the result)
 *  - 6 parsePairStderr:
 *      - failed-to-authenticate → INVALID_CODE
 *      - connection-refused → CONNECTION_REFUSED
 *      - no-route-to-host → CONNECTION_REFUSED
 *      - timedOut=true → TIMEOUT (takes precedence over stderr content)
 *      - blank stderr → TIMEOUT
 *      - unknown stderr → UNKNOWN
 *  - 4 parseConnectStderr:
 *      - no-route-to-host → NO_ROUTE
 *      - connection-refused → REFUSED
 *      - network-unreachable → NO_ROUTE
 *      - unknown → UNKNOWN
 *  - 2 parseAdbVersion:
 *      - canonical "Android Debug Bridge version 1.0.41\nVersion 34.0.5-..." output
 *      - malformed output (no version line) → null
 */
class AdbBridgeMdnsParserTest {

    // ===== parseMdnsServicesOutput =====

    @Test
    fun `parseMdnsServicesOutput parses pairing service correctly`() {
        val output = """
            List of discovered mdns services
            adb-32211JEHN-AAAAAA	_adb-tls-pairing._tcp.	192.168.1.42:37123
        """.trimIndent()

        val result = parseMdnsServicesOutput(output)

        assertEquals(1, result.size)
        val service = result[0]
        assertEquals("adb-32211JEHN-AAAAAA", service.instance)
        assertEquals(MdnsServiceType.PAIRING, service.serviceType)
        assertEquals("192.168.1.42", service.ip)
        assertEquals(37123, service.port)
    }

    @Test
    fun `parseMdnsServicesOutput parses connect service correctly`() {
        val output = """
            List of discovered mdns services
            adb-32211JEHN-BBBBBB	_adb-tls-connect._tcp.	192.168.1.42:38145
        """.trimIndent()

        val result = parseMdnsServicesOutput(output)

        assertEquals(1, result.size)
        val service = result[0]
        assertEquals("adb-32211JEHN-BBBBBB", service.instance)
        assertEquals(MdnsServiceType.CONNECT, service.serviceType)
        assertEquals("192.168.1.42", service.ip)
        assertEquals(38145, service.port)
    }

    @Test
    fun `parseMdnsServicesOutput parses mixed pairing and connect services for the same instance`() {
        val output = """
            List of discovered mdns services
            adb-32211JEHN-XXXXXX	_adb-tls-pairing._tcp.	192.168.1.42:37123
            adb-32211JEHN-XXXXXX	_adb-tls-connect._tcp.	192.168.1.42:38145
        """.trimIndent()

        val result = parseMdnsServicesOutput(output)

        assertEquals(2, result.size)
        val pairing = result.first { it.serviceType == MdnsServiceType.PAIRING }
        val connect = result.first { it.serviceType == MdnsServiceType.CONNECT }
        assertEquals("adb-32211JEHN-XXXXXX", pairing.instance)
        assertEquals("adb-32211JEHN-XXXXXX", connect.instance)
        assertEquals(37123, pairing.port)
        assertEquals(38145, connect.port)
    }

    @Test
    fun `parseMdnsServicesOutput returns empty list for empty input`() {
        assertTrue(parseMdnsServicesOutput("").isEmpty())
        assertTrue(parseMdnsServicesOutput("   \n  \n").isEmpty())
    }

    @Test
    fun `parseMdnsServicesOutput returns empty list when only the header is present`() {
        val output = "List of discovered mdns services\n"
        val result = parseMdnsServicesOutput(output)
        assertTrue(result.isEmpty(), "header-only output should produce no services, got $result")
    }

    @Test
    fun `parseMdnsServicesOutput skips malformed lines without failing`() {
        val output = """
            List of discovered mdns services
            garbage-line-with-too-few-fields
            adb-valid-XXXXXX	_adb-tls-pairing._tcp.	192.168.1.42:37123
            bad-ip-line	_adb-tls-connect._tcp.	not.an.ip:999
            adb-unknown-type	_adb-tls-unknown._tcp.	10.0.0.1:5555
            adb-bad-port	_adb-tls-connect._tcp.	10.0.0.2:99999
        """.trimIndent()

        val result = parseMdnsServicesOutput(output)

        // Only the one valid PAIRING line survives.
        assertEquals(1, result.size)
        assertEquals("adb-valid-XXXXXX", result[0].instance)
        assertEquals(MdnsServiceType.PAIRING, result[0].serviceType)
        assertEquals(37123, result[0].port)
    }

    // ===== parsePairStderr =====

    @Test
    fun `parsePairStderr maps failed-to-authenticate to INVALID_CODE`() {
        val stderr = "adb: failed to authenticate to 192.168.1.42:37123"
        assertEquals(PairFailureReason.INVALID_CODE, parsePairStderr(stderr, timedOut = false))
    }

    @Test
    fun `parsePairStderr maps connection-refused to CONNECTION_REFUSED`() {
        val stderr = "ssh: connect to host 192.168.1.42 port 37123: Connection refused"
        assertEquals(PairFailureReason.CONNECTION_REFUSED, parsePairStderr(stderr, timedOut = false))
    }

    @Test
    fun `parsePairStderr maps no-route-to-host to CONNECTION_REFUSED`() {
        val stderr = "adb: No route to host"
        assertEquals(PairFailureReason.CONNECTION_REFUSED, parsePairStderr(stderr, timedOut = false))
    }

    @Test
    fun `parsePairStderr returns TIMEOUT when the timedOut flag is true regardless of stderr content`() {
        // Even if stderr would normally classify as INVALID_CODE, the timedOut
        // flag takes precedence because the caller killed the process before
        // it could finish writing its error.
        assertEquals(
            PairFailureReason.TIMEOUT,
            parsePairStderr("failed to authenticate", timedOut = true),
        )
    }

    @Test
    fun `parsePairStderr maps blank stderr to TIMEOUT`() {
        assertEquals(PairFailureReason.TIMEOUT, parsePairStderr("", timedOut = false))
        assertEquals(PairFailureReason.TIMEOUT, parsePairStderr("   \n  ", timedOut = false))
    }

    @Test
    fun `parsePairStderr maps unknown stderr to UNKNOWN`() {
        val stderr = "adb: some unexpected error message that nobody knows about"
        assertEquals(PairFailureReason.UNKNOWN, parsePairStderr(stderr, timedOut = false))
    }

    // ===== parseConnectStderr =====

    @Test
    fun `parseConnectStderr maps no-route-to-host to NO_ROUTE`() {
        val stderr = "failed to connect to '192.168.1.42:38145': No route to host"
        assertEquals(ConnectFailureReason.NO_ROUTE, parseConnectStderr(stderr, timedOut = false))
    }

    @Test
    fun `parseConnectStderr maps connection-refused to REFUSED`() {
        val stderr = "failed to connect to '192.168.1.42:38145': Connection refused"
        assertEquals(ConnectFailureReason.REFUSED, parseConnectStderr(stderr, timedOut = false))
    }

    @Test
    fun `parseConnectStderr maps network-is-unreachable to NO_ROUTE`() {
        val stderr = "adb: Network is unreachable"
        assertEquals(ConnectFailureReason.NO_ROUTE, parseConnectStderr(stderr, timedOut = false))
    }

    @Test
    fun `parseConnectStderr maps unknown stderr to UNKNOWN`() {
        val stderr = "adb: completely unexpected message"
        assertEquals(ConnectFailureReason.UNKNOWN, parseConnectStderr(stderr, timedOut = false))
    }

    // ===== parseAdbVersion =====

    @Test
    fun `parseAdbVersion parses canonical adb --version output`() {
        // Real output from `adb --version` on adb 34.0.5.
        val output = """
            Android Debug Bridge version 1.0.41
            Version 34.0.5-11794757
            Installed as /usr/local/bin/adb
            Running on Darwin 23.4.0 (x86_64)
        """.trimIndent()

        val result = parseAdbVersion(output)
        assertNotNull(result)
        assertEquals(34, result.major)
        assertEquals(0, result.minor)
        assertEquals(5, result.patch)
    }

    @Test
    fun `parseAdbVersion returns null for malformed output`() {
        assertNull(parseAdbVersion(""))
        assertNull(parseAdbVersion("adb: command not found"))
        assertNull(parseAdbVersion("Version abc.def.ghi"))
    }
}
