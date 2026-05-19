package com.gameperf.desktop.core.devactions

/**
 * Per-rule list of [ActionStep] suggestions.
 *
 * Sprint 1 fills this catalog with research-grade entries for the 8
 * production rules. Each rule gets 3..5 steps mixing engine-agnostic
 * advice with engine-specific suggestions tagged via
 * [ActionStep.engineSpecific]. [DevActionEngine] filters by the detected
 * engine downstream (design ADR-3 / ADR-4).
 *
 * Spanish copy follows the project tuteo-formal style — neutral Castilian
 * Spanish (Spain), "tú" form for imperatives ("reduce", "baja", "captura",
 * NOT Rioplatense voseo "reducí" / "bajá" / "capturá"). Matches the README
 * in-app castellano formal convention (CLAUDE.md "Convención de idiomas").
 * Doc-links are first-party only (docs.unity3d.com, dev.epicgames.com,
 * docs.cocos2d-x.org, developer.android.com).
 *
 * Mirrors the static-object pattern (design ADR-2).
 *
 * @since v4.5.0
 */
internal object ActionStepsCatalog {

    private val catalog: Map<String, List<ActionStep>> = mapOf(
        "stable-low-fps-low-cpu" to listOf(
            ActionStep(
                description = "Perfila el bucle principal y mídelo por frame: identifica los tres métodos más caros.",
                tool = "Android Studio CPU Profiler",
                docLink = "https://developer.android.com/studio/profile/cpu-profiler",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Reduce los Quality Settings (sombras, anti-aliasing, pixel light count) un nivel y vuelve a medir.",
                tool = "Unity Editor",
                docLink = "https://docs.unity3d.com/Manual/class-QualitySettings.html",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Baja los Scalability buckets (sg.ShadowQuality, sg.PostProcessQuality) y vuelve a perfilar.",
                tool = "Unreal Editor",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/scalability-reference-for-unreal-engine",
                engineSpecific = GameEngine.UNREAL,
            ),
            ActionStep(
                description = "Verifica que setAnimationInterval esté en 1.0/60.0 al inicializar el Director.",
                tool = "Cocos2d-x",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d5/d4b/classcocos2d_1_1_director.html",
                engineSpecific = GameEngine.COCOS2D,
            ),
            ActionStep(
                description = "Activa batching estático y dinámico, y agrupa meshes que compartan material.",
                tool = null,
                docLink = "https://developer.android.com/topic/performance/rendering",
                engineSpecific = null,
            ),
        ),
        "thermal-throttling" to listOf(
            ActionStep(
                description = "Implementa un FPS cap dinámico que baje a 45 o 30 cuando el dispositivo reporte throttling.",
                tool = null,
                docLink = "https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Reduce dynamic shadows y el preset de post-processing un nivel en sesiones largas.",
                tool = null,
                docLink = "https://developer.android.com/games/optimize/adpf",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Instala el Adaptive Performance package y reacciona al ThermalEvent del SoC.",
                tool = "Unity Adaptive Performance",
                docLink = "https://docs.unity3d.com/Packages/com.unity.adaptiveperformance@latest",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Baja r.MobileContentScaleFactor y sg.ShadowQuality en los Device Profiles afectados.",
                tool = "Unreal Device Profiles",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/device-profiles-in-unreal-engine",
                engineSpecific = GameEngine.UNREAL,
            ),
            ActionStep(
                description = "Reduce la resolución de diseño con GLView::setDesignResolutionSize en gama baja.",
                tool = "Cocos2d-x",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/basic_concepts/scene.html",
                engineSpecific = GameEngine.COCOS2D,
            ),
        ),
        "memory-leak-suspect" to listOf(
            ActionStep(
                description = "Captura heap dumps al inicio y al final de la sesión y compara referencias retenidas.",
                tool = "Android Studio Memory Profiler",
                docLink = "https://developer.android.com/studio/profile/memory-profiler",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Audita listeners y delegados: cualquier suscripción debe tener su desuscripción en OnDestroy / OnDisable.",
                tool = null,
                docLink = "https://developer.android.com/studio/profile/memory-profiler",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Captura snapshots con Memory Profiler antes y después de cargar una escena pesada.",
                tool = "Unity Memory Profiler",
                docLink = "https://docs.unity3d.com/Packages/com.unity.memoryprofiler@latest",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Abre una traza de Memory Insights y revisa qué subsistema crece sin liberar.",
                tool = "Unreal Insights",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/memory-insights-in-unreal-engine",
                engineSpecific = GameEngine.UNREAL,
            ),
            ActionStep(
                description = "Llama a TextureCache::removeUnusedTextures al cambiar de escena y valida retain counts.",
                tool = "Cocos2d-x",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/basic_concepts/memory_management.html",
                engineSpecific = GameEngine.COCOS2D,
            ),
        ),
        "jank-with-good-avg" to listOf(
            ActionStep(
                description = "Captura un trace con Perfetto durante un tramo problemático y abre el frame timeline.",
                tool = "Perfetto",
                docLink = "https://developer.android.com/topic/performance/tracing",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Lleva a un dashboard el histograma de frame time, no solo la media.",
                tool = null,
                docLink = "https://developer.android.com/topic/performance/rendering/jank",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Reduce GC.Alloc en hot paths: reutiliza colecciones, evita boxing y string concat por frame.",
                tool = "Unity Profiler",
                docLink = "https://docs.unity3d.com/Manual/performance-garbage-collection-best-practices.html",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Audita el costo del Garbage Collector con 'stat GC' y mueve cargas pesadas a async.",
                tool = "Unreal Engine",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/garbage-collection-in-unreal-engine",
                engineSpecific = GameEngine.UNREAL,
            ),
            ActionStep(
                description = "Elimina I/O síncrono (FileUtils::getDataFromFile) en gameplay y migra a async.",
                tool = "Cocos2d-x",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d2/d28/classcocos2d_1_1_file_utils.html",
                engineSpecific = GameEngine.COCOS2D,
            ),
        ),
        "fps-cap-suspect" to listOf(
            ActionStep(
                description = "Confirma con producto si el cap a 30 FPS es intencional (batería, diseño). Si no, retíralo.",
                tool = null,
                docLink = "https://developer.android.com/games/optimize/measure-performance",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Audita llamadas a Display.setFrameRate y la política de VSync activa.",
                tool = null,
                docLink = "https://developer.android.com/reference/android/view/Surface#setFrameRate(float,%20int)",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Revisa Application.targetFrameRate y QualitySettings.vSyncCount al iniciar la app.",
                tool = "Unity",
                docLink = "https://docs.unity3d.com/ScriptReference/Application-targetFrameRate.html",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Verifica t.MaxFPS y device profiles móviles para descartar un cap heredado.",
                tool = "Unreal Engine",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/console-variables-reference-for-unreal-engine",
                engineSpecific = GameEngine.UNREAL,
            ),
            ActionStep(
                description = "Cambia Director::setAnimationInterval de 1.0/30.0 a 1.0/60.0 en AppDelegate.",
                tool = "Cocos2d-x",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d5/d4b/classcocos2d_1_1_director.html",
                engineSpecific = GameEngine.COCOS2D,
            ),
        ),
        "cpu-saturated" to listOf(
            ActionStep(
                description = "Identifica el hot method con CPU Profiler y muévelo a un hilo secundario.",
                tool = "Android Studio CPU Profiler",
                docLink = "https://developer.android.com/studio/profile/cpu-profiler",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Mueve cálculos pesados a coroutines o thread pools en lugar de bloquear el main loop.",
                tool = null,
                docLink = "https://developer.android.com/guide/background/threading",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Convierte Update loops pesados en C# Jobs + Burst para liberar el main thread.",
                tool = "Unity Job System",
                docLink = "https://docs.unity3d.com/Manual/JobSystem.html",
                engineSpecific = GameEngine.UNITY,
            ),
            ActionStep(
                description = "Desactiva Tick en actores que no lo necesitan y mueve trabajo a FAsyncTask.",
                tool = "Unreal Engine",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/actor-ticking-in-unreal-engine",
                engineSpecific = GameEngine.UNREAL,
            ),
            ActionStep(
                description = "Usa AsyncTaskPool de Cocos2d-x para descargar trabajo del Director main loop.",
                tool = "Cocos2d-x",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/dc/d34/classcocos2d_1_1_async_task_pool.html",
                engineSpecific = GameEngine.COCOS2D,
            ),
        ),
        "ad-vs-game-fps-gap" to listOf(
            ActionStep(
                description = "Usa la métrica filtrada como referencia principal en reportes; reserva la bruta para auditoría.",
                tool = null,
                docLink = "https://developer.android.com/games/optimize/measure-performance",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Etiqueta los samples capturados durante interstitials para poder filtrarlos a posteriori.",
                tool = null,
                docLink = "https://developer.android.com/topic/performance/rendering",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Revisa la integración del SDK de Unity Ads y el momento exacto en que dispara el interstitial.",
                tool = "Unity Ads",
                docLink = "https://docs.unity3d.com/Packages/com.unity.ads@latest",
                engineSpecific = GameEngine.UNITY,
            ),
        ),
        "loading-thermal-recovery" to listOf(
            ActionStep(
                description = "Conserva la duración mínima de las loading screens en futuras optimizaciones de UX.",
                tool = null,
                docLink = "https://developer.android.com/games/optimize/adpf",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Registra la temperatura de carcasa antes y después de cada loading para validar el respiro térmico.",
                tool = null,
                docLink = "https://developer.android.com/reference/android/os/HardwarePropertiesManager",
                engineSpecific = null,
            ),
            ActionStep(
                description = "Mantén SceneManager.LoadSceneAsync con barra de progreso visible mientras dura la carga.",
                tool = "Unity",
                docLink = "https://docs.unity3d.com/ScriptReference/SceneManagement.SceneManager.LoadSceneAsync.html",
                engineSpecific = GameEngine.UNITY,
            ),
        ),
    )

    /**
     * Returns the suggested action steps for [ruleId].
     *
     * Returns ALL steps regardless of engine — [DevActionEngine.run] filters
     * by [ActionStep.engineSpecific] downstream. Unknown ruleIds return an
     * empty list — Sprint 1 completeness tests prevent that for production rules.
     */
    fun lookup(ruleId: String): List<ActionStep> = catalog[ruleId] ?: emptyList()
}
