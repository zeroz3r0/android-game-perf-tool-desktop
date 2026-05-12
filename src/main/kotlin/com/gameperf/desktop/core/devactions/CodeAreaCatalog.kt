package com.gameperf.desktop.core.devactions

/**
 * Per-rule × per-engine code-area hints.
 *
 * Sprint 1 fills this catalog for the 8 production rules × {UNITY, UNREAL,
 * COCOS2D, GENERIC}. Engines without dedicated entries (GODOT, NATIVE) fall
 * through to GENERIC per design ADR-3.
 *
 * Spanish copy follows the project tuteo-formal style (voseo accepted for
 * imperatives, formal third-person for explanations). Doc-links are first-
 * party only (docs.unity3d.com, dev.epicgames.com, docs.cocos2d-x.org,
 * developer.android.com).
 *
 * Mirrors the `RuleRegistry` + `SdkSignatureCatalog` static-object pattern
 * already established in the codebase (design ADR-2).
 *
 * @since v4.5.0
 */
internal object CodeAreaCatalog {

    // ────────────────────────────────────────────────────────────────────
    // stable-low-fps-low-cpu — sustained low FPS with CPU + thermal headroom.
    // ────────────────────────────────────────────────────────────────────
    private val stableLowFpsHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Quality Settings activos y pipeline de render (URP / HDRP / Built-in).",
                whyHere = "Un preset de calidad demasiado alto satura el render aunque la CPU esté libre.",
                docLink = "https://docs.unity3d.com/Manual/class-QualitySettings.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Frame Debugger para inspeccionar el conteo y orden de draw calls.",
                whyHere = "Muchas draw calls sin batching son la causa más común de FPS bajo con CPU libre.",
                docLink = "https://docs.unity3d.com/Manual/FrameDebugger.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "MonoBehaviour.Update y LateUpdate en scripts de gameplay.",
                whyHere = "Lógica pesada por frame en un único hilo limita el render aunque el CPU no esté al máximo.",
                docLink = "https://docs.unity3d.com/Manual/ExecutionOrder.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Scalability Settings y Engine Quality (sg.ResolutionQuality, sg.ShadowQuality, etc.).",
                whyHere = "Un preset demasiado alto para el dispositivo mantiene los FPS por debajo del objetivo.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/scalability-reference-for-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Consola in-game: 'stat unit' y 'stat gpu' para identificar el cuello (Game / Draw / GPU).",
                whyHere = "Sin el desglose por hilo es imposible saber si el límite es CPU-render, GPU o lógica.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/stat-commands-in-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Material complexity / Shader Complexity view-mode en el editor.",
                whyHere = "Materiales costosos elevan el overdraw y consumen el frame sin saturar la CPU.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/optimization-view-modes-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Director::mainLoop y la frecuencia configurada con setAnimationInterval.",
                whyHere = "Si el intervalo de animación está mal configurado el motor nunca alcanza el objetivo.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d5/d4b/classcocos2d_1_1_director.html",
            ),
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Texture atlas y compresión (ETC2 / ASTC) en assets de UI y sprites del HUD.",
                whyHere = "Texturas no comprimidas o atlas mal armados aumentan el bandwidth de render y bajan FPS.",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/sprites/spritesheets.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Profiling del render thread vía adb shell dumpsys gfxinfo <package>.",
                whyHere = "Las columnas Draw / Process / Execute muestran dónde se consume el frame fuera del JS / scripting.",
                docLink = "https://developer.android.com/topic/performance/rendering/inspect-gpu-rendering",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Configuración de VSync y el target FPS reportado por Choreographer.",
                whyHere = "VSync forzado o un cap erróneo limita el throughput aunque haya margen de hardware.",
                docLink = "https://developer.android.com/games/optimize/vulkan-prerotation",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // thermal-throttling — device throttling under sustained load.
    // ────────────────────────────────────────────────────────────────────
    private val thermalThrottlingHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Adaptive Performance package (Samsung, Snapdragon) para reaccionar a niveles térmicos.",
                whyHere = "Permite bajar dinámicamente calidad o FPS objetivo antes de que el SoC entre en throttling.",
                docLink = "https://docs.unity3d.com/Packages/com.unity.adaptiveperformance@latest",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Sombras dinámicas en Quality Settings y Light component (Realtime / Mixed).",
                whyHere = "Las sombras realtime son el primer factor térmico evitable en escenas con muchas luces.",
                docLink = "https://docs.unity3d.com/Manual/Shadows.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Resolución de render (renderScale en URP) y post-processing volumen activo.",
                whyHere = "Bajar la resolución interna reduce la presión térmica más rápido que tocar la malla.",
                docLink = "https://docs.unity3d.com/Packages/com.unity.render-pipelines.universal@latest/manual/universalrp-asset.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Mobile Renderer + scalability buckets (r.MobileContentScaleFactor, sg.PostProcessQuality).",
                whyHere = "Bajar la escala de contenido y el preset de post-proceso reduce el coste térmico inmediato.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/scalability-reference-for-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Dynamic shadows (r.Shadow.MaxResolution) y reflections en project settings móviles.",
                whyHere = "Sombras y reflejos en alta resolución son el principal driver térmico en mobile.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/dynamic-shadows-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Resolución de pantalla via GLView::setDesignResolutionSize y políticas de escala.",
                whyHere = "Reducir la resolución interna baja el coste térmico sin tocar arte.",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/basic_concepts/scene.html",
            ),
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Particle systems y blending modes activos en escenas largas.",
                whyHere = "Partículas con alpha-blending pesado generan throttling en sesiones extendidas.",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/particles/quickstart.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Thermal API (PowerManager.getCurrentThermalStatus) para reaccionar antes del throttling.",
                whyHere = "Permite degradar calidad gradualmente en lugar de sufrir caídas bruscas de FPS.",
                docLink = "https://developer.android.com/reference/android/os/PowerManager#getCurrentThermalStatus()",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Estrategia de FPS cap dinámico (60 → 45 → 30) en sesiones largas.",
                whyHere = "Capear FPS bajo presión térmica preserva la batería y evita parones bruscos.",
                docLink = "https://developer.android.com/games/optimize/adpf",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // memory-leak-suspect — sustained linear memory growth.
    // ────────────────────────────────────────────────────────────────────
    private val memoryLeakHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Memory Profiler module — comparar snapshots entre escenas o niveles.",
                whyHere = "El diff entre snapshots revela referencias no liberadas (texturas, eventos, listeners).",
                docLink = "https://docs.unity3d.com/Packages/com.unity.memoryprofiler@latest",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Resources.UnloadUnusedAssets y AssetBundle.Unload(true) al cambiar de escena.",
                whyHere = "Texturas y assets cargados dinámicamente quedan retenidos si no se descargan explícitamente.",
                docLink = "https://docs.unity3d.com/ScriptReference/Resources.UnloadUnusedAssets.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Eventos y delegados sin OnDestroy / OnDisable que los anule.",
                whyHere = "Los suscriptores no removidos mantienen vivos GameObjects supuestamente destruidos.",
                docLink = "https://docs.unity3d.com/Manual/ExecutionOrder.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Unreal Insights — Memory Insights para rastrear allocations por categoría.",
                whyHere = "El timeline de Memory Insights muestra qué clase o subsistema crece sin liberar.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/memory-insights-in-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "UObject leaks con 'obj list class=XXX' en consola y FReferenceFinder.",
                whyHere = "Objetos UObject con referencias vivas no son liberados por el GC aunque el gameplay lo asuma.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/unreal-object-handling-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "TextureCache::removeUnusedTextures al cambiar de escena.",
                whyHere = "Las texturas quedan retenidas en cache aunque la escena dueña ya no exista.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d8/dec/classcocos2d_1_1_texture_cache.html",
            ),
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Ref::retain y release sin balancear en código propio (refcounting manual).",
                whyHere = "El retain count desbalanceado es la causa más típica de leaks en Cocos2d-x.",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/basic_concepts/memory_management.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Android Studio Memory Profiler — capturar heap dump al inicio y al final de la sesión.",
                whyHere = "El diff de instancias entre dumps muestra qué clases acumulan sin colectar.",
                docLink = "https://developer.android.com/studio/profile/memory-profiler",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "LeakCanary o herramientas equivalentes para detectar Activity / Fragment leaks.",
                whyHere = "Las referencias retenidas a Activities destruidas inflan la heap sin ser código de juego.",
                docLink = "https://developer.android.com/studio/profile/memory-profiler#capture-heap-dump",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // jank-with-good-avg — high frame-time variance with OK average.
    // ────────────────────────────────────────────────────────────────────
    private val jankHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Profiler — CPU Usage timeline para localizar frames pesados puntuales.",
                whyHere = "Los frame spikes son invisibles en la media pero saltan en la línea temporal.",
                docLink = "https://docs.unity3d.com/Manual/ProfilerCPU.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "GC.Alloc y GC Allocations en scripts de gameplay (Profiler → Hierarchy).",
                whyHere = "Las pausas de GC son el motivo principal de jank con avg FPS bueno.",
                docLink = "https://docs.unity3d.com/Manual/performance-garbage-collection-best-practices.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Carga de assets en runtime (Resources.Load / Instantiate) durante gameplay.",
                whyHere = "Cargar prefabs o texturas en pleno juego congela un frame entero.",
                docLink = "https://docs.unity3d.com/Manual/AssetBundlesIntro.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Unreal Insights — Frame Tracker y CPU profiler en sesiones de gameplay reales.",
                whyHere = "Los frame spikes aislados se detectan visualmente en el timeline de Insights.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/unreal-insights-in-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Garbage collection time (stat GC) y carga de assets sincrónica.",
                whyHere = "El GC de Unreal puede producir pausas notables en frames concretos.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/garbage-collection-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Director::getScheduler para identificar callbacks programados que disparen lag spikes.",
                whyHere = "Schedulers mal afinados ejecutan trabajo pesado en frames específicos.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d8/df8/classcocos2d_1_1_scheduler.html",
            ),
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "FileUtils::getDataFromFile durante gameplay (I/O síncrono).",
                whyHere = "Lecturas de disco síncronas congelan el frame y son la causa más común de jank.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d2/d28/classcocos2d_1_1_file_utils.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Systrace / Perfetto trace centrado en Choreographer y SurfaceFlinger.",
                whyHere = "El timeline de frames muestra exactamente qué bloqueó cada frame pesado.",
                docLink = "https://developer.android.com/topic/performance/tracing",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Histograma de frame time (no solo media) en la pipeline de telemetría.",
                whyHere = "Sin distribución no se puede separar jank de FPS bajo sostenido.",
                docLink = "https://developer.android.com/topic/performance/rendering/jank",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // fps-cap-suspect — p99 sits at ~30 on a high-tier device.
    // ────────────────────────────────────────────────────────────────────
    private val fpsCapHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Application.targetFrameRate y QualitySettings.vSyncCount al iniciar.",
                whyHere = "Un targetFrameRate=30 o vSyncCount=2 cap absolutamente los FPS aunque el HW pueda más.",
                docLink = "https://docs.unity3d.com/ScriptReference/Application-targetFrameRate.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "OnDemandRendering.renderFrameInterval (si está activado).",
                whyHere = "El render on-demand puede caper el frame rate efectivo sin tocar targetFrameRate.",
                docLink = "https://docs.unity3d.com/ScriptReference/Rendering.OnDemandRendering.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "t.MaxFPS y r.OneFrameThreadLag en .ini de proyecto o consola.",
                whyHere = "t.MaxFPS=30 es el cap explícito; r.OneFrameThreadLag puede limitar throughput indirectamente.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/console-variables-reference-for-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Device Profiles (Mobile_Low / Mobile_Mid / Mobile_High) y su FPS objetivo.",
                whyHere = "Los profiles per-device pueden estar forzando 30 FPS por defecto en gamas medias.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/device-profiles-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Director::setAnimationInterval(1.0/30.0) en código de inicialización.",
                whyHere = "Es el cap más directo de Cocos2d-x; revisar AppDelegate y la inicialización del Director.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d5/d4b/classcocos2d_1_1_director.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Display.setFrameRate y la política de VSync (forzado vs adaptativo).",
                whyHere = "Una llamada explícita a 30 Hz cap el frame rate efectivo a nivel de surface.",
                docLink = "https://developer.android.com/reference/android/view/Surface#setFrameRate(float,%20int)",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Configuración de PowerManager y modos de bajo consumo en el dispositivo.",
                whyHere = "El modo ahorro de batería puede forzar un cap a 30 FPS sin que la app lo decida.",
                docLink = "https://developer.android.com/reference/android/os/PowerManager",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // cpu-saturated — sustained avgCpu ≥ 85.
    // ────────────────────────────────────────────────────────────────────
    private val cpuSaturationHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "MonoBehaviour.Update y FixedUpdate — buscar bucles pesados por frame.",
                whyHere = "El hilo principal de Unity ejecuta scripts cada frame; trabajo pesado allí satura la CPU.",
                docLink = "https://docs.unity3d.com/Manual/ExecutionOrder.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Job System + Burst Compiler para mover cálculos a hilos secundarios.",
                whyHere = "Mover lógica intensiva a Jobs descomprime el main thread sin reescribir todo.",
                docLink = "https://docs.unity3d.com/Manual/JobSystem.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Physics.autoSimulation y la frecuencia de Time.fixedDeltaTime.",
                whyHere = "Una fixedDeltaTime demasiado baja multiplica las llamadas a FixedUpdate y satura la CPU.",
                docLink = "https://docs.unity3d.com/Manual/class-TimeManager.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Blueprint tick + Actor::Tick — auditar qué actores tienen tick habilitado.",
                whyHere = "Cada actor con PrimaryActorTick.bCanEverTick=true suma trabajo al frame.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/actor-ticking-in-unreal-engine",
            ),
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Async tasks (FAsyncTask, AsyncTaskGraph) para mover lógica fuera del Game thread.",
                whyHere = "Trabajo pesado en el Game thread satura CPU sin aprovechar los demás cores.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/asynchronous-asset-loading-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Scheduler updates y selectors registrados por frame (Node::schedule).",
                whyHere = "Muchos selectors corriendo cada frame saturan el main loop del Director.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/d8/df8/classcocos2d_1_1_scheduler.html",
            ),
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "AsyncTaskPool para descargar trabajo del hilo principal.",
                whyHere = "Permite mover cálculos a un thread pool sin reescribir la lógica del gameplay.",
                docLink = "https://docs.cocos2d-x.org/api-ref/cplusplus/v4x/dc/d34/classcocos2d_1_1_async_task_pool.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Perfetto / Android Studio CPU Profiler — identificar el hot method por sample.",
                whyHere = "Sin el hot method del profiler es imposible saber qué satura la CPU.",
                docLink = "https://developer.android.com/studio/profile/cpu-profiler",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Estrategia de threading (HandlerThread, coroutines, Executor pools).",
                whyHere = "Cualquier trabajo pesado fuera del thread principal alivia la saturación inmediata.",
                docLink = "https://developer.android.com/guide/background/threading",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // ad-vs-game-fps-gap — informational: ad screens drag the raw average.
    // ────────────────────────────────────────────────────────────────────
    private val adGapHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Reportes de KPI dashboards y comparación bruto vs filtrado.",
                whyHere = "El número filtrado refleja el rendimiento real del gameplay sin la contribución del SDK de ads.",
                docLink = "https://docs.unity3d.com/Packages/com.unity.ads@latest",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "Integración del Unity Ads / AdMob SDK y el momento en que se muestran interstitials.",
                whyHere = "Un SDK de ads mal integrado consume FPS durante la transición y sesga la media.",
                docLink = "https://docs.unity3d.com/Packages/com.unity.ads@latest/manual/MonetizationBasicIntegrationUnity.html",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Plugins de monetización (Google Ad Mob, etc.) y su impacto durante interstitials.",
                whyHere = "El render del SDK de ads compite por el frame budget durante la transición.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/plugins-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "AppLovin / IronSource / AdMob SDK integration y pausas de gameplay durante anuncios.",
                whyHere = "Las pantallas de anuncios pausan el render del juego y sesgan métricas globales.",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/index.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Pipeline de telemetría que separe FPS de gameplay vs FPS de ad-screens.",
                whyHere = "Sin la segmentación, los anuncios degradan los promedios y enmascaran problemas reales.",
                docLink = "https://developer.android.com/topic/performance/rendering",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Política de muestreo durante interstitials (pausar capture o etiquetar samples).",
                whyHere = "Etiquetar samples durante ad-screens permite filtrar a posteriori sin perder datos.",
                docLink = "https://developer.android.com/games/optimize/measure-performance",
            ),
        ),
    )

    // ────────────────────────────────────────────────────────────────────
    // loading-thermal-recovery — informational: loading screens cool the device.
    // ────────────────────────────────────────────────────────────────────
    private val loadingRecoveryHints: Map<GameEngine, List<CodeAreaHint>> = mapOf(
        GameEngine.UNITY to listOf(
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "SceneManager.LoadSceneAsync y duración mínima de las pantallas de carga.",
                whyHere = "Las cargas asíncronas actúan como ventana térmica si tienen duración suficiente.",
                docLink = "https://docs.unity3d.com/ScriptReference/SceneManagement.SceneManager.LoadSceneAsync.html",
            ),
            CodeAreaHint(
                engine = GameEngine.UNITY,
                area = "AssetBundles + Addressables al cambiar de escena (no acortar excesivamente).",
                whyHere = "Mantener la carga visible mientras el sistema descomprime aprovecha el respiro térmico.",
                docLink = "https://docs.unity3d.com/Packages/com.unity.addressables@latest",
            ),
        ),
        GameEngine.UNREAL to listOf(
            CodeAreaHint(
                engine = GameEngine.UNREAL,
                area = "Async level loading + loading screen widget durante transiciones largas.",
                whyHere = "Una loading screen visible mientras se cargan assets evita que el SoC se sobrecaliente.",
                docLink = "https://dev.epicgames.com/documentation/en-us/unreal-engine/asynchronous-asset-loading-in-unreal-engine",
            ),
        ),
        GameEngine.COCOS2D to listOf(
            CodeAreaHint(
                engine = GameEngine.COCOS2D,
                area = "Director::replaceScene con transición visible y duración mínima.",
                whyHere = "La transición permite que el SoC baje temperatura antes de la nueva escena.",
                docLink = "https://docs.cocos2d-x.org/cocos2d-x/v3/en/basic_concepts/scene.html",
            ),
        ),
        GameEngine.GENERIC to listOf(
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "UX de loading: duración mínima visible para no estresar al SoC entre niveles.",
                whyHere = "Cargas demasiado cortas reanudan gameplay antes de que el dispositivo recupere temperatura.",
                docLink = "https://developer.android.com/games/optimize/adpf",
            ),
            CodeAreaHint(
                engine = GameEngine.GENERIC,
                area = "Métrica de temperatura de carcasa antes y después de la carga (telemetría).",
                whyHere = "Sin la métrica no se puede validar que la carga actúa como ventana térmica.",
                docLink = "https://developer.android.com/reference/android/os/HardwarePropertiesManager",
            ),
        ),
    )

    /** Per-rule × per-engine catalog. Engines without an entry fall through to GENERIC (design ADR-3). */
    private val catalog: Map<String, Map<GameEngine, List<CodeAreaHint>>> = mapOf(
        "stable-low-fps-low-cpu" to stableLowFpsHints,
        "thermal-throttling" to thermalThrottlingHints,
        "memory-leak-suspect" to memoryLeakHints,
        "jank-with-good-avg" to jankHints,
        "fps-cap-suspect" to fpsCapHints,
        "cpu-saturated" to cpuSaturationHints,
        "ad-vs-game-fps-gap" to adGapHints,
        "loading-thermal-recovery" to loadingRecoveryHints,
    )

    /**
     * Returns the code-area hints for [ruleId] under [engine].
     *
     * Engines without dedicated entries (GODOT, NATIVE) fall through to
     * [GameEngine.GENERIC] per design ADR-3. Unknown ruleIds return an empty
     * list — Sprint 1 completeness tests prevent that for production rules.
     */
    fun lookup(ruleId: String, engine: GameEngine): List<CodeAreaHint> {
        val ruleEntries = catalog[ruleId] ?: return emptyList()
        return ruleEntries[engine] ?: ruleEntries[GameEngine.GENERIC] ?: emptyList()
    }
}
