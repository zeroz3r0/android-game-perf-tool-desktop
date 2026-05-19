package com.gameperf.desktop.core.sharing

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [TempShUploader].
 *
 * Uses an in-process [HttpServer] (`com.sun.net.httpserver`, part of the JDK)
 * to simulate the temp.sh upload endpoint without touching the network.
 * Same approach as `IosBridgeTest` for the iOS sidecar — exercise the real
 * HTTP path against a fake server we control byte-for-byte, no mocks.
 *
 * Each test starts a fresh server on an ephemeral port so the suite can run
 * in parallel without port collisions.
 */
class TempShUploaderTest {

    private lateinit var server: HttpServer
    private lateinit var endpoint: String
    private lateinit var tmpDir: File
    private lateinit var reportFile: File

    @BeforeTest
    fun setUp() {
        // Port 0 = let the OS pick an ephemeral port.
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        // Default tests register their handler before reading endpoint().
        tmpDir = Files.createTempDirectory("gameperf-tempsh-test-").toFile()
        reportFile = File(tmpDir, "report.html")
        reportFile.writeText("<html><body>tiny report</body></html>", Charsets.UTF_8)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
        tmpDir.deleteRecursively()
    }

    private fun startWith(handler: HttpHandler): String {
        server.createContext("/upload", handler)
        server.start()
        val port = server.address.port
        return "http://127.0.0.1:$port/upload"
    }

    // ═══════ pure: buildMultipartBody ═══════

    @Test
    fun `buildMultipartBody contains boundary, filename, content-type and file bytes`() {
        val payload = "<html>hello</html>".toByteArray(StandardCharsets.UTF_8)
        reportFile.writeBytes(payload)
        val body = TempShUploader.buildMultipartBody(reportFile, "text/html")

        val s = String(body, StandardCharsets.UTF_8)
        assertTrue(s.contains("Content-Disposition: form-data; name=\"file\"; filename=\"report.html\""))
        assertTrue(s.contains("Content-Type: text/html"))
        assertTrue(s.contains("<html>hello</html>"))
        // Body ends with the closing boundary delimiter `--BOUNDARY--`.
        assertTrue(s.trimEnd().endsWith("--"), "multipart body must end with closing boundary --")
    }

    @Test
    fun `buildMultipartBody preserves a normal filename verbatim`() {
        // The expected production case: a filename like
        // `informe_com_pkg_Pixel_2026-05-19.html` flows through verbatim.
        // The sanitizer only kicks in on illegal characters (CR/LF/quotes)
        // which the host OS won't normally allow in a real path, so we only
        // assert the happy path here. The sanitization regex itself is
        // private guard logic — covered by code review, not by a test that
        // would need OS-specific filename gymnastics to set up.
        val body = TempShUploader.buildMultipartBody(reportFile, "text/html")
        val s = String(body, StandardCharsets.UTF_8)
        val headerLine = s.lineSequence().firstOrNull { it.contains("Content-Disposition") } ?: ""
        assertTrue(
            headerLine.contains("filename=\"${reportFile.name}\""),
            "expected filename verbatim in Content-Disposition, got: $headerLine",
        )
    }

    // ═══════ pure: extractUrl ═══════

    @Test
    fun `extractUrl picks the first http link in the response body`() {
        val body = "https://temp.sh/aBcD1/report.html\n"
        assertEquals("https://temp.sh/aBcD1/report.html", TempShUploader.extractUrl(body))
    }

    @Test
    fun `extractUrl trims whitespace and tolerates trailing newlines`() {
        val body = "   https://temp.sh/AAA/x.html   \n\n"
        assertEquals("https://temp.sh/AAA/x.html", TempShUploader.extractUrl(body))
    }

    @Test
    fun `extractUrl skips banner lines until a real URL appears`() {
        val body = "Welcome to temp.sh\nYour file is here:\nhttps://temp.sh/Z9/x.html\n"
        assertEquals("https://temp.sh/Z9/x.html", TempShUploader.extractUrl(body))
    }

    @Test
    fun `extractUrl returns null when body has no URL`() {
        assertNull(TempShUploader.extractUrl("Sorry, service down right now."))
    }

    @Test
    fun `extractUrl rejects bare domain without a path`() {
        // A line like "https://temp.sh" without any path is NOT a valid
        // shareable URL (it'd land the recipient on the homepage). We
        // require at least one slash after the host so the result is
        // useful as a share link.
        assertNull(TempShUploader.extractUrl("https://temp.sh"))
    }

    // ═══════ upload: success path ═══════

    @Test
    fun `upload posts file and returns the URL on a 200 response`() {
        var capturedBodyHadFile = false
        val ep = startWith { ex ->
            // Drain the body and look for the multipart marker so we know the
            // client actually wrote the bytes (not just a 0-length POST).
            val received = ex.requestBody.readBytes()
            capturedBodyHadFile = String(received, StandardCharsets.UTF_8).contains("filename=\"report.html\"")
            val responseUrl = "https://temp.sh/FAKE1/report.html"
            val bytes = responseUrl.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }

        val result = TempShUploader.upload(reportFile, endpoint = ep)

        val success = result as? ReportShareResult.TempLinkShareResult
        assertNotNull(success, "expected TempLinkShareResult, got $result")
        assertEquals("https://temp.sh/FAKE1/report.html", success.url)
        assertTrue(success.retentionDescription.contains("día", ignoreCase = true), "retention copy must mention days")
        assertTrue(capturedBodyHadFile, "server must have received the multipart body with the filename header")
    }

    @Test
    fun `upload returns TempLink when server response has banner lines before the URL`() {
        val ep = startWith { ex ->
            val body = "Banner line 1\nBanner line 2\nhttps://temp.sh/ZZZ/x.html\n"
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val result = TempShUploader.upload(reportFile, endpoint = ep) as ReportShareResult.TempLinkShareResult
        assertEquals("https://temp.sh/ZZZ/x.html", result.url)
    }

    // ═══════ upload: failure paths ═══════

    @Test
    fun `upload returns UPLOAD_HTTP_ERROR on 5xx`() {
        val ep = startWith { ex ->
            ex.sendResponseHeaders(503, -1)
            ex.close()
        }
        val result = TempShUploader.upload(reportFile, endpoint = ep) as ReportShareResult.Failure
        assertEquals(ReportShareResult.FailureReason.UPLOAD_HTTP_ERROR, result.reason)
        assertTrue(result.userMessage.contains("503"), "user message should include the HTTP code for clarity")
    }

    @Test
    fun `upload returns UPLOAD_BAD_RESPONSE when body has no URL`() {
        val ep = startWith { ex ->
            val bytes = "no url here".toByteArray(StandardCharsets.UTF_8)
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val result = TempShUploader.upload(reportFile, endpoint = ep) as ReportShareResult.Failure
        assertEquals(ReportShareResult.FailureReason.UPLOAD_BAD_RESPONSE, result.reason)
    }

    @Test
    fun `upload returns REPORT_NOT_FOUND when the file does not exist`() {
        val ghost = File(tmpDir, "ghost.html")
        // Use a non-routable endpoint — won't be reached because the
        // file-not-found check short-circuits before the socket opens.
        val result = TempShUploader.upload(ghost, endpoint = "http://127.0.0.1:1/never-called") as ReportShareResult.Failure
        assertEquals(ReportShareResult.FailureReason.REPORT_NOT_FOUND, result.reason)
    }

    @Test
    fun `upload returns UPLOAD_FILE_TOO_LARGE when file exceeds the cap`() {
        // Create a sparse-ish payload bigger than the cap. We write a small
        // file and then directly assert the boundary using a separate
        // [largeFile] sized just above the cap via [RandomAccessFile.setLength].
        val large = File(tmpDir, "big.bin")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(TempShUploader.MAX_UPLOAD_BYTES + 1) }
        val result = TempShUploader.upload(large, endpoint = "http://127.0.0.1:1/never-called") as ReportShareResult.Failure
        assertEquals(ReportShareResult.FailureReason.UPLOAD_FILE_TOO_LARGE, result.reason)
    }

    @Test
    fun `upload returns UPLOAD_NETWORK_ERROR when the endpoint is unreachable`() {
        // Port 1 on loopback is virtually guaranteed to refuse the connection
        // (privileged port, no service listening, immediate ECONNREFUSED).
        val result = TempShUploader.upload(
            reportFile,
            endpoint = "http://127.0.0.1:1/upload",
        ) as ReportShareResult.Failure
        assertEquals(ReportShareResult.FailureReason.UPLOAD_NETWORK_ERROR, result.reason)
    }
}
