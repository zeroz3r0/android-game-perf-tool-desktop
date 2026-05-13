# Spec: kpi-scoring (NEW capability)

## Purpose
Pure-functional KPI scoring layer. Single catalog → linear 0-100 scores → phase/category/session aggregates → comparison deltas with color bands. v1 internal-only behind `kpi.scoring.internal` flag.

## ADDED Requirements

### Requirement: KPI Catalog Single Source of Truth
System MUST expose a single immutable catalog (`KpiCatalog.ALL`) defining ≥23 KPIs. Each entry MUST carry: `id`, `phase` set, `category` (Smoothness | Resource | Thermal | Stability | Responsiveness), `unit`, `direction` (HigherIsBetter | LowerIsBetter), and a per-tier threshold triple (TOP/MID/LOW) with `target` and `floor`.

#### Scenario: Catalog enumerates all KPIs from doc §5.1
- GIVEN `KpiCatalog.ALL`
- WHEN size and ids inspected
- THEN size ≥ 23 AND ids cover FPS avg/p1/p0.1/stability, frame time p99, slow/frozen frames, CPU avg/max (normalized), RAM avg/max, temp avg/max, throttling, cold/warm/hot start, TTID, ANR count, crash count, slow-session-rate, PerfDog Jank, Big Jank, FPower

#### Scenario: No KPI metadata defined outside catalog
- GIVEN repository source under `core/`
- WHEN grepped for `target =`, `floor =`, threshold literals matching KPI ids
- THEN zero hits outside `core/kpi/KpiCatalog.kt` (architectural anti-duplication, mirrors `SdkSignatureCatalog`)

### Requirement: Linear Scoring Function
System MUST provide `scoreLinear(value: Double, target: Double, floor: Double, direction: Direction): Int` returning an integer 0-100 by linear interpolation, clamped, respecting direction. Result MUST be deterministic and pure (no I/O, no time, no randomness).

#### Scenario: Higher-is-better at or above target → 100
- GIVEN target=60, floor=20, direction=HigherIsBetter
- WHEN value=60 (or 75)
- THEN result == 100

#### Scenario: Higher-is-better at or below floor → 0
- GIVEN target=60, floor=20, direction=HigherIsBetter
- WHEN value=20 (or 5)
- THEN result == 0

#### Scenario: Lower-is-better at or below target → 100
- GIVEN target=16.0, floor=84.0, direction=LowerIsBetter (frame time p99)
- WHEN value=16.0 (or 10.0)
- THEN result == 100

#### Scenario: Lower-is-better at or above floor → 0
- GIVEN target=16.0, floor=84.0, direction=LowerIsBetter
- WHEN value=84.0 (or 200.0)
- THEN result == 0

#### Scenario: Midpoint produces 50
- GIVEN target=60, floor=20, direction=HigherIsBetter
- WHEN value=40
- THEN result == 50

#### Scenario: NaN or non-finite returns 0
- GIVEN any thresholds
- WHEN value is NaN, +∞ or −∞
- THEN result == 0

### Requirement: Phase Aggregation
System MUST aggregate KPI scores per phase using weighted average from `PhaseWeights.DEFAULT` (table keyed by `Phase × KpiId → weight`). KPIs whose data is missing/unavailable MUST be excluded from the phase's weight denominator (renormalize on present KPIs only).

#### Scenario: Weighted average across present KPIs
- GIVEN phase Gameplay with weights {FPS_AVG:0.3, FPS_P1:0.2, TEMP_MAX:0.1} and scores {100, 50, 0}
- WHEN `PhaseScoreAggregator.aggregate(phase=Gameplay, scores)` invoked
- THEN result == round((100·0.3 + 50·0.2 + 0·0.1) / 0.6) == 67

#### Scenario: Missing data excluded from denominator
- GIVEN phase Gameplay weights as above but TEMP_MAX score absent (thermalAvailable=false)
- WHEN aggregator invoked
- THEN result == round((100·0.3 + 50·0.2)/0.5) == 80 AND TEMP_MAX NOT counted

### Requirement: Category Aggregation
System MUST produce per-category sub-scores (Smoothness, Resource, Thermal, Stability, Responsiveness) as weighted average of category-tagged KPI scores across all present phases.

#### Scenario: Smoothness category aggregates FPS+frame-time KPIs only
- GIVEN scored session where Smoothness KPIs avg=80 and Resource KPIs avg=40
- WHEN `CategoryScoreAggregator.aggregate(SMOOTHNESS, session)`
- THEN result == 80 AND Resource KPIs NOT included

#### Scenario: Category with no present KPIs returns null
- GIVEN session lacking any Thermal KPI data
- WHEN aggregator invoked for THERMAL
- THEN result is null (not 0 — distinguishes "no data" from "bad")

### Requirement: Session Aggregation
System MUST aggregate phase scores into one overall session score (0-100) using weighted average where phase weights come from `PhaseWeights.DEFAULT.phaseWeights`. Phases absent from the session MUST be excluded from the denominator.

#### Scenario: All phases present
- GIVEN phase scores {STARTUP:70, LOADING:60, GAMEPLAY:90} with phase weights {0.1, 0.2, 0.7}
- WHEN `SessionScoreAggregator.aggregate(phaseScores)`
- THEN result == round(70·0.1 + 60·0.2 + 90·0.7) == 82

#### Scenario: Game has no cinematics phase
- GIVEN session containing only STARTUP and GAMEPLAY
- WHEN aggregator invoked
- THEN CINEMATIC weight NOT counted; denominator = 0.1 + 0.7 = 0.8

### Requirement: Comparison Engine with Color Bands
System MUST produce, for each KPI / phase / category / session, a `Comparison` carrying `value`, `target`, `delta`, `score`, and a `Band` (GREEN | AMBER | RED). Band rule (per doc §3.1 trichotomy): `score ≥ 80 → GREEN`, `50 ≤ score < 80 → AMBER`, `score < 50 → RED`.

#### Scenario: Score 95 → GREEN band
- GIVEN comparison input value below target for a LowerIsBetter KPI
- WHEN engine classifies
- THEN band == GREEN AND delta < 0 (better than target)

#### Scenario: Score 30 → RED band with positive delta
- GIVEN HigherIsBetter KPI, value=25, target=60, floor=20
- WHEN engine classifies
- THEN score < 50 AND band == RED AND delta == 25-60 == -35

### Requirement: Feature Flag Gating
Calculation MUST always run when input data available. UI / HTML report exposure of scores MUST be gated behind `Settings.kpiScoringInternalEnabled` (default `false`). v1 MUST NOT add any UI wiring referencing scores.

#### Scenario: Flag OFF — calc runs, UI hidden
- GIVEN `kpiScoringInternalEnabled = false` AND a captured SessionResult
- WHEN session scored via pipeline
- THEN scoring functions return populated results AND no UI/HTML node references them

#### Scenario: Flag ON — same calc, exposure unlocked
- GIVEN `kpiScoringInternalEnabled = true`
- WHEN downstream consumers query
- THEN consumers MAY surface results (out of scope for v1; verified by absence of leakage when false)

### Requirement: Device Tier Resolution
System MUST resolve KPI thresholds by device tier (`DeviceTier.TOP | MID | LOW`) using `DeviceTierCatalog.classify(deviceInfo): DeviceTier`. Same raw KPI value MUST produce different scores when tiers differ.

#### Scenario: Same FPS value scores differently per tier
- GIVEN FPS_AVG with TOP {target=60, floor=30}, MID {target=45, floor=24}, LOW {target=30, floor=20}
- WHEN value=30 evaluated for TOP, MID, LOW
- THEN TOP score == 0 AND MID score ≈ 29 AND LOW score == 100

#### Scenario: Unknown device → MID default
- GIVEN deviceInfo unrecognized by catalog
- WHEN `classify()` invoked
- THEN returned tier == MID (safe middle-ground default; logged for catalog expansion)

## Notes
- All requirements testable as pure-Kotlin unit tests under `src/test/kotlin/.../core/kpi/`.
- Synthetic SessionResult fixtures live at `src/test/resources/kpi-fixtures/`.
- No I/O, no time, no randomness in any scoring code (TDD invariant).
