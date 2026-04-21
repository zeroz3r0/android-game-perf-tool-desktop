package com.gameperf.desktop.core

/**
 * Resolves Android `ro.product.model` codenames to human-readable marketing names.
 *
 * Why: `getprop ro.product.model` returns vendor codenames like `SM-S911B`,
 * `Mi 11 Lite 5G`, `Pixel 6`, or `LM-V600` that mean nothing to a QA tester
 * trying to identify a phone in a session list. We map the most common ones
 * to their marketing names ("Samsung Galaxy S23", "Xiaomi Mi 11 Lite 5G",
 * "Google Pixel 6", "LG V60 ThinQ 5G") so the device list and report headers
 * are immediately understandable.
 *
 * Strategy:
 *   1. Try exact match (e.g. `Pixel 6` → `Google Pixel 6`).
 *   2. Try prefix match (e.g. `SM-S911B`, `SM-S911U`, `SM-S911N` all map via
 *      the `SM-S911` prefix to `Samsung Galaxy S23` — same model, different
 *      regional variants, same marketing name).
 *   3. Fall back to `"<manufacturer> <codename>"` so unknown devices still
 *      show something better than the bare cryptic codename.
 *
 * Coverage focus: Samsung Galaxy S/A/Note (>50% of Android QA), Google Pixel,
 * Xiaomi/Redmi/POCO, OnePlus, Motorola, Realme, Oppo, Vivo, Huawei. Plus a
 * few legacy/budget for backward compatibility.
 *
 * v4.2.5 — initial mapping built from the QA team's recurring devices list.
 * Adding new devices: append to [codenameToMarketing] preserving the alphabetic
 * order within each brand so duplicates don't sneak in.
 */
object DeviceNameResolver {

    /**
     * Map of codename (or codename prefix) → marketing name.
     *
     * Prefix matches: `SM-S911` matches `SM-S911B`, `SM-S911U`, etc. We rely on
     * `String.startsWith` so the prefix must be the longest unique disambiguator
     * before regional/carrier suffixes.
     *
     * Order in the map matters for prefix lookup: iteration is map order, and the
     * first match wins. To avoid `SM-S91` matching `SM-S911` AND `SM-S912`, prefer
     * to enter the SHORTER prefix LATER (or keep prefixes mutually distinct).
     */
    internal val codenameToMarketing: Map<String, String> = linkedMapOf(
        // ═══ Samsung Galaxy S series (flagships) ═══
        "SM-G960" to "Samsung Galaxy S9",
        "SM-G965" to "Samsung Galaxy S9+",
        "SM-G970" to "Samsung Galaxy S10e",
        "SM-G973" to "Samsung Galaxy S10",
        "SM-G975" to "Samsung Galaxy S10+",
        "SM-G977" to "Samsung Galaxy S10 5G",
        "SM-G980" to "Samsung Galaxy S20",
        "SM-G981" to "Samsung Galaxy S20 5G",
        "SM-G985" to "Samsung Galaxy S20+",
        "SM-G986" to "Samsung Galaxy S20+ 5G",
        "SM-G988" to "Samsung Galaxy S20 Ultra",
        "SM-G991" to "Samsung Galaxy S21",
        "SM-G996" to "Samsung Galaxy S21+",
        "SM-G998" to "Samsung Galaxy S21 Ultra",
        "SM-S901" to "Samsung Galaxy S22",
        "SM-S906" to "Samsung Galaxy S22+",
        "SM-S908" to "Samsung Galaxy S22 Ultra",
        "SM-S911" to "Samsung Galaxy S23",
        "SM-S916" to "Samsung Galaxy S23+",
        "SM-S918" to "Samsung Galaxy S23 Ultra",
        "SM-S921" to "Samsung Galaxy S24",
        "SM-S926" to "Samsung Galaxy S24+",
        "SM-S928" to "Samsung Galaxy S24 Ultra",
        "SM-S931" to "Samsung Galaxy S25",
        "SM-S936" to "Samsung Galaxy S25+",
        "SM-S938" to "Samsung Galaxy S25 Ultra",

        // ═══ Samsung Galaxy Note ═══
        "SM-N960" to "Samsung Galaxy Note 9",
        "SM-N970" to "Samsung Galaxy Note 10",
        "SM-N975" to "Samsung Galaxy Note 10+",
        "SM-N980" to "Samsung Galaxy Note 20",
        "SM-N985" to "Samsung Galaxy Note 20 Ultra",

        // ═══ Samsung Galaxy A series (mid-range) ═══
        "SM-A105" to "Samsung Galaxy A10",
        "SM-A205" to "Samsung Galaxy A20",
        "SM-A305" to "Samsung Galaxy A30",
        "SM-A325" to "Samsung Galaxy A32",
        "SM-A336" to "Samsung Galaxy A33 5G",
        "SM-A346" to "Samsung Galaxy A34 5G",
        "SM-A356" to "Samsung Galaxy A35 5G",
        "SM-A405" to "Samsung Galaxy A40",
        "SM-A505" to "Samsung Galaxy A50",
        "SM-A515" to "Samsung Galaxy A51",
        "SM-A525" to "Samsung Galaxy A52",
        "SM-A526" to "Samsung Galaxy A52 5G",
        "SM-A528" to "Samsung Galaxy A52s 5G",
        "SM-A536" to "Samsung Galaxy A53 5G",
        "SM-A546" to "Samsung Galaxy A54 5G",
        "SM-A556" to "Samsung Galaxy A55 5G",
        "SM-A705" to "Samsung Galaxy A70",
        "SM-A715" to "Samsung Galaxy A71",
        "SM-A736" to "Samsung Galaxy A73 5G",

        // ═══ Samsung Galaxy Z (foldables) ═══
        "SM-F700" to "Samsung Galaxy Z Flip",
        "SM-F707" to "Samsung Galaxy Z Flip 5G",
        "SM-F711" to "Samsung Galaxy Z Flip 3",
        "SM-F721" to "Samsung Galaxy Z Flip 4",
        "SM-F731" to "Samsung Galaxy Z Flip 5",
        "SM-F741" to "Samsung Galaxy Z Flip 6",
        "SM-F900" to "Samsung Galaxy Fold",
        "SM-F907" to "Samsung Galaxy Fold 5G",
        "SM-F916" to "Samsung Galaxy Z Fold 2",
        "SM-F926" to "Samsung Galaxy Z Fold 3",
        "SM-F936" to "Samsung Galaxy Z Fold 4",
        "SM-F946" to "Samsung Galaxy Z Fold 5",
        "SM-F956" to "Samsung Galaxy Z Fold 6",

        // ═══ Google Pixel ═══
        "Pixel 5" to "Google Pixel 5",
        "Pixel 5a" to "Google Pixel 5a",
        "Pixel 6" to "Google Pixel 6",
        "Pixel 6 Pro" to "Google Pixel 6 Pro",
        "Pixel 6a" to "Google Pixel 6a",
        "Pixel 7" to "Google Pixel 7",
        "Pixel 7 Pro" to "Google Pixel 7 Pro",
        "Pixel 7a" to "Google Pixel 7a",
        "Pixel 8" to "Google Pixel 8",
        "Pixel 8 Pro" to "Google Pixel 8 Pro",
        "Pixel 8a" to "Google Pixel 8a",
        "Pixel 9" to "Google Pixel 9",
        "Pixel 9 Pro" to "Google Pixel 9 Pro",
        "Pixel 9 Pro XL" to "Google Pixel 9 Pro XL",
        "Pixel XL" to "Google Pixel XL",
        "Pixel 4" to "Google Pixel 4",
        "Pixel 4 XL" to "Google Pixel 4 XL",
        "Pixel 4a" to "Google Pixel 4a",
        "Pixel 4a (5G)" to "Google Pixel 4a 5G",

        // ═══ Xiaomi / Redmi / POCO ═══
        "Mi 9" to "Xiaomi Mi 9",
        "Mi 9T" to "Xiaomi Mi 9T",
        "Mi 10" to "Xiaomi Mi 10",
        "Mi 10 Pro" to "Xiaomi Mi 10 Pro",
        "Mi 10T" to "Xiaomi Mi 10T",
        "Mi 11" to "Xiaomi Mi 11",
        "Mi 11 Lite" to "Xiaomi Mi 11 Lite",
        "Mi 11 Lite 5G" to "Xiaomi Mi 11 Lite 5G",
        "Mi 11 Ultra" to "Xiaomi Mi 11 Ultra",
        "Mi 11i" to "Xiaomi Mi 11i",
        "2201116SG" to "Xiaomi 12",
        "2201123G" to "Xiaomi 12 Pro",
        "2210132G" to "Xiaomi 13",
        "2211133G" to "Xiaomi 13 Pro",
        "2306EPN60G" to "Xiaomi 13T",
        "2304FPN6DG" to "Xiaomi 13T Pro",
        "23116PN5BG" to "Xiaomi 14",
        "23116PN5BC" to "Xiaomi 14 Pro",
        "24031PN0DG" to "Xiaomi 14 Ultra",
        "Redmi Note 8" to "Xiaomi Redmi Note 8",
        "Redmi Note 9" to "Xiaomi Redmi Note 9",
        "Redmi Note 10" to "Xiaomi Redmi Note 10",
        "Redmi Note 11" to "Xiaomi Redmi Note 11",
        "Redmi Note 12" to "Xiaomi Redmi Note 12",
        "Redmi Note 13" to "Xiaomi Redmi Note 13",
        "M2010J19SG" to "POCO M3",
        "M2104K10AC" to "POCO X3 Pro",
        "M2102J20SG" to "POCO X3 NFC",
        "M2103K19PG" to "POCO F3",
        "21121210G" to "POCO F4",
        "22041216G" to "POCO F4 GT",
        "23049PCD8G" to "POCO F5",

        // ═══ OnePlus ═══
        "GM1900" to "OnePlus 7",
        "GM1903" to "OnePlus 7",
        "GM1913" to "OnePlus 7 Pro",
        "HD1903" to "OnePlus 7T",
        "HD1913" to "OnePlus 7T Pro",
        "IN2023" to "OnePlus 8",
        "IN2025" to "OnePlus 8",
        "IN2013" to "OnePlus 8T",
        "KB2003" to "OnePlus 8 Pro",
        "LE2113" to "OnePlus 9",
        "LE2125" to "OnePlus 9 Pro",
        "NE2213" to "OnePlus 10 Pro",
        "CPH2415" to "OnePlus 10T",
        "CPH2449" to "OnePlus 11",
        "CPH2581" to "OnePlus 12",
        "CPH2611" to "OnePlus 12R",

        // ═══ Motorola ═══
        "moto g(7)" to "Motorola Moto G7",
        "moto g(8) plus" to "Motorola Moto G8 Plus",
        "moto g power" to "Motorola Moto G Power",
        "moto g(60)" to "Motorola Moto G60",
        "moto g(100)" to "Motorola Moto G100",
        "moto g pro" to "Motorola Moto G Pro",
        "moto g52" to "Motorola Moto G52",
        "moto g82 5G" to "Motorola Moto G82 5G",
        "edge 20 pro" to "Motorola Edge 20 Pro",
        "edge 30" to "Motorola Edge 30",
        "edge 30 fusion" to "Motorola Edge 30 Fusion",
        "edge 30 ultra" to "Motorola Edge 30 Ultra",
        "edge 40" to "Motorola Edge 40",
        "edge 40 pro" to "Motorola Edge 40 Pro",
        "edge 50 pro" to "Motorola Edge 50 Pro",
        "razr 40 ultra" to "Motorola Razr 40 Ultra",

        // ═══ Realme ═══
        "RMX2202" to "Realme 8 Pro",
        "RMX3151" to "Realme 9 Pro+",
        "RMX3242" to "Realme GT Neo 3",
        "RMX3360" to "Realme GT 2 Pro",
        "RMX3686" to "Realme GT Neo 5",

        // ═══ Oppo ═══
        "CPH2173" to "Oppo Reno 5 Pro",
        "CPH2305" to "Oppo Find X3 Pro",
        "CPH2355" to "Oppo Reno 7 Pro",
        "CPH2451" to "Oppo Find X5 Pro",
        "CPH2525" to "Oppo Find X6 Pro",

        // ═══ Vivo ═══
        "V2218" to "Vivo X80",
        "V2241" to "Vivo X90",
        "V2301" to "Vivo X90 Pro+",

        // ═══ Huawei ═══
        "ANA-NX9" to "Huawei P40",
        "ANA-AN00" to "Huawei P40",
        "ELS-N04" to "Huawei P40 Pro",
        "ELS-NX9" to "Huawei P40 Pro",
        "JNY-LX1" to "Huawei P40 Lite",
        "NAM-LX9" to "Huawei P50",
        "ABR-LX9" to "Huawei P50 Pro",
        "VOG-L29" to "Huawei P30 Pro",
        "ELE-L29" to "Huawei P30",
        "DRA-LX5" to "Huawei Y5 Lite",

        // ═══ Honor ═══
        "ALI-NX1" to "Honor Magic 6 Pro",
        "PGT-N19" to "Honor Magic 5 Pro",
        "RMO-NX1" to "Honor Magic 4 Pro",

        // ═══ Asus ROG (gaming-focused, common in QA) ═══
        "ASUS_AI2202" to "Asus ROG Phone 6",
        "ASUS_AI2401" to "Asus ROG Phone 8",
        "ASUS_I003D" to "Asus ROG Phone 3",

        // ═══ Sony Xperia ═══
        "XQ-AS72" to "Sony Xperia 1 II",
        "XQ-AT52" to "Sony Xperia 5 II",
        "XQ-BC52" to "Sony Xperia 1 III",
        "XQ-BQ72" to "Sony Xperia 5 III",
        "XQ-CT54" to "Sony Xperia 1 IV",
        "XQ-DQ54" to "Sony Xperia 1 V",
        "XQ-EC54" to "Sony Xperia 1 VI",

        // ═══ Nothing ═══
        "A063" to "Nothing Phone (1)",
        "A065" to "Nothing Phone (2)",
        "A142" to "Nothing Phone (2a)",
        "A157" to "Nothing Phone (3a)",
    )

    /**
     * Resolve a device's codename + manufacturer to a human-readable name.
     *
     * @param model Raw output of `getprop ro.product.model` (e.g. `SM-S911B`).
     * @param manufacturer Raw output of `getprop ro.product.manufacturer`
     *                     (e.g. `samsung`). Used only in the fallback string when
     *                     the model is not in the lookup table.
     * @return The marketing name if known (`Samsung Galaxy S23`), or
     *         `<Manufacturer> <Model>` formatted nicely as a fallback
     *         (`Samsung SM-S911B`). Never returns an empty string.
     */
    fun resolve(model: String, manufacturer: String = ""): String {
        val trimmedModel = model.trim()
        if (trimmedModel.isEmpty()) {
            return manufacturer.trim().capitalizeFirst().ifEmpty { "Unknown device" }
        }

        // v4.3.3: normalize underscores to hyphens before lookup.
        //
        // Why: `getprop ro.product.model` returns `SM-S911B` (with hyphen),
        // but `adb devices -l` prints `model:SM_S911B` (with underscore) —
        // adb replaces hyphens with underscores in the short device listing
        // because spaces and dashes would break its space-delimited parser.
        // The codenameToMarketing table uses the canonical hyphen form (how
        // Samsung officially names the models), so `SM_S911B` from the
        // listing never matched and users saw the raw codename instead of
        // "Samsung Galaxy S23". One normalization at the entry point fixes
        // both call sites (listDevices + getDeviceInfo) in one shot.
        val normalized = trimmedModel.replace('_', '-')

        // 1. Exact match — Pixel 6, Mi 11 Lite, etc. all match here directly.
        codenameToMarketing[normalized]?.let { return it }

        // 2. Prefix match — SM-S911B / SM-S911U / SM-S911N all hit SM-S911.
        for ((prefix, name) in codenameToMarketing) {
            if (normalized.startsWith(prefix)) return name
        }

        // 3. Fallback: "<Manufacturer> <Model>" with manufacturer capitalized.
        // Avoids "samsung SM-S999X" — uses "Samsung SM-S999X".
        // Uses the normalized form so the fallback shows "Samsung SM-S999X"
        // rather than the noisier "Samsung SM_S999X".
        val mfr = manufacturer.trim().capitalizeFirst()
        return if (mfr.isEmpty() || normalized.startsWith(mfr, ignoreCase = true)) {
            normalized
        } else {
            "$mfr $normalized"
        }
    }

    /** Capitalize first letter, lowercase the rest — for `samsung` → `Samsung`. */
    private fun String.capitalizeFirst(): String {
        if (isEmpty()) return this
        return this[0].uppercaseChar() + substring(1).lowercase()
    }
}
