package com.gameperf.desktop.core.report.kpi

import com.gameperf.desktop.core.kpi.Band

/**
 * CSS bundle appended to the existing report `<style>` block ONLY when the
 * KPI scoring sections are rendered (i.e. `kpiInternalEnabled == true` and
 * `kpiReport != null`). Legacy reports stay byte-identical because the
 * bundle is never appended.
 *
 * All band colors are injected via [KpiBandColors.forBand] using Kotlin
 * string templates — DO NOT hardcode hex values anywhere in this file. The
 * grep-guard test (`KpiBandColorsSingleSourceTest`) walks the
 * `core/report/kpi/` package and asserts no band-hex literals outside
 * [KpiBandColors] (mirrors v4.2.13 `ToolResolver` / v4.4.0
 * `SdkSignatureCatalog` anti-duplication rule).
 *
 * Class prefix `kpi-` keeps the new rules from clashing with the existing
 * `.stat-pill` / `.card-badge` families.
 *
 * @since v4.6 (shareable-html-report Block F)
 */
private val GREEN: String = KpiBandColors.forBand(Band.GREEN)
private val AMBER: String = KpiBandColors.forBand(Band.AMBER)
private val RED: String = KpiBandColors.forBand(Band.RED)

val KPI_CSS: String = """
.kpi-band-green{color:$GREEN}
.kpi-band-amber{color:$AMBER}
.kpi-band-red{color:$RED}
.kpi-scoring{margin:24px 0;padding:20px;border-radius:12px;background:rgba(30,41,59,0.45);border:1px solid rgba(148,163,184,0.08)}
.kpi-scoring h2{font-size:1.1rem;font-weight:700;margin-bottom:12px;color:#e2e8f0}
.kpi-overall-card{display:inline-flex;align-items:baseline;gap:10px;padding:12px 20px;border-radius:12px;background:rgba(15,23,42,0.55);margin-bottom:16px}
.kpi-overall-score{font-size:2rem;font-weight:800}
.kpi-overall-band{font-size:0.85rem;font-weight:700;text-transform:uppercase;letter-spacing:0.5px}
.kpi-phases-table{width:100%;border-collapse:collapse;margin-bottom:16px}
.kpi-phases-table th,.kpi-phases-table td{padding:8px 12px;text-align:left;border-bottom:1px solid rgba(148,163,184,0.08);font-size:13px}
.kpi-phases-table th{font-size:11px;text-transform:uppercase;letter-spacing:0.5px;color:#94a3b8;font-weight:700}
.kpi-category-cards{display:flex;gap:12px;flex-wrap:wrap}
.kpi-category-card{flex:1;min-width:140px;padding:12px;border-radius:10px;background:rgba(15,23,42,0.55);border:1px solid rgba(148,163,184,0.08)}
.kpi-category-name{display:block;font-size:11px;text-transform:uppercase;letter-spacing:0.5px;color:#94a3b8;margin-bottom:4px;font-weight:700}
.kpi-category-score{display:block;font-size:1.2rem;font-weight:800}
.kpi-vitals-warn{margin:16px 0;padding:12px 16px;border-radius:10px;background:${AMBER}15;border-left:4px solid $AMBER;color:#fbbf24}
.kpi-vitals-warn h3{font-size:1rem;font-weight:700;margin-bottom:6px}
.kpi-vitals-warn ul{margin-left:20px;font-size:13px}
.kpi-comparison-table{width:100%;border-collapse:collapse;margin:16px 0;font-size:13px}
.kpi-comparison-table th,.kpi-comparison-table td{padding:8px 12px;text-align:left;border-bottom:1px solid rgba(148,163,184,0.08)}
.kpi-comparison-table th{font-size:11px;text-transform:uppercase;letter-spacing:0.5px;color:#94a3b8;font-weight:700}
.kpi-na{color:#64748b;font-style:italic}
.kpi-phase-breakdown{margin:16px 0}
.kpi-phase-breakdown-table{width:100%;border-collapse:collapse;font-size:13px}
.kpi-phase-breakdown-table th,.kpi-phase-breakdown-table td{padding:8px 12px;text-align:left;border-bottom:1px solid rgba(148,163,184,0.08);vertical-align:top}
.kpi-phase-drilldown{margin:0;padding-left:18px;font-size:12px;color:#94a3b8}
.kpi-export-buttons{display:flex;gap:8px;flex-wrap:wrap;margin:16px 0}
.kpi-export-btn{display:inline-block;padding:8px 14px;border-radius:8px;background:rgba(56,189,248,0.1);border:1px solid rgba(56,189,248,0.2);color:#38bdf8;font-size:12px;font-weight:700;text-decoration:none;transition:all 0.2s ease}
.kpi-export-btn:hover{background:rgba(56,189,248,0.2)}
.kpi-caveats{margin:24px 0;padding:16px 20px;border-radius:10px;background:rgba(15,23,42,0.55);border:1px solid rgba(148,163,184,0.08);font-size:13px;color:#94a3b8}
.kpi-caveats h2{font-size:1rem;font-weight:700;color:#e2e8f0;margin-bottom:8px}
.kpi-caveats p{margin-bottom:6px;line-height:1.5}
.kpi-card-band{display:inline-flex;align-items:center;gap:4px;padding:2px 8px;margin-left:8px;border-radius:6px;font-size:12px;font-weight:700}
.kpi-card-band.kpi-band-green{background:${GREEN}20;color:$GREEN}
.kpi-card-band.kpi-band-amber{background:${AMBER}20;color:$AMBER}
.kpi-card-band.kpi-band-red{background:${RED}20;color:$RED}
.kpi-card-band.kpi-na{opacity:0.5}
""".trimIndent()
