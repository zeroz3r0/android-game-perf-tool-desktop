package com.gameperf.desktop.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [GameTargetsCatalog] and [GameTargetsCatalogIO].
 *
 * Mirrors the manual temp-dir pattern from `DataUrlBuilderTest:24-36`
 * (no JUnit Jupiter, no mocks — `kotlin.test` framework). The static
 * [GameTargetsCatalogIO.targetsFile] is swapped to a per-test temp file
 * in `setUp` and restored in `tearDown` so production paths under
 * `~/GamePerf Reports/` are never touched.
 *
 * @since v5.1.0
 */
class GameTargetsCatalogTest {

    private lateinit var tempDir: Path
    private lateinit var originalFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("game-targets-test-")
        originalFile = GameTargetsCatalogIO.targetsFile
        GameTargetsCatalogIO.targetsFile = tempDir.resolve("game-targets.json").toFile()
    }

    @AfterTest
    fun tearDown() {
        GameTargetsCatalogIO.targetsFile = originalFile
        tempDir.toFile().deleteRecursively()
    }

    // ════════════════ R1 — persistence + forward-compat ════════════════

    @Test
    fun `load returns empty catalog when file does not exist`() {
        val catalog = GameTargetsCatalogIO.load()
        assertTrue(catalog.targets.isEmpty(), "missing file must produce empty catalog")
    }

    @Test
    fun `load returns populated catalog when JSON is valid`() {
        GameTargetsCatalogIO.targetsFile.writeText(
            """
            {
              "version": 1,
              "targets": {
                "com.foo": { "targetAvgFps": 60 },
                "com.bar": { "displayName": "Bar", "maxPeakRamMb": 500 }
              }
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val catalog = GameTargetsCatalogIO.load()

        assertEquals(2, catalog.targets.size)
        assertEquals(60, catalog.getTargetsFor("com.foo")?.targetAvgFps)
        assertEquals("Bar", catalog.getTargetsFor("com.bar")?.displayName)
        assertEquals(500L, catalog.getTargetsFor("com.bar")?.maxPeakRamMb)
    }

    @Test
    fun `load returns empty catalog when JSON is malformed without throwing`() {
        GameTargetsCatalogIO.targetsFile.writeText(
            "{ \"targets\": { \"com.foo\": { \"targetAvgFps\":",
            Charsets.UTF_8,
        )

        // Must NOT throw.
        val catalog = GameTargetsCatalogIO.load()

        assertTrue(catalog.targets.isEmpty(), "malformed JSON must produce empty catalog (no throw)")
    }

    @Test
    fun `load ignores unknown fields for forward compatibility`() {
        GameTargetsCatalogIO.targetsFile.writeText(
            """
            {
              "version": 1,
              "targets": {
                "com.foo": {
                  "targetAvgFps": 30,
                  "futureField": "ignore me",
                  "anotherFuture": 42
                }
              },
              "topLevelFuture": "also ignored"
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val catalog = GameTargetsCatalogIO.load()

        assertEquals(30, catalog.getTargetsFor("com.foo")?.targetAvgFps)
    }

    @Test
    fun `save creates file with pretty-printed JSON`() {
        val catalog = GameTargetsCatalog(
            targets = mapOf("com.foo" to GameTargets(displayName = "Foo", targetAvgFps = 30)),
        )

        GameTargetsCatalogIO.save(catalog)

        assertTrue(GameTargetsCatalogIO.targetsFile.exists(), "save must create the file")
        val text = GameTargetsCatalogIO.targetsFile.readText(Charsets.UTF_8)
        assertTrue(text.contains("\n"), "pretty-print JSON must contain newlines")
        assertTrue(text.contains("com.foo"), "saved text must contain the package key")
    }

    @Test
    fun `getTargetsFor returns null for unknown package`() {
        val catalog = GameTargetsCatalog(
            targets = mapOf("com.known" to GameTargets(targetAvgFps = 30)),
        )

        assertNull(catalog.getTargetsFor("com.unknown"))
    }

    @Test
    fun `save then load round trip preserves content`() {
        val original = GameTargetsCatalog(
            targets = mapOf(
                "com.foo" to GameTargets(
                    displayName = "Foo",
                    targetAvgFps = 60,
                    maxTempSkinC = 41.5,
                    maxPeakRamMb = 1234L,
                    notes = "round-trip with special chars: áéíóú ñ",
                ),
            ),
        )

        GameTargetsCatalogIO.save(original)
        val reloaded = GameTargetsCatalogIO.load()

        assertEquals(original, reloaded, "round-trip must preserve all values byte-equivalent at value level")
    }

    // ════════════════ R2 — bootstrap idempotency ════════════════

    @Test
    fun `ensureBootstrapped creates file with com vivastudios pieceout template when missing`() {
        assertTrue(!GameTargetsCatalogIO.targetsFile.exists(), "precondition: file must not exist")

        GameTargetsCatalogIO.ensureBootstrapped()

        assertTrue(GameTargetsCatalogIO.targetsFile.exists(), "bootstrap must create the file")
        val catalog = GameTargetsCatalogIO.load()
        val pieceOut = catalog.getTargetsFor("com.vivastudios.pieceout")
        assertNotNull(pieceOut, "bootstrap must seed com.vivastudios.pieceout entry")
        assertNotNull(pieceOut!!.displayName, "bootstrap entry must have non-null displayName")
        assertNotNull(pieceOut.targetAvgFps, "bootstrap entry must have non-null targetAvgFps")
    }

    @Test
    fun `ensureBootstrapped is idempotent and does not overwrite existing file`() {
        val customCatalog = GameTargetsCatalog(
            targets = mapOf("com.user.game" to GameTargets(displayName = "User Custom", targetAvgFps = 120)),
        )
        GameTargetsCatalogIO.save(customCatalog)
        val originalBytes = GameTargetsCatalogIO.targetsFile.readBytes()

        GameTargetsCatalogIO.ensureBootstrapped()

        val newBytes = GameTargetsCatalogIO.targetsFile.readBytes()
        assertTrue(originalBytes.contentEquals(newBytes), "bootstrap must NOT touch a user-edited file")
    }

    @Test
    fun `ensureBootstrapped fails silently when parent dir cannot be created`() {
        // Cross-platform invalid parent: create a regular file and try to use
        // it as the parent directory. mkdirs() will refuse because the path
        // exists as a file, NOT a directory. Works identically on Windows,
        // Linux and macOS CI runners (the earlier Z:/... assumption was
        // Windows-only and CI runs on ubuntu-latest).
        val blockerFile = tempDir.resolve("blocker").toFile()
        blockerFile.writeBytes(byteArrayOf(0x42))
        GameTargetsCatalogIO.targetsFile =
            File(blockerFile, "game-targets.json") // blocker is a file, not a dir

        // Must NOT throw.
        GameTargetsCatalogIO.ensureBootstrapped()

        // No file created either — only assertion is "no exception".
        assertTrue(
            !GameTargetsCatalogIO.targetsFile.exists(),
            "no file should be created when parent path is a regular file",
        )
    }
}
