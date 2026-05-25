package com.gameperf.desktop.core.sharing

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [DataUrlBuilder].
 *
 * No mocks, real `File` instances in a manually-managed temp dir — same
 * pattern as `ToolResolverTest` and `ReportSharerTest` (project uses
 * `kotlin.test` framework, not JUnit Jupiter).
 *
 * @since v5.0.0
 */
class DataUrlBuilderTest {

    private lateinit var tempDir: Path

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("data-url-builder-test-")
    }

    @AfterTest
    fun tearDown() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `happy path returns prefixed base64 for HTML under cap`() {
        val payload = "<html><body><h1>Reporte</h1></body></html>".toByteArray()
        val file = newFile("report.html", payload)

        val result = DataUrlBuilder.build(file)

        assertNotNull(result, "happy path must not return null")
        assertTrue(
            result!!.startsWith("data:text/html;base64,"),
            "expected MIME prefix, got: ${result.take(40)}…",
        )
        val encoded = result.removePrefix("data:text/html;base64,")
        assertEquals(
            payload.toList(),
            Base64.getDecoder().decode(encoded).toList(),
            "decoded payload must equal original bytes",
        )
    }

    @Test
    fun `boundary exactly 5 MB returns non-null (cap is exclusive)`() {
        val file = newFile("boundary.html", ByteArray(DataUrlBuilder.MAX_SIZE_BYTES.toInt()))

        val result = DataUrlBuilder.build(file)

        assertNotNull(result, "exactly 5 MB must NOT be rejected (cap is exclusive)")
        assertTrue(result!!.startsWith("data:text/html;base64,"))
    }

    @Test
    fun `over cap returns null`() {
        val file = newFile("toobig.html", ByteArray(DataUrlBuilder.MAX_SIZE_BYTES.toInt() + 1))

        val result = DataUrlBuilder.build(file)

        assertNull(result, "5 MB + 1 byte must return null")
    }

    @Test
    fun `MIME prefix is exactly data text html base64`() {
        val file = newFile("any.html", "<p>x</p>".toByteArray())

        val result = DataUrlBuilder.build(file)

        assertNotNull(result)
        assertTrue(
            result!!.startsWith("data:text/html;base64,"),
            "MIME prefix must be exactly data:text/html;base64, — got: ${result.take(40)}",
        )
    }

    @Test
    fun `deterministic same file twice`() {
        val file = newFile("deterministic.html", "<body>same</body>".toByteArray())

        val first = DataUrlBuilder.build(file)
        val second = DataUrlBuilder.build(file)

        assertEquals(first, second, "two invocations on the same file must return identical strings")
    }

    @Test
    fun `non existent file returns null`() {
        val ghost = tempDir.resolve("does-not-exist.html").toFile()

        val result = DataUrlBuilder.build(ghost)

        assertNull(result, "missing file must return null without crashing")
    }

    @Test
    fun `empty file returns prefix only`() {
        val file = newFile("empty.html", ByteArray(0))

        val result = DataUrlBuilder.build(file)

        assertEquals(
            "data:text/html;base64,",
            result,
            "empty file must produce a valid empty data URL (prefix with empty payload)",
        )
    }

    private fun newFile(name: String, bytes: ByteArray): File {
        val file = tempDir.resolve(name).toFile()
        file.writeBytes(bytes)
        return file
    }
}
