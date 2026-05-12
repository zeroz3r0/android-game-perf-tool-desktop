# Backlog: in-app-dep-bootstrap follow-ups

**Source:** `openspec/changes/in-app-dep-bootstrap/design.md` (archived 2026-05-12 → `openspec/archive/2026-05-12-in-app-dep-bootstrap/`)
**Status:** Captured at archive time. These were the two open questions left unresolved when the change shipped retroactively in commit `f462ac6`. They are NOT blockers — the shipped behaviour is internally consistent — but they remain candidates for a future focused PR.

---

## Q1 — Bundled adb: copy to `UserToolsDir` on first run, or dual-lookup?

**Original phrasing (design.md):**
> Should bundled `adb` be copied to `UserToolsDir` on first run, or should `ToolResolver` check both `<installDir>/tools/` and `UserToolsDir`? — The proposal says "copy/extract to UserToolsDir" for bundled adb, but checking both is simpler. **Decision needed**: copy vs. dual lookup.

**Why it's a future enhancement (not a blocker):**
- The shipped implementation works correctly with whichever strategy `ToolResolver` currently uses; the spec scenarios in `core/spec.md §7 DEP-001/DEP-003` pass either way.
- The trade-off is **disk usage + maintainability** (one copy vs. lookup logic), not **correctness**.

**Suggested resolution path (when picked up):**
1. Measure: in production, how many users actually have bundled adb? If ~100%, dual-lookup is dead code.
2. Decide explicitly and document the decision in `core/ToolResolver.kt` KDoc.
3. Add a spec scenario under DEP-001 if behaviour changes.

---

## Q2 — Auto-update of bundled tools (adb version drift)

**Original phrasing (design.md):**
> Should the app check for updates to bundled tools (adb version)? — Currently out of scope per proposal, but could be a future enhancement.

**Why it's a future enhancement (not a blocker):**
- Out of scope per `proposal.md` ("Actualización automática de tools bundled — manual bump por ahora").
- Bundled `adb` version is pinned at CI build time. Drift only matters when Google releases a platform-tools update with a protocol change — historically rare (~1/year).
- A bug-pattern workaround exists: user can override by placing a newer adb in `UserToolsDir` (step 0 lookup).

**Suggested resolution path (when picked up):**
1. Spec a new tiny change: `bundled-tool-version-check` (separate from this archive).
2. Reuse `AutoUpdater`'s version-poll pattern against a small manifest JSON hosted alongside CI artifacts.
3. New spec section under `core/spec.md` (likely §8 or merged into §7).
4. Consider doing this together with a more general "tool catalogue" refactor — multiple tools (ffmpeg, scrcpy?) might benefit from the same mechanism.

---

## Cross-cutting note

Neither Q1 nor Q2 affects the v4.4.x release contract. The detection mode, banner, download, fallback, and SHA256 verification all work as specified. These items are **deferred deliberately** and can land in any future minor release without breaking backward compatibility.
