package com.gameperf.desktop.report

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright

/**
 * Thread-safe lazy singleton that owns the Playwright + Chromium Browser lifecycle.
 *
 * First `ensureReady()` call launches Playwright + a headless Chromium and may take
 * several seconds the very first time (Chromium download is triggered by the driver
 * if the binary is not cached under `~/.cache/ms-playwright/`). Subsequent calls are
 * effectively instantaneous and all exported PDFs share the same Browser instance.
 *
 * `shutdown()` is idempotent and intended to be called from `AppViewModel.cleanup()`
 * right before the process exits. It nulls both fields so a later `ensureReady()` can
 * relaunch if needed.
 */
object PlaywrightManager {
    @Volatile private var playwright: Playwright? = null
    @Volatile private var browser: Browser? = null
    private val lock = Any()

    /** Cheap check for the UI so it can show/hide the "Preparando motor PDF..." dialog. */
    val isReady: Boolean get() = browser != null

    /**
     * Returns the shared Browser, launching Playwright + Chromium on first call.
     * Uses classic double-checked locking with @Volatile fields for visibility.
     */
    fun ensureReady(): Browser {
        browser?.let { return it }
        return synchronized(lock) {
            browser ?: run {
                val pw = Playwright.create()
                val b = pw.chromium().launch(
                    BrowserType.LaunchOptions()
                        .setHeadless(true)
                        .setChromiumSandbox(false)
                )
                playwright = pw
                browser = b
                b
            }
        }
    }

    /** Convenience: open a new Page on the shared Browser. Caller is responsible for closing it. */
    fun newPage(): Page = ensureReady().newPage()

    /** Idempotent shutdown. Safe to call twice. Never throws. */
    fun shutdown() {
        synchronized(lock) {
            try { browser?.close() } catch (_: Throwable) {}
            try { playwright?.close() } catch (_: Throwable) {}
            browser = null
            playwright = null
        }
    }
}
