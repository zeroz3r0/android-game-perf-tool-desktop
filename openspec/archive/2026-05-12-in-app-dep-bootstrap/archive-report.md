# Archive Report: in-app-dep-bootstrap

**Archived on**: 2026-05-12
**Shipped in commit**: `f462ac6` (2026-04-28, co-bundled with fps-after-ad fix)
**Released as**: part of v4.4.x line
**Final status**: SHIPPED — all 12 tasks completed retroactively. Two design open questions captured to backlog.

---

## Status note: retroactive archive

This change was implemented and merged to `main` 14 days before the formal archive paperwork was completed. The original `tasks.md` left 9 of 12 boxes unchecked because the implementation was co-bundled with an unrelated bug fix (fps-after-ad) and the SDD checklist was never closed out at merge time. Code has been in production / `main` since 2026-04-28 with no follow-up bugs, no rollback, and `./gradlew check` clean.

Archive paperwork (this document, spec merge, tasks closure, backlog capture) was done at 2026-05-12 to bring the SDD record back in sync with the codebase.

---

## What shipped

The change eliminated the manual `adb` / `ffmpeg` install dependency (scoop / chocolatey / Homebrew) by:

1. **Bundled `adb`** at build time → `<installDir>/tools/` is searched as step 0 by `ToolResolver.find`.
2. **On-demand `ffmpeg`** download to `UserToolsDir` when the user first attempts video recording — surfaced via a Compose banner styled after the existing `UpdateBanner`.
3. **OS-specific user tools directory** (`%LOCALAPPDATA%\GamePerf\tools\`, `~/Library/Application Support/GamePerf/tools/`, `~/.local/share/GamePerf/tools/`).
4. **Pure-test coverage** for `ToolResolver`, `DependencyBootstrap`, `ToolInstaller` — Fake* pattern, no mocks (per project rule).
5. **Best-effort SHA256 verification** — ffmpeg from gyan.dev publishes hashes; platform-tools verified by minimum size.
6. **Graceful proxy / disk-quota fallback** — "Abrir en navegador" CTA opens the official download URL.

---

## Tasks completed

**12 / 12 tasks shipped** (100%).

| Phase | Status |
|-------|--------|
| Phase 1 (Infrastructure: UserToolsDir, Downloader, ToolInstaller) | 3/3 — originally checked at proposal time |
| Phase 2 (Core: DependencyBootstrap, ToolResolver step 0, AppViewModel wiring) | 3/3 — closed retroactively |
| Phase 3 (Integration: HomeScreen banner, download CTA, browser fallback) | 3/3 — closed retroactively |
| Phase 4 (Testing: ToolResolverTest, DependencyBootstrapTest, ToolInstallerTest) | 3/3 — closed retroactively |
| Phase 5 (Verification: `./gradlew check`, manual smoke) | 3/3 — verified on `main` |

See `tasks.md` for per-task notes.

---

## Spec coverage

**5 / 5 EARS requirements covered** in code. They have been merged into `openspec/specs/core/spec.md` as a new section `§7 Dependency Bootstrap` with stable IDs `DEP-001 … DEP-005`:

| ID | Requirement | Status |
|----|-------------|--------|
| DEP-001 | App detects missing adb at startup | ✅ shipped |
| DEP-002 | App detects missing ffmpeg when recording | ✅ shipped |
| DEP-003 | Download succeeds with progress feedback | ✅ shipped |
| DEP-004 | Download fails gracefully (proxy, disk space) | ✅ shipped |
| DEP-005 | Manual fallback for download failures | ✅ shipped |

The delta heading in the change folder's `specs/core/spec.md` used informal names (e.g. "Requirement: App detects missing adb at startup"). On merge into the main spec, these were renumbered with the stable `DEP-NNN` prefix to match the rest of `core/spec.md`'s convention (`EVT-`, `FLT-`, `CON-`, `MAN-`, `REP-`, `IOS-`).

---

## Deferred items captured to backlog

Two open questions from `design.md` were carried forward instead of being resolved before archive. See `openspec/backlog/in-app-dep-bootstrap-followups.md`.

| ID | Question | Why deferred |
|----|----------|--------------|
| Q1 | Bundled adb: copy to `UserToolsDir` or dual-lookup in `ToolResolver`? | Trade-off is disk usage / maintainability, not correctness. Spec passes either way. |
| Q2 | Auto-update of bundled tools (adb version drift)? | Explicitly out-of-scope per proposal. Manual bump works; drift is ~1/year. |

Neither blocks the v4.4.x release contract.

---

## Engram observation IDs (traceability)

| Artifact | Engram ID | Topic key |
|----------|-----------|-----------|
| Exploration | `#120` | `sdd/in-app-dep-bootstrap/explore` |
| Proposal | `#123` | `sdd/in-app-dep-bootstrap/proposal` |
| Spec (delta) | `#124` | `sdd/in-app-dep-bootstrap/spec` |
| Design | `#125` | `sdd/in-app-dep-bootstrap/design` |
| Tasks | `#126` | `sdd/in-app-dep-bootstrap/tasks` |
| Apply progress | `#127` | `sdd/in-app-dep-bootstrap/apply-progress` |
| Verify report | (none) | — verification was implicit via clean `./gradlew check` on `main` |
| Archive report | (this doc) | `sdd/in-app-dep-bootstrap/archive-report` |

---

## Files touched at archive time (paperwork only — no `src/` changes)

- `openspec/changes/in-app-dep-bootstrap/tasks.md` — closed remaining 9 boxes with retroactive-ship note
- `openspec/specs/core/spec.md` — appended `§7 Dependency Bootstrap` with DEP-001 … DEP-005
- `openspec/backlog/in-app-dep-bootstrap-followups.md` — new file capturing Q1 + Q2
- `openspec/changes/in-app-dep-bootstrap/archive-report.md` — this file
- Folder move: `openspec/changes/in-app-dep-bootstrap/` → `openspec/archive/2026-05-12-in-app-dep-bootstrap/`

---

## Verification at archive time

- Code has been on `main` since 2026-04-28 (14 days).
- `./gradlew check` reported clean by prior session: detekt 0 issues, ~815 tests passing.
- No follow-up bug reports, no rollback PRs, no hotfixes touching the bootstrap surface.

---

## Notes for next change

The next sprint kick-off should be **Sprint 1 GPU usage %** — the next entry on the roadmap. This archive closes out the bootstrap workstream and clears the desk for new spec work.

---

## SDD cycle complete

The change has been proposed, spec'd, designed, broken down, applied, verified (retroactively, post-merge), and archived. Ready for the next change.
