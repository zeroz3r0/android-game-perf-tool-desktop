package com.gameperf.desktop.viewmodel

import com.gameperf.desktop.core.GameTargets
import com.gameperf.desktop.core.GameTargetsCatalog
import com.gameperf.desktop.core.GameTargetsCatalogIO
import com.gameperf.desktop.testing.FakeAdbBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for the v5.2.0 targets editor additions to [AppViewModel]:
 *   - `targetsEditorOpen` StateFlow + `openTargetsEditor` / `closeTargetsEditor`
 *   - `saveGameTargets(catalog)` — persists via [GameTargetsCatalogIO.save],
 *     posts a snackbar message, closes the editor on success.
 *
 * Mirrors the static-file redirect pattern from `GameTargetsCatalogTest:30-41`
 * to avoid touching the real `~/GamePerf Reports/` directory.
 *
 * @since v5.2.0
 */
class AppViewModelTargetsEditorTest {

    private lateinit var tempDir: Path
    private lateinit var originalTargetsFile: File

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("appvm-targets-test-")
        originalTargetsFile = GameTargetsCatalogIO.targetsFile
        GameTargetsCatalogIO.targetsFile = tempDir.resolve("game-targets.json").toFile()
    }

    @AfterTest
    fun tearDown() {
        GameTargetsCatalogIO.targetsFile = originalTargetsFile
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun `openTargetsEditor flips state to true`() {
        val vm = AppViewModel(adb = FakeAdbBridge())
        assertFalse(vm.targetsEditorOpen.value, "editor must start closed")
        vm.openTargetsEditor()
        assertTrue(vm.targetsEditorOpen.value, "open call must flip state to true")
    }

    @Test
    fun `closeTargetsEditor flips state back to false`() {
        val vm = AppViewModel(adb = FakeAdbBridge())
        vm.openTargetsEditor()
        vm.closeTargetsEditor()
        assertFalse(vm.targetsEditorOpen.value, "close call must flip state to false")
    }

    @Test
    fun `saveGameTargets persists catalog and closes editor on success`() = runBlocking {
        val vm = AppViewModel(adb = FakeAdbBridge())
        vm.openTargetsEditor()

        val catalog = GameTargetsCatalog(
            targets = mapOf("com.test" to GameTargets(displayName = "Test", targetAvgFps = 60)),
        )
        vm.saveGameTargets(catalog)

        // Wait briefly for snackbar message to be posted (coroutine race-free in practice
        // because save is synchronous, but stay defensive).
        val message = withTimeoutOrNull(1_000L) {
            while (vm.sessionPackMessage.value == null) delay(20)
            vm.sessionPackMessage.value
        }
        assertNotNull(message, "saveGameTargets must post a snackbar message")
        assertTrue(
            message.contains("guardad", ignoreCase = true),
            "message must confirm save in castellano: was \"$message\"",
        )

        // File written with our content
        val loaded = GameTargetsCatalogIO.load()
        assertEquals(60, loaded.targets["com.test"]?.targetAvgFps)

        // Editor closed
        assertFalse(vm.targetsEditorOpen.value, "editor must close after successful save")

        vm.cleanup()
    }
}
