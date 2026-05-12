# Archive Report: dev-action-brief

**Archived on**: 2026-05-12
**Shipped in commits**: `933b46b` → `02797db` → `0ad856a` → `c12fcb0` (2026-05-12, single-day end-to-end SDD ship for 3 of 4 sprints)
**Released as**: v4.6.0 (unreleased / next minor, layered on top of v4.5.0 fpower-metric)
**Final status**: SHIPPED — Sprints 0/1/2/3 complete. Sprint 4 (Ollama BYO-LLM) explicitly DEFERRED per DAB-017.

---

## Summary

The change adds the **Dev Action Brief** — an engine-aware, rule-driven enrichment layer on top of `ConclusionEngine` that maps each fired conclusion into a structured developer-facing payload (evidence, diagnostic, code-area hints, suggested actions, confidence) and renders it as the FIRST section of the HTML report. Conclusions remain a passive "what happened" surface (DAB-016 byte-equivalence guaranteed); the brief adds the missing "what to do about it" layer that competitors stop short of.

The brief is engine-aware: a `GameEngineDetector` consumes already-captured LOADING events to identify Unity / Unreal / Cocos2d / Generic, then `CodeAreaCatalog` and `ActionStepsCatalog` surface engine-specific hints (Unity `MonoBehaviour.Update` vs Unreal `Tick` vs Cocos2d `scheduleUpdate`) drawn from official first-party docs. Spanish tuteo-formal copy throughout, mirroring the v4.4.1 thermal banner tone.

Persistence rides through `SessionResult` / `SerializableEntry` / `HistoryEntry` with defaulted fields, so pre-v4.6 `.gameperf` files decode cleanly with a null/empty brief. The report renders the section at the TOP of the body with severity badges (CRÍTICO/ALERTA/INFO), confidence badges, and a top-5 cap with "Mostrar todo" CSS toggle when the brief exceeds 5 items.

Suite grew from **951 → 1022 tests** (+71 net, all 71 devactions-specific across 8 new test classes). `./gradlew detekt` exits clean cross-project; no new threshold bumps required.

---

## Spec deltas merged into main

All 17 EARS requirements from `openspec/changes/dev-action-brief/specs/dev-actions/spec.md` (now archived at `openspec/archive/2026-05-12-dev-action-brief/specs/dev-actions/spec.md`) merged into the new main spec at **`openspec/specs/dev-actions/spec.md`**. This is the FIRST archive that touches the `dev-actions` capability — the main spec file did not exist prior to this merge.

| ID | Requirement | Status |
|----|-------------|--------|
| DAB-001 | `DevActionItem` data class with 8 fields and JSON round-trip | shipped |
| DAB-002 | Severity ranking + top-N=5 cap | shipped |
| DAB-003 | `CodeAreaCatalog` per-engine hints (8 rules × {UNITY, UNREAL, COCOS2D, GENERIC}, fall-through for GODOT/NATIVE) | shipped |
| DAB-004 | `ActionStepsCatalog` per-rule + per-engine actions with `engineSpecific` filtering | shipped |
| DAB-005 | `DevActionEngine.run` wraps `ConclusionEngine.run` (1:1 ruleId mapping) | shipped |
| DAB-006 | `GameEngineDetector` (frequency + recency tie-break) | shipped |
| DAB-007 | Persisted in `SessionResult` + `SerializableEntry` + `HistoryEntry` | shipped |
| DAB-008 | Report HTML rendering above raw metrics + first nav link | shipped |
| DAB-009 | Spanish tuteo-formal copy across all surfaces | shipped |
| DAB-010 | Backward compat default empty/null for legacy `.gameperf` | shipped |
| DAB-011 | Severity badges + CSS classes (`.dev-action-severity-*`) | shipped |
| DAB-012 | Expandable item + "Mostrar todo" / "Mostrar menos" toggle | shipped |
| DAB-013 | Evidence section with `<dl>` + segment chip + anchor link | shipped |
| DAB-014 | Optional `<details class="dev-action-logcat">` block (gated on `logcat-event-stream`) | shipped (slot reserved, body conditional) |
| DAB-015 | Detekt clean | shipped |
| DAB-016 | No breaking change to `ConclusionEngine` (byte-equivalent snapshot) | shipped |
| DAB-017 | Optional Ollama BYO-LLM narrative | **DEFERRED — Sprint 4, only on demand** |

The delta-spec used `## DAB-NNN — title` headings with bare GIVEN/WHEN/THEN paragraphs. On merge into `openspec/specs/dev-actions/spec.md`, the headings were renormalised to `### Requirement: DAB-NNN — title` and scenarios were broken into named `#### Scenario:` blocks with bullet GIVEN/WHEN/THEN, matching `openspec/specs/core/spec.md` + `openspec/specs/power-usage/spec.md` house style.

---

## Tasks closed

**3 of 4 sprints shipped** across **65 atomic tasks** flipped to `[x]`. Sprint 4 (DAB-017 / Ollama BYO-LLM) remains explicitly DEFERRED with a `[ ] DEFERRED` marker. See `tasks.md` for the per-sprint breakdown.

| Sprint | Goal | Commit | Tests added |
|-------|------|--------|-------------|
| 0 — Foundation | Data classes, `DevActionEngine` skeleton, DAB-016 snapshot lock | `933b46b` | +17 |
| 1 — Per-rule catalogs | Fill `CodeAreaCatalog` + `ActionStepsCatalog` for 8 rules × 4 engines | `02797db` | +22 |
| 2 — Engine auto-detection | `GameEngineDetector` (SDK_TO_ENGINE map + tie-break-most-recent) | `0ad856a` | +11 |
| 3 — Persistence + Report | `SessionResult`/`SerializableEntry`/`HistoryEntry` wiring + HTML section + Spanish copy + top-5 toggle | `c12fcb0` | +21 |
| 4 — DEFERRED | Optional Ollama BYO-LLM narrative | — | — |

Total: **+71 devactions-specific tests across 8 new test classes**.

---

## Test counts

| Metric | Pre-change | Post-change | Delta |
|--------|------------|-------------|-------|
| Total suite | 951 | 1022 | **+71 net** |
| devactions-specific | 0 | 71 | +71 across 8 new test classes |
| Failures | 0 | 0 | clean |
| Skipped | (pre-existing) | 10 | unchanged |

Devactions-specific test classes:
- `CodeAreaCatalogTest` + `ActionStepsCatalogTest` (Sprint 1) — completeness + content checks across 8 rules × 4 engines
- `DevActionEngineTest` (Sprint 0/1) — severity ordering, top-N, 1:1 ruleId mapping, empty input, per-rule enrichment, `engineSpecific` filtering
- `GameEngineDetectorTest` (Sprint 2) — Unity-only, Unreal-only, Cocos2d-only, frequency tie-break, recency tie-break, empty events, non-engine events
- `EvidenceBuilderTest` (Sprint 0) — per-ruleId `values` map shape
- `ConclusionEngineSnapshotTest` (Sprint 0, DAB-016 lock) — re-run at every sprint exit, byte-identical
- `ReportGeneratorDevActionBriefTest` (Sprint 3) — section position, severity CSS, top-5 toggle, doc-link rendering, backward compat, empty brief, multi-engine
- `AppViewModelDevActionBriefTest` (Sprint 3) — capture stop builds brief, no-trigger → null, engine propagation, `takeIf` empty-items dedup
- `SessionHistoryRoundTripTest` (Sprint 3) — round-trip with brief, with empty/null brief, legacy pre-Sprint3 entry

---

## Detekt status

Cross-project `./gradlew detekt` exits with code **0**. Baseline file unchanged — no new findings, no new threshold bumps required (Sprint 0..3 added < 300 LoC of new logic per sprint with named constants throughout the catalogs; magic-number-prone literals like `topN = 5`, `MAX_DOC_LINK_LENGTH`, color-band breakpoints all declared `const val` or annotated `@Suppress` with justification per DAB-015).

---

## Manifesto alignment

Per Engram observation **#337** (best-of mobile-game-perf compilation positioning), `dev-action-brief` IS the centerpiece of the project's differentiation. Competitors (GameBench, PerfDog, Embrace) stop at the data → diagnostic line; this change ships the data → diagnostic → **action** narrative end-to-end with engine-aware code-area hints drawn from first-party docs.

All 4 core principles preserved:

1. **Local-first** — no network calls in the brief generation path. Catalogs are inspectable static Kotlin code; doc-links open in the user's browser on click.
2. **No-SDK** — `GameEngineDetector` infers engine from already-captured logcat events; no in-game SDK required.
3. **No-cloud** — zero telemetry, zero data leaves the desktop. Sprint 4 LLM was DEFERRED specifically to preserve this; if it ever ships, it will be BYO-Ollama-localhost only.
4. **Open-methodology** — every catalog entry is a plain-text Kotlin literal with the `docLink` cited inline. A reviewer can `cmd-click` from a rendered hint straight to Unity's official `MonoBehaviour.Update` doc, no proprietary lookup table required.

---

## DAB-016 invariant verification

`ConclusionEngine.run(input: ConclusionInput): List<Conclusion>` byte-equivalent to pre-change behavior. `ConclusionEngineSnapshotTest` (added Sprint 0 task S0-T2) captures the current output for a representative fixture and re-runs at every sprint exit. All 4 sprint commits passed the snapshot test without modification:

- Sprint 0 (`933b46b`): snapshot established
- Sprint 1 (`02797db`): snapshot still byte-identical (catalog work has zero call-site impact on `ConclusionEngine`)
- Sprint 2 (`0ad856a`): snapshot still byte-identical (`GameEngineDetector` is read-only against events)
- Sprint 3 (`c12fcb0`): snapshot still byte-identical (`DevActionEngine` call-site is colocated next to but downstream of `ConclusionEngine.run`)

The `<section id="sec-conclusions">` HTML render is also byte-equivalent — `ReportGenerator.generate(...)` adds the new `<section id="sec-dev-action-brief">` ABOVE the existing flow without touching the conclusions section. Verified by existing report rendering tests continuing to pass through all sprints.

---

## Backward compat verified

A legacy v4.5.0-pre-Sprint3 `.gameperf` JSON file (without `devActionBrief` field) decodes cleanly via `SessionHistory.load()` on the post-this-change build. The implementation chose `devActionBrief: DevActionBrief? = null` (nullable default) rather than `DevActionBrief()` (empty default) — see inline decision #1 below — and the report renderer treats both `null` and `items.isEmpty()` as "no findings, omit section".

Asserts in `SessionHistoryRoundTripTest.legacy_pre_sprint3_entry_decodes_with_null_brief`:

- `entry.devActionBrief == null` — TRUE (legacy field absent)
- `Json { ignoreUnknownKeys = true }` parses without throwing — TRUE
- `ReportGenerator.generate(entry)` runs without throwing — TRUE
- The rendered HTML does NOT contain `<section id="sec-dev-action-brief"` — TRUE (omitted on null/empty)

Mirrors the v4.4.1 `ThermalSnapshot` widening pattern AND the fpower-metric Batch 4 backward-compat pattern (commit `9169824`). Confirms DAB-007 + DAB-010.

---

## Inline decisions taken during apply

Captured from Engram apply-progress observation **#345** for traceability:

| # | Decision | Rationale |
|---|----------|-----------|
| 1 | `takeIf { items.isNotEmpty() } → null` at the `AppViewModel` call site (rather than always storing a `DevActionBrief(emptyList())`) | Distinguishes "no findings — engine ran cleanly" (legitimate empty brief computed at capture stop) from "brief never computed" (`null` = pre-Sprint3 legacy entry). The renderer treats both as "omit section", but the persisted distinction is preserved for future analytics. Cleaner than storing an empty list and losing the "never computed" signal. |
| 2 | Spanish copy with severity icons + uppercase severity labels | Severity badges use `⛔ CRÍTICO`, `⚠️ ALERTA`, `ℹ️ INFO` for visual hierarchy. Confidence badges use minimal copy (`"confianza alta/media/baja"`) deliberately understated so they don't compete with severity for the dev's attention. Mirrors v4.4.1 thermal banner tone. |
| 3 | Top-5 cap enforced via CSS (`display:none on :nth-child(n+6)`), NOT JavaScript | Keeps static HTML reports shareable without JS execution. The toggle uses a single inline `onclick` with `classList.toggle`, no external deps, no framework. Reports load and render correctly even with JS disabled — just shows all items unwrapped. |
| 4 | Section placement: TOP of body, BEFORE existing summary cards (per design ADR-7) | Devs see `dev-action-brief` FIRST when opening a report — it's the "what to do" surface, the highest-leverage view. Visual style (border-left indigo + light gray bg) distinguishes it from the data sections below without competing for color hierarchy with the existing summary cards. |

---

## Risks open for v4.6 review

1. **Spanish copy quality** — All catalog strings, action descriptions, and report labels are project-generated Spanish tuteo-formal. Sprint 1 task S1-T12 ran a regex linter (`\busted\b|\bvosotros\b` returns empty) but did NOT have a native-speaker review pass. Recommended for v4.6 release prep: route the catalog `.kt` files through a Spanish copywriter or native-speaker engineer for tone consistency, especially the `whyHere` and `description` longer-form strings.
2. **Doc-link rot** — 30+ first-party URLs hard-coded in `CodeAreaCatalog.kt` + `ActionStepsCatalog.kt` (Unity Manual, Unreal Engine docs, Cocos2d-x API ref, Android Studio profilers, RenderDoc). These will rot over time as those vendors restructure their docs. Recommended: add a CI job that HEAD-checks every `docLink` weekly and files an issue on any 404. Out of scope for this change; tracked for v4.7.
3. **Engine-specific accuracy is research-grade** — Sprint 1 catalog entries were built from official docs + community best-practice articles, but no real-game-validation pass was done. A Unity dev triaging a "stable-low-fps-low-cpu" hint on their game may find the suggestion misses their actual bottleneck. Mitigation: confidence badges (HIGH/MEDIUM/LOW per `ConfidenceLookup`) communicate uncertainty; "Mostrar todo" toggle lets devs see all hints; logcat-line evidence (DAB-014) will eventually anchor hints to concrete observed behavior. For v4.6: collect dev feedback on hint accuracy via a flag in the report ("¿Este consejo te ayudó?").
4. **Sprint 4 LLM scope-creep prevention** — DAB-017 is explicitly DEFERRED with a separate change name reserved (`dev-action-brief-ollama-narrative`). Any v4.6 PR that adds `narrative: String?` to `DevActionItem` / `DevActionBrief`, or imports an Ollama HTTP client, or reads `OLLAMA_HOST` / `LLM_API_KEY` from environment is a scope violation. The spec scenario (DAB-017 scenario "no Ollama client or LLM key in v1 code paths") provides a machine-checkable invariant. Recommended: add a detekt custom rule that fails on `import.*ollama` or `narrative:` field additions to brief models until the separate change ships.

---

## Engram observation IDs (traceability)

| Artifact | Engram ID | Topic key |
|----------|-----------|-----------|
| Exploration | `#338` | `sdd/dev-action-brief/explore` |
| Proposal | `#339` | `sdd/dev-action-brief/proposal` |
| Spec (delta) | `#340` | `sdd/dev-action-brief/spec` |
| Design | `#341` | `sdd/dev-action-brief/design` |
| Tasks | `#342` | `sdd/dev-action-brief/tasks` |
| Apply progress | `#345` | `sdd/dev-action-brief/apply-progress` (upserted across Sprints 0-3) |
| Verify report | (rolled into Sprint 3 exit) | — |
| Archive report | (this doc) | `sdd/dev-action-brief/archive-report` |

---

## Files touched at archive time (paperwork only — no `src/` changes)

- `openspec/specs/dev-actions/spec.md` — NEW file, full DAB-001..DAB-017 main spec (DAB-017 marked DEFERRED)
- `openspec/archive/2026-05-12-dev-action-brief/tasks.md` — all `[ ]` → `[x]` for Sprints 0-3, Sprint 4 retains explicit `[ ] DEFERRED` marker, footer appended
- `openspec/archive/2026-05-12-dev-action-brief/archive-report.md` — this file
- Folder move: `openspec/changes/dev-action-brief/` → `openspec/archive/2026-05-12-dev-action-brief/`

No source code files (`src/`, `core/`, `app/`) touched at archive time. All `src/` changes shipped in the 4 implementation commits listed at the top of this report.

---

## Next: roadmap impact

The new `dev-actions` capability is now referenceable by downstream SDD changes. Three in-flight changes in `openspec/changes/` can now consume it:

| Downstream change | How it benefits |
|-------------------|-----------------|
| `logcat-event-stream` | Populates `DevActionItem.relatedLogcatLines` (DAB-014 reserved slot). The HTML render path is already conditional and ready — `logcat-event-stream` just needs to emit `LogcatLineRef` instances during capture and attach them to the matching brief items by timestamp range. |
| `event-segmentation-coverage` | Can surface a per-event-window dev-action mini-brief (e.g. "during ad show, FPS drops 40% — here's what to check") by passing event-windowed `ConclusionInput` slices through `DevActionEngine.run`. Spec can reference DAB-005 1:1 mapping guarantee. |
| `gpu-usage-percent` | Can add a new rule (`gpu-bound`) to `RuleRegistry` and provide its catalog entries; the brief automatically picks it up via DAB-003/004 since both catalogs are keyed by `ruleId`. Zero changes required to `DevActionEngine` for new rules. |

The `dev-action-brief-ollama-narrative` future change (DAB-017 reserved) will consume `DevActionBrief.items` as LLM context and add a `narrative: String?` field — explicitly out of scope here.

---

## SDD cycle complete

The change has been explored, proposed, spec'd, designed, broken down, applied across 4 sprints (3 shipped, 1 deferred), verified via full-suite + detekt gate at every sprint exit, and archived. The `dev-actions` capability is now first-class in `openspec/specs/`. Ready for the next change.
