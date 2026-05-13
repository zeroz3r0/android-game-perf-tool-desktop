package com.gameperf.desktop.core.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [EnginePhaseClassifier].
 *
 * Pure tests — classifier has zero I/O. Each test exercises:
 *  - AUTO-005: bilingual ES+EN keyword mapping per phase type.
 *  - AUTO-006: priority resolution for compound names (BOSS > COMBAT >
 *    CUTSCENE > TUTORIAL > MENU).
 *  - AUTO-010: tag-allowlist negative (Unity Ads `*AdLayout*` scene names
 *    are NOT phase-detected).
 *  - Unmatched names return `null` (no fallback class).
 *
 * Per CLAUDE.md "tests puros sin mocks": no mocks, classifier is a top-level
 * pure function taking `(engine, sceneName)` and returning `EventType?`.
 */
class EnginePhaseClassifierTest {

    // ═══════ Combat / Boss / Wave — bilingual ═══════

    @Test
    fun `BattleArena classifies as COMBAT_PHASE`() {
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unity", "BattleArena"))
    }

    @Test
    fun `Spanish oleada_03 classifies as COMBAT_PHASE`() {
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unity", "oleada_03"))
    }

    @Test
    fun `fight keyword classifies as COMBAT_PHASE`() {
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unreal", "FightZone_02"))
    }

    @Test
    fun `boss keyword classifies as COMBAT_PHASE`() {
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unity", "Boss_Arena_01"))
    }

    @Test
    fun `combate Spanish classifies as COMBAT_PHASE`() {
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unreal", "combate_final"))
    }

    @Test
    fun `jefe Spanish boss classifies as COMBAT_PHASE`() {
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unity", "Jefe_Dragon"))
    }

    // ═══════ Cutscene / Cinematic — bilingual ═══════

    @Test
    fun `cinematic classifies as CUTSCENE`() {
        assertEquals(EventType.CUTSCENE, EnginePhaseClassifier.classify("Unity", "Cinematic_Intro"))
    }

    @Test
    fun `cinematica Spanish classifies as CUTSCENE`() {
        assertEquals(EventType.CUTSCENE, EnginePhaseClassifier.classify("Unreal", "cinemática_opening"))
    }

    @Test
    fun `cutscene keyword classifies as CUTSCENE`() {
        assertEquals(EventType.CUTSCENE, EnginePhaseClassifier.classify("Unity", "Cutscene_05"))
    }

    // ═══════ Tutorial / Onboarding — bilingual ═══════

    @Test
    fun `tutorial classifies as TUTORIAL_PHASE`() {
        assertEquals(EventType.TUTORIAL_PHASE, EnginePhaseClassifier.classify("Unity", "Tutorial_01"))
    }

    @Test
    fun `tuto Spanish classifies as TUTORIAL_PHASE`() {
        assertEquals(EventType.TUTORIAL_PHASE, EnginePhaseClassifier.classify("Unreal", "tuto_step3"))
    }

    @Test
    fun `onboarding classifies as TUTORIAL_PHASE`() {
        assertEquals(EventType.TUTORIAL_PHASE, EnginePhaseClassifier.classify("Unity", "OnboardingFlow"))
    }

    // ═══════ Menu / Lobby — bilingual ═══════

    @Test
    fun `MainMenu classifies as MENU_NAV`() {
        assertEquals(EventType.MENU_NAV, EnginePhaseClassifier.classify("Unity", "MainMenu"))
    }

    @Test
    fun `lobby classifies as MENU_NAV`() {
        assertEquals(EventType.MENU_NAV, EnginePhaseClassifier.classify("Unreal", "Lobby_Hub"))
    }

    @Test
    fun `inicio Spanish classifies as MENU_NAV`() {
        assertEquals(EventType.MENU_NAV, EnginePhaseClassifier.classify("Unity", "pantalla_inicio"))
    }

    // ═══════ Priority resolution (AUTO-006) ═══════

    @Test
    fun `BossFightMenu resolves COMBAT_PHASE over MENU (priority)`() {
        // BOSS keyword wins over MENU keyword via priority order DESC.
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unity", "BossFightMenu"))
    }

    @Test
    fun `BossArenaMenu resolves COMBAT_PHASE over MENU`() {
        // BOSS+COMBAT both present; MENU also present — COMBAT wins.
        assertEquals(EventType.COMBAT_PHASE, EnginePhaseClassifier.classify("Unreal", "BossArenaMenu"))
    }

    @Test
    fun `CutsceneMenu resolves CUTSCENE over MENU`() {
        assertEquals(EventType.CUTSCENE, EnginePhaseClassifier.classify("Unity", "CutsceneMenu"))
    }

    @Test
    fun `TutorialMenu resolves TUTORIAL over MENU`() {
        assertEquals(EventType.TUTORIAL_PHASE, EnginePhaseClassifier.classify("Unity", "TutorialMenu"))
    }

    // ═══════ Unmatched / negative cases ═══════

    @Test
    fun `obfuscated scene name s001 returns null`() {
        assertNull(EnginePhaseClassifier.classify("Unity", "s001"))
    }

    @Test
    fun `empty scene name returns null`() {
        assertNull(EnginePhaseClassifier.classify("Unity", ""))
    }

    @Test
    fun `random level name returns null`() {
        assertNull(EnginePhaseClassifier.classify("Unreal", "lvl_042"))
    }

    // ═══════ AUTO-010 — Unity Ads tag-allowlist negative ═══════

    @Test
    fun `MainMenuAdLayout returns null (Unity Ads mediation)`() {
        // tag=Unity msg="MainMenuAdLayout loaded" must NOT classify as MENU_NAV
        // even though "menu" keyword is present — Ad/Ads/AdLayout substring
        // filter rejects it before keyword matching.
        assertNull(EnginePhaseClassifier.classify("Unity", "MainMenuAdLayout"))
    }

    @Test
    fun `BannerAdMenu returns null (Unity Ads mediation)`() {
        assertNull(EnginePhaseClassifier.classify("Unity", "BannerAdMenu"))
    }

    @Test
    fun `AdsOverlay returns null`() {
        assertNull(EnginePhaseClassifier.classify("Unity", "AdsOverlay"))
    }
}
