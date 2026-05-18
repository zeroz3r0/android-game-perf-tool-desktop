package com.gameperf.desktop.core.kpi

/**
 * ╔════════════════════════════════════════════════════════════════════════╗
 * ║  SINGLE SOURCE OF TRUTH for frame-time budget constants.               ║
 * ║                                                                        ║
 * ║  These are MATHEMATICAL LAWS (`1000 / targetFps`), NOT Vitals          ║
 * ║  thresholds and NOT KpiCatalog entries. Render code (charts, KPI       ║
 * ║  cards, banners) MUST reference these constants — never the bare       ║
 * ║  literal `16.6` / `33.3` / `8.3`.                                      ║
 * ║                                                                        ║
 * ║  Mirrors the anti-duplication discipline of `ToolResolver` (v4.2.13)   ║
 * ║  and `SdkSignatureCatalog.ALL` (v4.4.0). See CLAUDE.md.                ║
 * ║                                                                        ║
 * ║  Architectural test `FrameBudgetsSingleSourceTest` greps               ║
 * ║  `src/main/kotlin/com/gameperf/desktop/` for these literals outside    ║
 * ║  this file (plus the `KpiCatalog` allow-list for `FRAME_TIME_P99`      ║
 * ║  Vitals thresholds that happen to share the value `16.6 / 33.3`).      ║
 * ╚════════════════════════════════════════════════════════════════════════╝
 *
 * Anchors: `docs/competitive-analysis-and-kpis.md` §3.2 RAIL (16 ms budget at
 * 60 fps) and §3.4 frame-budget table.
 *
 * @since v4.7 (html-report-rag-bands — RAG-005)
 */
internal object FrameBudgets {

    /** 60 fps frame budget in milliseconds (1000 / 60 = 16.66…, canonicalized to 16.6). */
    const val FPS_60_MS: Double = 16.6

    /** 30 fps frame budget in milliseconds (1000 / 30 = 33.33…, canonicalized to 33.3). */
    const val FPS_30_MS: Double = 33.3

    /** 120 fps frame budget in milliseconds (1000 / 120 = 8.33…, canonicalized to 8.3). */
    const val FPS_120_MS: Double = 8.3

    /**
     * Exact frame budget in milliseconds for an arbitrary target fps. Use for
     * non-canonical refresh rates (24 / 90 / 144 Hz) where the canonical
     * constants would not apply. Returns `1000.0 / targetFps`.
     */
    fun lineFor(targetFps: Int): Double = 1000.0 / targetFps
}
