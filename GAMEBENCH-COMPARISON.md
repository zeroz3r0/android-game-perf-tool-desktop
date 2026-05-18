# GameBench vs GamePerf Desktop — Comparación práctica

**Fecha**: 2026-05-11
**Versión nuestra**: GamePerf Desktop v4.4.1
**Versión GameBench**: Studio Pro Desktop v1.37.2 + Pro Android v8.37.93 + Web Dashboard v3.2.0

Audiencia: product, QA, marketing, desarrolladores no-Android. Para detalles técnicos de archivos y código, ver apéndice al final.

---

## TL;DR — ¿quién gana en qué?

- **GameBench gana en**: medición de GPU, análisis de red detallado, dashboard en la nube, integraciones empresariales (SSO, Jira, API REST) y menor consumo del propio medidor sobre el teléfono (3.8% vs nuestro ~10-15%).
- **Nosotros ganamos en**: detección automática de anuncios/IAP/loading sin tocar el juego, conclusiones cualitativas ("esto es throttling térmico", "fuga de memoria probable"), reporte HTML/PDF auto-contenido sin login, video del gameplay sincronizado con la timeline, nota A/B/C/D/F ajustada al género y target FPS del juego, instalación 0-touch (sin Android Studio), y ser código abierto y gratuito.
- **En qué somos pares**: FPS, percentiles de frame time, CPU general, memoria, comparación entre dos sesiones, captura en vivo.
- **Estrategia**: no peleamos en cloud ni empresarial. Pelearemos en privacidad, diagnóstico automático e indie-friendly.

---

## 1. ¿Qué es cada uno?

**GameBench** es una suite comercial enterprise: una app de escritorio (Win/Mac) más una app Android instalada en el dispositivo, más un SDK opcional que se embebe en el juego, todo conectado a un dashboard en la nube. Su modelo de negocio es B2B SaaS con planes "request a demo". Mercado natural: estudios AAA y consultoras de QA grandes.

**GamePerf Desktop** es una aplicación de escritorio open source MIT (Win/Mac/Linux). Se conecta al teléfono por USB o WiFi, captura todo localmente, genera un reporte HTML/PDF auto-contenido y guarda la sesión como un ZIP `.gameperf` que se comparte por cualquier medio. Sin login, sin nube, sin SDK que embeber. Mercado natural: indies, QA individuales, equipos chicos, devs que necesitan privacidad sobre builds prelanzamiento.

---

## 2. Comparación filtrada (solo donde hay diferencia)

Leyenda: ✅ tiene · 🟡 parcial · ❌ no tiene

### Rendimiento del medidor

| Capability | GameBench | Nosotros | ¿Qué significa para tu juego? |
|---|---|---|---|
| Consumo de CPU del medidor sobre el teléfono | **3.8%** | ~10-15% | Si tu juego ya viene apretado, nuestro medidor le come más recursos. Mide con margen. |
| Sample rate efectivo | 1 Hz | ~1-2 Hz | Resolución equivalente en la práctica. |

### Métricas que faltan o sobran

| Capability | GameBench | Nosotros | ¿Qué significa para tu juego? |
|---|---|---|---|
| GPU (uso y frecuencia) | ✅ a nivel driver gráfico (uso + frecuencia + sub-counters) | 🟡 uso % vía kernel sysfs (v4.5.0, Mali + Adreno; sin frecuencia ni sub-counters)[^gpu] | Para juegos gráficamente exigentes, ahora medimos si la GPU está saturada en % de uso (Mali ARM + Adreno Qualcomm). GameBench sigue ganando en frecuencia por dominio + sub-counters de driver (vertex / fragment / texture), porque su SDK Pro está embebido en el juego. PowerVR (MediaTek/Unisoc) todavía no soportado del lado nuestro. |
| Red por conexión + tiempo al primer byte | ✅ | 🟡 ancho de banda total app vía binder/dumpsys (v4.6.0; sin desglose por endpoint)[^net] | Si te importa cuánto tarda cada llamada al servidor o cada anuncio en descargar, GameBench desglosa por endpoint. Nosotros medimos el total de bytes RX/TX que consume el juego, lo cual sirve para detectar fugas de bandwidth, anuncios pesados o uploads no planeados, pero no diferenciamos qué endpoint específico está consumiendo. |
| Frecuencia por núcleo de CPU | ✅ | ❌ | Importa si quieres saber qué núcleo está saturado en juegos multi-thread o si el sistema está bajando clocks por calor. |
| Consumo real de batería (mA / mW) | ✅ Android | 🟡 solo nivel y temperatura | Si tu KPI es "minutos de juego por carga", GameBench te da el dato real. Nosotros estimamos por temperatura y nivel. |
| Memoria de WebView (juegos híbridos) | ✅ | ❌ | Solo importa si tu juego es Cocos2d-JS, Construct, Unity WebGL o similar. |
| Temperatura del chip y de la piel del dispositivo | 🟡 solo batería | ✅ piel + chip + batería | Diferenciamos temperatura externa (la que siente el jugador) del chip interno, ajustando por fabricante de SoC (Snapdragon, Tensor, etc.). Crítico para detectar throttling térmico. |
| Detección de throttling | ❌ explícita | ✅ con umbrales | Sabemos automáticamente cuándo el teléfono está bajando frecuencias por calor y te lo decimos en las conclusiones. |
| Vitals-aware single-session proxies | ❌ (no expone alertas Vitals nativas) | ✅ 3 KPIs store-gating (v4.6.0) | GamePerf reporta 3 Vitals store-gating KPIs (Crash Rate Users 1.09%, ANR Rate Users 0.47%, Wake Locks Rate >2h screen-off) como proxies single-session; GameBench no expone native Vitals alerts. Si una sesión cruza el gate de Google, lo verás en el banner del reporte con la copia oficial y la causa típica (SDK de analíticas mal configurado). |
| Detección automática de anuncios / IAP / loading | ❌ (solo manual o vía SDK) | ✅ catálogo de 6 SDKs | El reporte identifica solo cuándo aparece un anuncio (AdMob, Unity Ads, IronSource, AppLovin, Meta AN) o una compra in-app, sin que toques una línea del juego. |
| Métricas filtradas (solo juego vs incluyendo anuncios) | ❌ | ✅ | Cuando un anuncio interrumpe, su FPS no contamina la media del juego. El reporte muestra dos cifras: "juego real 58 fps" y "incluyendo anuncios 75 fps". |
| Conclusiones cualitativas / diagnóstico | ❌ (solo datos crudos) | ✅ 8 reglas deterministas | El reporte te dice qué está pasando en lenguaje humano: "throttling térmico probable", "fuga de memoria", "FPS cap detectado", "jank con buena media". No es IA, son reglas explícitas. |
| Nota S/A/B/C/D/F ajustada al juego | ❌ | ✅ por género + target FPS + gama del device | Un juego casual capado a 30 fps que cumple los 30 fps saca A. Un shooter que solo llega a 30 fps saca D. La nota es proporcional a las expectativas reales del juego, no a un absoluto. |

### Reportes y compartir

| Capability | GameBench | Nosotros | ¿Qué significa para tu juego? |
|---|---|---|---|
| Reporte HTML local auto-contenido | ❌ (todo va a la nube) | ✅ con gráficos interactivos | Generás el reporte y lo abrís en cualquier navegador, sin login, sin subir nada. |
| Export PDF | ❌ no documentado | ✅ | Adjuntás el PDF al ticket de Jira/Notion sin más pasos. |
| Archivo portable `.gameperf` para compartir | ❌ (sharing va por enlace de dashboard) | ✅ ZIP self-contained | Lo mandás por Slack/email/Drive y el destinatario lo abre como sesión propia. Sin cuentas, sin permisos. |
| Trends build-over-build | ✅ | ❌ | GameBench te grafica cómo evoluciona el rendimiento entre builds. Útil para regresiones. Aún no lo tenemos. |
| Tags libres y búsqueda | ✅ | 🟡 (favorito + competidor binario) | GameBench permite etiquetar sesiones con cualquier palabra y buscar. Nosotros solo tenemos favorito. |
| API REST para CI/CD | ✅ con tokens | ❌ | Si tu pipeline de QA necesita disparar capturas automáticas y consumir resultados, GameBench se integra; nosotros aún no. |
| Video del gameplay completo | ❌ (solo screenshots) | ✅ grabado y segmentado | El reporte incluye el video del juego sincronizado con la timeline. |
| Video sincronizado con timeline (scrub bidireccional) | ❌ | ✅ | Tocás un pico de jank en el gráfico y el video salta a ese momento. Diagnóstico visual instantáneo. |

### Plataformas e instalación

| Capability | GameBench | Nosotros | ¿Qué significa para tu juego? |
|---|---|---|---|
| Soporte Linux | ❌ no documentado | ✅ (vía JAR) | Si tu pipeline corre en Linux, podemos. GameBench no. |
| Soporte iOS production-ready | ✅ (incluyendo wireless iOS 17+) | 🟡 (arquitectura lista, features pendientes) | Para iOS hoy, GameBench está más maduro. Estamos cerrando la brecha. |
| Auto-instala adb/ffmpeg sin permisos de admin | ❌ (asume entorno preparado) | ✅ | Onboarding inmediato en Windows corporativo con permisos restringidos. |
| Open source / código abierto | ❌ propietario | ✅ MIT | Auditás, modificás, no dependés de un vendor. |
| Requiere SDK embebido en el juego | Opcional (sin SDK pierde detalle) | Nunca | Cero fricción de integración. No tocamos el juego. |
| Cloud / SaaS / dashboard equipos | ✅ | ❌ | Si tu equipo necesita workspace compartido en la nube, GameBench tiene; nosotros somos local-first por diseño. |
| Single Sign-On, RBAC, on-prem enterprise | ✅ | ❌ | Scope distinto. No es nuestro mercado. |

---

## 3. Donde GANAMOS

- **Open source MIT y gratis** — sin paywall, sin login, sin dependencia de vendor.
- **Local-first** — los builds prelanzamiento nunca salen de tu máquina. Crítico para privacidad y NDA.
- **Detección automática de anuncios/IAP/loading** sin que el juego coopere. GameBench requiere SDK o marcadores manuales.
- **Métricas filtradas** — separamos el rendimiento del juego del rendimiento del anuncio. GameBench los suma y promedia juntos.
- **Conclusiones cualitativas deterministas** — 8 reglas que diagnostican causa-raíz en lenguaje humano. GameBench te da datos y tú los descifras.
- **Nota proporcional al género y target FPS** del juego. GameBench no opina; nosotros sí, con criterio explícito.
- **Temperatura piel vs chip** diferenciada con detección por fabricante de SoC.
- **Video del gameplay sincronizado con timeline**. GameBench solo screenshots periódicas.
- **Archivo `.gameperf` portable** — sharing sin login del receptor.
- **Reporte HTML/PDF auto-contenido** — fuera del paywall.
- **Auto-instalación de dependencias sin admin** — onboarding 0-touch.

## 4. Donde PERDEMOS

| Gap | Severidad | ¿Qué significa para ti? |
|---|---|---|
| Frecuencia de GPU + sub-counters del driver (vertex / fragment / texture) | MEDIO | Desde v4.5.0 ya medimos % de uso de la GPU vía kernel sysfs (Mali + Adreno). Lo que aún nos falta es la frecuencia por dominio y los sub-counters del driver gráfico — eso solo se obtiene con un SDK embebido en el juego (GameBench Pro). Para juegos hipergráficos donde se necesita ese detalle, GameBench sigue ganando. |
| Red por conexión + tiempo al primer byte | **CRÍTICO** | Si te importa el detalle de cada llamada de red (anuncios que tardan, multiplayer), GameBench desglosa y nosotros no medimos red. |
| iOS production-ready end-to-end | ALTO | Para QA exclusivo iOS hoy, GameBench está más maduro. |
| Batería real en mA/mW | ALTO | Si tu KPI es duración de batería, GameBench te da el dato directo. |
| API REST + integración CI/CD | MEDIO | Si tu pipeline automatiza capturas, GameBench encaja; nosotros aún no. |
| Comparación build-over-build (trends) | MEDIO | Para detectar regresiones entre versiones del juego, GameBench tiene gráficos longitudinales; nosotros aún no. |
| Tags libres y búsqueda entre sesiones | MEDIO | Si manejás docenas de sesiones, GameBench las organiza mejor. |
| Frecuencia por núcleo de CPU | MEDIO | Útil para juegos multi-thread donde quieres saber qué núcleo se satura. |
| Consumo de CPU del medidor (3.8% vs ~10-15%) | MEDIO | Nuestro medidor pesa más sobre el dispositivo. Mejorable. |

---

## 5. Roadmap recomendado

| Categoría | Item | Esfuerzo | Impacto |
|---|---|---|---|
| ✅ Shipped v4.5.0 | Medir GPU en Android (Mali + Adreno por contadores del sistema) | Bajo | **Crítico** (cerró el gap #1) |
| Quick wins | Frecuencia por núcleo de CPU | Muy bajo | Medio |
| Quick wins | Consumo de batería real en mA | Muy bajo | Alto |
| Quick wins | Export CSV/JSON desde la sesión | Bajo | Medio (CI-friendly) |
| Quick wins | Modo CLI headless (`--device X --pkg Y --duration N --output report.html`) | Bajo | Alto (cubre integración CI sin necesidad de API REST/servidor) |
| Medium | Trends build-over-build con historial local indexado | Medio | Alto |
| Medium | Tags libres y búsqueda entre sesiones | Bajo | Medio |
| Medium | iOS Phase 1 completar features (temperatura piel, foreground app, IAP) | Medio | Alto |
| Medium | Captura paralela en el medidor para bajar overhead a 1-2% | Medio | Medio |
| Medium | Red básica (ancho de banda agregado por proceso) | Medio | Medio (cubre 80% del caso) |
| Long | Red detallada por conexión + tiempo al primer byte | Alto | Alto (requiere SDK opcional) |
| Long | Detección de anomalías y alertas de regresión sobre trends | Alto | Medio |
| Skip | SaaS cloud dashboard | — | Modelo de negocio distinto. No es nuestro mercado. |
| Skip | SSO/SAML/RBAC/on-prem enterprise | — | Single-user app, no team. |
| Skip | Unity/Unreal SDK explícito | — | Duplicaría el modelo de GameBench sin valor incremental. |

---

## 6. Posicionamiento

> **GameBench te da datos. GamePerf te dice qué hacer con esos datos — y es tuyo, en tu máquina, sin login.**

Ventajas estructurales que GameBench no va a copiar por incentivos de negocio:

- **Open source y local-first** — su revenue depende del SaaS.
- **Auto-detección sin SDK** — eliminaría su vendor lock-in.
- **Conclusiones cualitativas** — bajaría el margen de su consultoría de QA.
- **Nota opinada por género** — los compromete con cada estudio que les paga.
- **Catálogos abiertos extensibles** (SDKs, thermal sensors, devices) — su base de devices es cerrada y monetizada.
- **Gratis para indies** — su mercado son AAA studios.

---

[^gpu]: **GPU usage v4.5.0 — diferencias técnicas con GameBench.** Lo que tenemos: porcentaje de uso de la GPU leído desde el kernel sysfs por adb (Mali via `/sys/class/misc/mali0/device/utilization` HIGH-confidence; Adreno via `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` HIGH; fallback Adreno via delta de `gpubusy` busy/total counters). Sin root, sin SDK embebido en el juego, sin link en compilación. Cubre Mali (ARM) y Adreno (Qualcomm). Lo que NO tenemos: (a) frecuencia de la GPU por dominio (shader core, MMU, L2) — eso lo lee GameBench desde el SDK que se embebe en el juego usando Mali kbase ioctls + DB de clocks máximos; (b) sub-counters del driver (vertex / fragment / texture / compute) — mismo motivo; (c) PowerVR (MediaTek / Unisoc); en lugar de ocultar la métrica mostramos un banner invitando al crowdsource. **Caveat de atribución foreground-app**: el sysfs reporta el uso TOTAL de la GPU, no el del proceso. Lo atribuimos al juego porque la única app en foreground bajo carga gráfica suele ser él; en multi-window o con overlays activos esta atribución se rompe. GameBench, al hookear desde el SDK del juego, no tiene este caveat. **Caveat de warm-up Adreno**: en chipsets Adreno (Qualcomm) los contadores de rendimiento necesitan ~4 s desde el primer poll antes de mostrar un valor estable; los primeros 1-2 puntos del gráfico pueden aparecer en 0% por esta razón. **Caveat de side-effects**: para habilitar los contadores Adreno emitimos `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` al inicio de la sesión y `echo 0 > ...` al final (en `resetSessionState()`), para no dejar el contador activo entre sesiones. En OEMs locked este enable falla silenciosamente y el banner explica por qué.

## Apéndice — Para devs del repo

### Estructura interna relevante

Paquetes Kotlin clave en `core/`:
- `bridge/` — `DeviceBridgeApi` + `AndroidBridge` + `CompositeBridge` (multi-plataforma).
- `ios/` — `SidecarClient` + `SidecarLifecycle` + `IosBridge` (HTTP a sidecar Python pymobiledevice3).
- `events/` — `EventDetectorImpl` + `LogcatCapture` + `DumpsysPoller` + `SdkSignatureCatalog` (6 SDKs: AdMob, UnityAds, IronSource, AppLovin, Meta AN, Play Billing). v4.4.0.
- `metrics/` — `FilteredMetricsCalculator` + `TimeRange` (v4.4.0 dual-view metrics, padding ±500 ms).
- `conclusions/` — `ConclusionEngine` + `RuleRegistry` + 8 reglas deterministas (v4.4.0).
- `grading/` — `FinalScoreCalculator` (extraído v4.3.4, 29 tests).
- `update/` — `UpdateAttempt` + `UpdateHistoryStore` + `HelperLogWatcher` (v4.4.1 resilient autoupdater).
- `AdbBridge` + `AdbBridgeApi` (low-level), `AdbThermalParser`, `ThermalZoneClassifier`, `LayerSelector`, `LastKnownFpsTracker`, `HardwareScoring`, `SessionHistory`, `DeviceNameResolver`, `ToolResolver`, `DependencyBootstrap` + `ToolInstaller` + `Downloader` + `UserToolsDir` (v4.3.5 auto-install), `AutoUpdater`.

Stack: Kotlin 1.9.22, Compose Desktop 1.6.1, JDK 17, coroutines 1.7.3, detekt 1.23.7. Heap cap `-Xmx2048m`. **772 tests passing / 0 failing / 10 ignored** (per v4.4.1 CHANGELOG).

### Detalles técnicos por métrica

- **FPS**: medimos `dumpsys SurfaceFlinger --latency <layer>` con `LayerSelector` rank por sufijo `#N` + `LastKnownFpsTracker` para sticky durante transición de anuncio. GameBench hookea framebuffer producer/consumer desde SDK; sin SDK usa el mismo `SurfaceFlinger`.
- **CPU**: `/proc/<pid>/stat` per-process (desde v4.2.5 — antes leíamos `/proc/stat` device-wide, ver CLAUDE.md). Misma fórmula que GameBench.
- **GPU GameBench**: Mali kbase ioctls + Adreno `/sys/class/kgsl/kgsl-3d0/perfcounter` + DB de clocks máximos. Adreno en Android 13+ requiere comando `adb shell "echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter"`. PowerVR no soportado.
- **GPU GamePerf (v4.5.0)**: kernel sysfs via adb, sin SDK. `core/GpuVendorCatalog.kt` lista candidates ordenados: Mali utilization HIGH → Adreno `gpu_busy_percentage` HIGH → Adreno `gpubusy` delta-counters HIGH → PowerVR LOW placeholders. `core/GpuUsageParser.kt` puro (sin I/O) parsea Mali int 0-100 + Adreno gpu_busy_percentage int + Adreno gpubusy delta math con reject de wraparound y zero-delta. `core/AdbBridge.kt::captureGpuUsage` probe-one-shell-command + cache per-device + lifecycle Adreno perfcounter enable/disable en `resetSessionState()`. 5 GpuUnavailableReason mapping a banners castellano tuteo-formal. 82 tests TDD red→green. Foreground-app attribution caveat + Adreno warm-up footnote documentados en el reporte HTML.
- **Memoria**: `dumpsys meminfo` (PSS). Idéntico a GameBench Android.
- **Thermal**: vendor-aware via `ThermalZoneClassifier` con allow-list explícita por SoC family (Snapdragon, Tensor, Unisoc) — fix de v4.3.6 contra substring matching.
- **Red GameBench**: hooks a libs de red OS-level + `NSURLSessionTaskTransactionMetrics` en iOS; `TrafficStats` + intercept de C-level socket APIs para per-connection en Android. Imposible de replicar sin JNI hooks o SDK opcional.
- **Battery GameBench Android**: `BatteryManager` voltaje/draw/charging. Nosotros solo `dumpsys battery` (level + temp). `current_now` está disponible en `dumpsys battery` — quick win.
- **Overhead**: GameBench mide 3.8% en Pixel 6 (Tensor, Mali-G78) USB con todas las métricas (baseline 24.4% → 28.1%). Engram obs #67 estimó ~10-15% para nosotros por loop secuencial `dumpsys SurfaceFlinger` + `/proc/stat` + `dumpsys meminfo` + sysfs thermal. Captura paralela en `AppViewModel.startCapture` cerraría el gap.

### Endpoints GameBench API REST referenciados

`/v1/sessions/{id}/fpsStability`, `/v1/sessions/{id}/frametimes`, `/v1/sessions/{id}/corefreq`, `/v1/sessions/{id}/android-memory`, `/v1/sessions/{id}/energy`, `/v1/sessions/{id}/power`, `/v1/sessions*` (token-based).

### Fuentes

GameBench docs (2026-05-11): `docs.gamebench.net/docs/` (home), `studio-pro-desktop/getting-started/`, `studio-pro-desktop/overhead/` (overhead Pixel 6 medido), `sdk/overview/`, `sdk/metrics/`, `web-dashboard/session-detail/`, `web-dashboard/api/`, `web-dashboard/account-settings/sso-configuration/`, `enterprise/`, `studio-pro-desktop/relnotes/` (v1.37.2).

Repo local: `README.md`, `CHANGELOG.md`, `build.gradle.kts`, `gradle.properties` (v4.4.1), estructura completa de `src/main/kotlin/com/gameperf/desktop/`, engram observations #14, #15, #18, #23, #24, #26, #43, #48–50, #53–57, #67, #69, #71, #82, #84, #99–104.

[^net]: **Network bandwidth v4.6.0 - diferencias tecnicas con GameBench.** Lo que tenemos: total de bytes RX (descarga) y TX (subida) que consume el proceso del juego durante la captura, leidos via service call netstats (binder Android, transaction codes [11, 12, 14, 15] HINT-confidence) con fallback automatico a dumpsys netstats detail --uid cuando el binder no responde. Sin root, sin SDK embebido. Probe-once-then-cache: primera captura >=2 shell calls walkeando el catalogo, capturas siguientes 1 shell call solo. Lo que NO tenemos: desglose por endpoint, TTFB por endpoint, headers/payloads (esos requieren hook libc/eBPF o proxy MITM). 5 motivos de fallo reportados: BINDER_UNAVAILABLE, DUMPSYS_PERMISSION_DENIED, ALL_PROBES_FAILED, IMPLAUSIBLE_VALUE (NET-010 window [0, 100 GB]), CAPTURE_THREW.
