package com.gameperf.desktop.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [EnginePhaseCatalog] — the single source of truth for
 * engine-phase keyword rules.
 *
 * Covers spec requirements:
 *  - AUTO-002 single-source-of-truth invariants (Unity + Unreal rule lists
 *    populated, no duplicate priorities, priority order DESC).
 *  - AUTO-005 bilingual ES+EN coverage per phase type.
 *  - AUTO-006 priority schema BOSS=100 > COMBAT=90 > CUTSCENE=80 >
 *    TUTORIAL=70 > MENU=60.
 *  - AUTO-010 Unity Ads tag-allowlist negative — `MainMenuAdLayout` is
 *    rejected before keyword matching.
 *
 * Per CLAUDE.md "tests puros sin mocks" — direct invariant assertions on
 * the catalog object.
 */
class EnginePhaseCatalogTest {

    // ═══════ AUTO-002: single source structure ═══════

    @Test
    fun `UNITY_RULES is non-empty`() {
        assertTrue(EnginePhaseCatalog.UNITY_RULES.isNotEmpty(), "Unity rule list must not be empty")
    }

    @Test
    fun `UNREAL_RULES is non-empty`() {
        assertTrue(EnginePhaseCatalog.UNREAL_RULES.isNotEmpty(), "Unreal rule list must not be empty")
    }

    @Test
    fun `every Unity rule has at least one keyword`() {
        for (rule in EnginePhaseCatalog.UNITY_RULES) {
            assertTrue(
                rule.keywords.isNotEmpty(),
                "rule for ${rule.type} (priority ${rule.priority}) has empty keyword set",
            )
        }
    }

    @Test
    fun `every Unreal rule has at least one keyword`() {
        for (rule in EnginePhaseCatalog.UNREAL_RULES) {
            assertTrue(
                rule.keywords.isNotEmpty(),
                "rule for ${rule.type} (priority ${rule.priority}) has empty keyword set",
            )
        }
    }

    // ═══════ AUTO-006: priority schema ═══════

    @Test
    fun `priority constants match spec values`() {
        assertEquals(100, EnginePhaseCatalog.PRIORITY_BOSS, "BOSS priority must be 100")
        assertEquals(90, EnginePhaseCatalog.PRIORITY_COMBAT, "COMBAT priority must be 90")
        assertEquals(80, EnginePhaseCatalog.PRIORITY_CUTSCENE, "CUTSCENE priority must be 80")
        assertEquals(70, EnginePhaseCatalog.PRIORITY_TUTORIAL, "TUTORIAL priority must be 70")
        assertEquals(60, EnginePhaseCatalog.PRIORITY_MENU, "MENU priority must be 60")
    }

    @Test
    fun `PRIORITY_ORDER is strictly descending`() {
        val ordered = EnginePhaseCatalog.PRIORITY_ORDER
        assertEquals(
            ordered.sortedDescending(),
            ordered,
            "PRIORITY_ORDER must already be sorted DESC: $ordered",
        )
        // Plus no duplicates
        assertEquals(ordered.size, ordered.toSet().size, "PRIORITY_ORDER must not contain duplicates")
    }

    @Test
    fun `Unity rules are pre-sorted DESC by priority`() {
        val priorities = EnginePhaseCatalog.UNITY_RULES.map { it.priority }
        assertEquals(
            priorities.sortedDescending(),
            priorities,
            "Unity rules must be ordered DESC by priority (first-match-wins assumes this)",
        )
    }

    @Test
    fun `Unreal rules are pre-sorted DESC by priority`() {
        val priorities = EnginePhaseCatalog.UNREAL_RULES.map { it.priority }
        assertEquals(
            priorities.sortedDescending(),
            priorities,
            "Unreal rules must be ordered DESC by priority",
        )
    }

    // ═══════ AUTO-005: bilingual ES+EN keyword coverage ═══════

    @Test
    fun `Unity rules include bilingual combat keywords`() {
        val combatKeywords = EnginePhaseCatalog.UNITY_RULES
            .filter { it.type == EventType.COMBAT_PHASE }
            .flatMap { it.keywords }
            .toSet()
        // EN
        assertTrue("combat" in combatKeywords, "missing EN 'combat': $combatKeywords")
        assertTrue("fight" in combatKeywords, "missing EN 'fight': $combatKeywords")
        assertTrue("battle" in combatKeywords, "missing EN 'battle': $combatKeywords")
        assertTrue("boss" in combatKeywords, "missing EN 'boss': $combatKeywords")
        assertTrue("wave" in combatKeywords, "missing EN 'wave': $combatKeywords")
        // ES
        assertTrue("combate" in combatKeywords, "missing ES 'combate': $combatKeywords")
        assertTrue("oleada" in combatKeywords, "missing ES 'oleada': $combatKeywords")
        assertTrue("jefe" in combatKeywords, "missing ES 'jefe': $combatKeywords")
    }

    @Test
    fun `Unity rules include bilingual cutscene keywords`() {
        val keywords = EnginePhaseCatalog.UNITY_RULES
            .first { it.type == EventType.CUTSCENE }
            .keywords
        assertTrue("cinematic" in keywords, "missing EN 'cinematic': $keywords")
        assertTrue("cutscene" in keywords, "missing EN 'cutscene': $keywords")
        assertTrue("cinemática" in keywords, "missing ES 'cinemática': $keywords")
    }

    @Test
    fun `Unity rules include bilingual tutorial keywords`() {
        val keywords = EnginePhaseCatalog.UNITY_RULES
            .first { it.type == EventType.TUTORIAL_PHASE }
            .keywords
        assertTrue("tutorial" in keywords, "missing EN 'tutorial': $keywords")
        assertTrue("onboarding" in keywords, "missing EN 'onboarding': $keywords")
        assertTrue("tuto" in keywords, "missing ES 'tuto': $keywords")
    }

    @Test
    fun `Unity rules include bilingual menu keywords`() {
        val keywords = EnginePhaseCatalog.UNITY_RULES
            .first { it.type == EventType.MENU_NAV }
            .keywords
        assertTrue("menu" in keywords, "missing 'menu': $keywords")
        assertTrue("lobby" in keywords, "missing EN 'lobby': $keywords")
        assertTrue("home" in keywords, "missing EN 'home': $keywords")
        assertTrue("inicio" in keywords, "missing ES 'inicio': $keywords")
    }

    // ═══════ AUTO-002: rulesForEngine resolver ═══════

    @Test
    fun `rulesForEngine returns Unity rules for Unity tag`() {
        assertEquals(EnginePhaseCatalog.UNITY_RULES, EnginePhaseCatalog.rulesForEngine("Unity"))
    }

    @Test
    fun `rulesForEngine returns Unity rules for UnityEngine tag`() {
        assertEquals(EnginePhaseCatalog.UNITY_RULES, EnginePhaseCatalog.rulesForEngine("UnityEngine"))
    }

    @Test
    fun `rulesForEngine returns Unreal rules for Unreal tag`() {
        assertEquals(EnginePhaseCatalog.UNREAL_RULES, EnginePhaseCatalog.rulesForEngine("Unreal"))
    }

    @Test
    fun `rulesForEngine returns empty for unknown engine`() {
        assertTrue(
            EnginePhaseCatalog.rulesForEngine("Cocos2d").isEmpty(),
            "unknown engine must return empty rule list (graceful degradation)",
        )
        assertTrue(EnginePhaseCatalog.rulesForEngine("").isEmpty())
    }

    // ═══════ AUTO-010: tag-allowlist negative (ad mediation) ═══════

    @Test
    fun `looksLikeAdMediation rejects MainMenuAdLayout`() {
        assertTrue(EnginePhaseCatalog.looksLikeAdMediation("MainMenuAdLayout"))
    }

    @Test
    fun `looksLikeAdMediation rejects BannerAdMenu`() {
        assertTrue(EnginePhaseCatalog.looksLikeAdMediation("BannerAdMenu"))
    }

    @Test
    fun `looksLikeAdMediation rejects AdsOverlay`() {
        assertTrue(EnginePhaseCatalog.looksLikeAdMediation("AdsOverlay"))
    }

    @Test
    fun `looksLikeAdMediation does NOT reject oleada (Spanish wave)`() {
        // Critical false-positive guard: `oleada` contains substring `ad`
        // but lowercase, and is NOT an ad mediation marker. Catalog must
        // case-distinguish the marker (Capital A) from incidental `ad`.
        assertTrue(!EnginePhaseCatalog.looksLikeAdMediation("oleada_03"))
    }

    @Test
    fun `looksLikeAdMediation does NOT reject random non-ad names`() {
        assertTrue(!EnginePhaseCatalog.looksLikeAdMediation("BattleArena"))
        assertTrue(!EnginePhaseCatalog.looksLikeAdMediation("Tutorial_01"))
        assertTrue(!EnginePhaseCatalog.looksLikeAdMediation("Boss_Arena_01"))
    }

    // ═══════ AUTO-010 end-to-end via classifier (integration with catalog) ═══════

    @Test
    fun `MainMenuAdLayout via classifier returns null (Unity Ads filter)`() {
        // Reinforces AUTO-010 at the boundary: the classifier MUST honour
        // the catalog's ad-mediation guard. Catalog test owns this case
        // because the negative obligation is spec-level (AUTO-010).
        assertNull(EnginePhaseClassifier.classify("Unity", "MainMenuAdLayout"))
    }

    // ═══════ AUTO-003 / AUTO-004 scenePattern wiring ═══════

    @Test
    fun `Unity Engine signature has scenePattern for scene capture`() {
        val unity = SdkSignatureCatalog.ALL.first { it.sdk == "Unity Engine" }
        val pattern = unity.scenePattern
        assertNotNull(pattern, "Unity Engine signature must declare scenePattern (Phase 2)")
        // "Loading scene: Boss_Arena_01" form
        val m1 = pattern.find("Loading scene: Boss_Arena_01")
        assertNotNull(m1, "scenePattern must capture 'Loading scene: <name>' form")
        assertEquals("Boss_Arena_01", m1.groupValues[1])
        // "Scene loaded successfully name=Level3" form
        val m2 = pattern.find("Scene loaded successfully name=Level3")
        assertNotNull(m2, "scenePattern must capture 'Scene loaded successfully name=<name>' form")
        assertEquals("Level3", m2.groupValues[1])
    }

    @Test
    fun `Unreal Engine signature has scenePattern for map capture`() {
        val unreal = SdkSignatureCatalog.ALL.first { it.sdk == "Unreal Engine" }
        val pattern = unreal.scenePattern
        assertNotNull(pattern, "Unreal Engine signature must declare scenePattern (Phase 2)")
        // "Loading package /Game/Maps/Tutorial_01" form
        val m1 = pattern.find("Loading package /Game/Maps/Tutorial_01")
        assertNotNull(m1, "scenePattern must capture '/Game/Maps/<name>' form")
        assertEquals("Tutorial_01", m1.groupValues[1])
        // "LogLevelSwitch: TravelTo Boss_Arena_01" form
        val m2 = pattern.find("LogLevelSwitch: TravelTo Boss_Arena_01")
        assertNotNull(m2, "scenePattern must capture 'LogLevelSwitch: TravelTo <name>' form")
        assertEquals("Boss_Arena_01", m2.groupValues[1])
    }

    @Test
    fun `Cocos2d signature has no scenePattern (out of Phase 2 scope)`() {
        // D5 limits Phase 2 to Unity + Unreal. Cocos2d entry remains
        // legacy LOADING-only with no auto-phase wiring.
        val cocos = SdkSignatureCatalog.ALL.first { it.sdk == "Cocos2d" }
        assertNull(cocos.scenePattern, "Cocos2d must not declare scenePattern in Phase 2")
    }

    // ═══════ AUTO-010 end-to-end with scenePattern → classifier ═══════

    @Test
    fun `Unity scenePattern + classifier rejects MainMenuAdLayout (AUTO-010)`() {
        // Obligatory negative test: a real Unity log line carrying a Unity
        // Ads mediation scene name MUST NOT be classified as MENU_NAV.
        val unity = SdkSignatureCatalog.ALL.first { it.sdk == "Unity Engine" }
        val msg = "Loading scene: MainMenuAdLayout"
        val captured = unity.scenePattern!!.find(msg)?.groupValues?.get(1)
        assertEquals("MainMenuAdLayout", captured, "scenePattern must capture the scene name")
        // Even though the name is captured, the classifier rejects it.
        assertNull(
            EnginePhaseClassifier.classify("Unity", captured!!),
            "Unity Ads mediation scene name must NOT classify as MENU_NAV",
        )
    }

    @Test
    fun `Unity scenePattern + classifier baseline positive for MainMenu`() {
        // Symmetric positive case: a real Unity log line with the engine
        // tag and a non-ad scene name MUST classify to MENU_NAV.
        val unity = SdkSignatureCatalog.ALL.first { it.sdk == "Unity Engine" }
        val msg = "Loading scene: MainMenu"
        val captured = unity.scenePattern!!.find(msg)?.groupValues?.get(1)
        assertEquals("MainMenu", captured)
        assertEquals(EventType.MENU_NAV, EnginePhaseClassifier.classify("Unity", captured!!))
    }
}
