package com.gameperf.desktop.core

/**
 * Classification of an Android thermal_zone "type" name into the four
 * user-meaningful buckets the desktop app cares about.
 *
 * - [Skin]: the user-facing case/skin estimator. This is what the user "feels"
 *   on the back of the phone. Throttling thresholds quoted in UI copy (~42°C)
 *   apply to THIS category.
 * - [DieCpu]: silicon temperature of the CPU. Routinely 80-95°C under load on
 *   modern flagships and NOT a problem unless > 95°C.
 * - [DieGpu]: silicon temperature of the GPU.
 * - [Battery]: battery cell temperature.
 *
 * Anything else (modem, charger, PMIC, USB-C, unknown vendor names) classifies
 * to `null` and is intentionally ignored — see exploration `sdd/grading-thermal-realism/explore`.
 */
enum class ThermalCategory { Skin, DieCpu, DieGpu, Battery }

/**
 * Pure classifier from a thermal_zone "type" string to a [ThermalCategory].
 *
 * The classifier is built from PUBLIC AOSP / Snapdragon / Samsung / Pixel
 * kernel patterns. We deliberately use exact-match sets and word-boundary
 * regex (NOT free substring matching) so charger IC zones like
 * `chg-skin-therm` are NOT mis-classified as user "skin" temperature.
 *
 * Lookup order:
 *  1. Exact ignore list (charger / PMIC / modem / USB-C / WiFi).
 *  2. Exact skin allow-list.
 *  3. Exact die-CPU / die-GPU / battery allow-lists.
 *  4. Pattern allow-lists (cpuss-*, cpu*-thermal, gpuss-*, etc.).
 *  5. Default: `null` (ignored).
 *
 * Step 1 runs FIRST so a literal `chg-skin-therm` never falls through to step 2.
 */
object ThermalZoneClassifier {

    // ── v4.4.1 vendor catalog patterns (sdd/temperature-not-shown) ───────
    // Extracted as named constants per design ADR-1, ADR-2 (REFACTOR step).

    /** Pixel XL pre-Treble Qualcomm: `tsens_tz_sensor0` ... `tsens_tz_sensor99`. */
    internal val TSENS_TZ_PATTERN: Regex = Regex("^tsens_tz_sensor\\d+$")

    /** Samsung Galaxy Tab A8 Unisoc T618: `cluster0-thermal` / `cluster1_thermal` etc. */
    internal val CLUSTER_THERMAL_PATTERN: Regex = Regex("^cluster\\d+[-_]thermal$")

    /**
     * v4.4.1 -- Stage-2 prefix guards. Charger IC and PMIC zone names often
     * contain `cpu` / `skin` substrings (e.g. `chg-controller-cpu-tsens`,
     * `pmic-thermal-cpu`) yet they MUST NOT pollute DieCpu / Skin buckets
     * with non-thermal-state values. Belt-and-suspenders rejection BEFORE
     * the keyword catch-all. See design ADR-2.
     */
    internal val STAGE2_REJECT_PREFIXES: List<String> = listOf("chg-", "pmic-")

    // ── Step 1 — ignore (literal matches + prefixes) ─────────────────────

    private val IGNORE_LITERAL: Set<String> = setOf(
        "chg-skin-therm",
        "chg-therm",
        "chg-bat-therm",
        "wp_therm",
        "wp-therm",
        "usbc-therm",
        "usb-therm",
    )

    private val IGNORE_PATTERNS: List<Regex> = listOf(
        // Modem / WiFi / power-mgmt IC die — never user-relevant.
        Regex("^modem.*$"),
        Regex("^mdm.*$"),
        Regex("^wlan.*$"),
        Regex("^pm\\d+[ab]?_.*$"),  // pm8350b_tz, pm8550_tz, pm8350a_*, etc.
    )

    // ── Step 2 — skin allow-list ─────────────────────────────────────────

    private val SKIN_LITERAL: Set<String> = setOf(
        // bare `skin` and `quiet` are emitted by dumpsys thermalservice fallback
        // on some Qualcomm Android 10+ devices.
        "skin",
        "quiet",
        "quiet-therm",
        "quiet-therm-monitor",
        "skin-therm",
        "skin-therm-usr",
        "xo-therm",
        "xo-therm-usr",
        "virtual-skin",
        "virtual-skin-therm",
        "sm-skin-therm",
        "case-therm",
        "back-therm",
        "back-therm-usr",
        "disp-therm",
    )

    /** Pixel-family `pa-therm`, `pa-therm0`, `pa-therm1`. Treated as skin proxy. */
    private val SKIN_PATTERN: Regex = Regex("^pa-therm\\d*$")

    // ── Step 3 — die / battery allow-lists ───────────────────────────────

    private val DIE_CPU_LITERAL: Set<String> = setOf(
        // dumpsys thermalservice exact names (the captureTemperature fallback
        // path emits these for some Qualcomm SoCs).
        "big",
        "little",
        "mid",
        // Cluster aliases without the `_cluster_` infix.
        "gold_thermal",
        "silver_thermal",
        "prime_thermal",
    )

    private val DIE_CPU_PATTERN: List<Regex> = listOf(
        Regex("^cpuss-\\d+(-usr)?$"),                   // cpuss-0, cpuss-1-usr, cpuss-3-usr
        Regex("^cpu\\d+(-thermal)?$"),                  // cpu0-thermal ... cpu7-thermal AND bare cpu0..cpu7 (dumpsys thermalservice fallback)
        Regex("^cpu-\\d+-(step|fast)$"),                // Tensor cpu-1-step, cpu-1-fast
        Regex("^(gold|silver|prime)_cluster_thermal$"), // gold_cluster_thermal, etc.
        Regex("^aoss\\d+-usr$"),                        // aoss0-usr, aoss1-usr
        // v4.4.1 vendor catalog additions (sdd/temperature-not-shown):
        TSENS_TZ_PATTERN,                               // Pixel XL pre-Treble Qualcomm: tsens_tz_sensor[0-9]+
        CLUSTER_THERMAL_PATTERN,                        // Tab A8 Unisoc T618: cluster[0-9]+(-|_)thermal
    )

    private val DIE_GPU_LITERAL: Set<String> = setOf(
        // dumpsys thermalservice fallback exact name.
        "g3d",
        "mali",
        "mali-thermal",
        // v4.4.1 -- Tab A8 Unisoc Mali alias.
        "ump_thermal",
    )

    private val DIE_GPU_PATTERN: List<Regex> = listOf(
        Regex("^gpuss-\\d+(-usr)?$"),  // gpuss-0-usr, gpuss-1-usr
        Regex("^gpu\\d+-thermal$"),    // gpu0-thermal
        Regex("^kgsl_3d\\d*_thermal$"), // kgsl_3d_thermal, kgsl_3d0_thermal
    )

    private val BATTERY_PATTERN: Regex = Regex("^battery\\d*$")

    /** Classify a single `thermal_zone/type` string. Case insensitive. */
    fun classify(typeName: String): ThermalCategory? {
        val name = typeName.lowercase().trim()
        if (name.isEmpty()) return null

        // 1. Ignore (charger / PMIC / modem / USB-C / WiFi) — runs FIRST so
        //    `chg-skin-therm` does not leak into the skin allow-list.
        if (name in IGNORE_LITERAL || IGNORE_PATTERNS.any { it.matches(name) }) return null

        // 2. Skin
        if (name in SKIN_LITERAL || SKIN_PATTERN.matches(name)) return ThermalCategory.Skin

        // 3. Die-CPU
        if (name in DIE_CPU_LITERAL || DIE_CPU_PATTERN.any { it.matches(name) }) return ThermalCategory.DieCpu

        // 4. Die-GPU
        if (name in DIE_GPU_LITERAL || DIE_GPU_PATTERN.any { it.matches(name) }) return ThermalCategory.DieGpu

        // 5. Battery
        if (BATTERY_PATTERN.matches(name)) return ThermalCategory.Battery

        return null
    }

    /**
     * v4.4.1 -- Stage-2 keyword catch-all heuristic, called by
     * [com.gameperf.desktop.core.AdbThermalParser] ONLY when [classify]
     * returns `null`. Lets the desktop tool produce a usable DieCpu / DieGpu
     * / Skin reading on vendors not yet in the strict allow-list, instead of
     * silently dropping the zone and showing the user a misleading 0 C.
     *
     * Order of operations (see design ADR-2):
     *  1. Lowercase + trim the input.
     *  2. Reject anything starting with [STAGE2_REJECT_PREFIXES] (`chg-`,
     *     `pmic-`). These are charger / PMIC chips whose zone names contain
     *     `cpu` / `skin` substrings but are NOT user-relevant temperatures.
     *  3. Match keyword family:
     *     - contains `cpu` OR `core` -> DieCpu
     *     - contains `gpu` OR `mali` OR `kgsl` -> DieGpu
     *     - contains `skin` OR `case` OR `back` -> Skin
     *  4. No match -> `null` (caller leaves the snapshot unpopulated).
     *
     * Plausibility gating (value sanity) lives at the parser layer
     * ([AdbThermalParser.parseThermalZonesOutput]) per design ADR-3, NOT
     * here. This method is naming-only.
     *
     * Public so [AdbThermalParser] can call it directly without a friend
     * class workaround. The strict allow-list classifier
     * ([classify]) remains the primary entry point.
     */
    fun classifyHeuristic(typeName: String): ThermalCategory? {
        val name = typeName.lowercase().trim()
        if (name.isEmpty()) return null
        // Belt-and-suspenders: prefix guard BEFORE keyword match.
        if (STAGE2_REJECT_PREFIXES.any { name.startsWith(it) }) return null
        return when {
            "cpu" in name || "core" in name -> ThermalCategory.DieCpu
            "gpu" in name || "mali" in name || "kgsl" in name -> ThermalCategory.DieGpu
            "skin" in name || "case" in name || "back" in name -> ThermalCategory.Skin
            else -> null
        }
    }
}
