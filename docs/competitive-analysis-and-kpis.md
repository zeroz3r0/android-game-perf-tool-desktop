# Análisis Competitivo + Framework de KPIs de Rendimiento

**Proyecto**: android-game-perf-tool-desktop
**Fecha**: 2026-05-12
**Estado del documento**: Investigación consolidada el 2026-05-12, decisiones de producto pendientes en §8
**Responsable**: TBD

---

## 0. Resumen ejecutivo

Este documento consolida 7 investigaciones paralelas + 1 auditoría interna en una única referencia de apoyo a la decisión para el proyecto android-game-perf-tool-desktop. Cubre:

- **Posicionamiento competitivo** frente a 5 competidores directos (GameBench, PerfDog, Snapdragon Profiler, ARM Streamline, Unity Profiler, Android Studio Profiler) y 5 herramientas APM/RUM (Firebase Perf, Sentry, New Relic, Embrace, Android Vitals).
- **KPIs estándar del mercado** procedentes de URLs autoritativas: Google Android Vitals (impacto en el ranking de Play Store), Google RAIL, presupuesto de lanzamiento de Apple iOS (WWDC 2019). Las certificaciones de consola y los PDFs de motores/proveedores quedan marcados explícitamente como **no verificados** en esta pasada.
- **Segmentación de eventos** para 8 fases de juego: matriz de cobertura actual de la herramienta + comparativa con el estado del arte + plan de detección en 3 niveles.
- **Framework de puntuación de KPIs**: 23 KPIs en un catálogo maestro, mapa de relevancia por fase, 3 modelos de puntuación comparados (recomendación: Modelo A Lineal anclado en los umbrales de Android Vitals).
- **Diseño de informe HTML compartible**: patrones a copiar de Notebookcheck / Android Authority / GSMArena, anti-patrones a evitar, huecos de mercado que podemos rellenar.
- **9 decisiones de producto pendientes** (§8) y **7 próximos cambios SDD** priorizados por esfuerzo (§9).

Notas de transparencia preservadas: las certificaciones de consola están sujetas a NDA, los PDFs de motores/proveedores requieren descarga manual y la auto-detección de SDKs de anuncios más allá de los 6 SDKs ya presentes en el catálogo está **no verificada** y necesitaría un laboratorio empírico de captura.

---

## 1. Por qué existe este documento

- **Objetivo de producto**: posicionar nuestra herramienta frente a GameBench, PerfDog y las APM/RUM. Clarificar dónde competimos, dónde complementamos y dónde tenemos valor único.
- **Objetivo del sistema de puntuación**: entregar puntuaciones de rendimiento por juego ancladas en KPIs objetivos y defendibles, procedentes de guías oficiales (Google Vitals = ranking de Play Store; RAIL = percepción; Apple = presupuesto de arranque).
- **Objetivo de segmentación**: desglose de métricas por fase (arranque en frío vs gameplay vs anuncios vs cargas). Una única media de FPS sobre una sesión de 10 minutos esconde los problemas que de verdad importan.
- **Decisiones que este documento desbloquea**: ver §8.

---

## 2. Panorama competitivo

### 2.1 Competidores directos (perfiladores de rendimiento de juego)

| Herramienta | Mecanismo | Cobertura de proveedores | Precio | Tasa de muestreo | Segmentación | Sobrecoste | Requiere cloud | Brecha respecto a nosotros |
|------|-----------|-----------------|-------|---------------|--------------|----------|----------------|-----------------|
| **GameBench** | `.so` nativo en el dispositivo que lee perfcounters del driver + SDK/Injector | Mali + Adreno (~90% del mercado Android GPU). PowerVR explícitamente NO soportado. | Suscripción (pago). Pro Android Lite gratuito para indies. | 1 Hz fijo | Marcadores programáticos (SDK) + protocolo de logcat `gb_marker_start - <name>` / `gb_marker_stop - <name>` | 3.8% CPU en perfilado completo en Pixel 6 (según docs); 0.5% CPU con SDK en Subway Surfers S24U | Web Dashboard en la nube por defecto; tier enterprise self-hosted | Uso de GPU% (Sprint 1 en curso), ancho de banda de red, marcadores programáticos vía SDK |
| **PerfDog (Tencent/WeTest)** | Daemon PerfDog Service en el dispositivo + cliente adb en el host. Plug-and-play, sin SDK ni root. Sincronización obligatoria con la nube. | 11 plataformas: Android, iOS, Win, Switch, VR (Quest/Pico), Wear (multiplataforma, agnóstico al proveedor) | Gratuito para uso no comercial / investigación; comercial vía contacto de ventas (perfdog_net@tencent.com, precios bajo NDA); suscripción mensual en RMB | 1 Hz por defecto, configurable | Custom Data Extension SDK (instrumentado, 7 lenguajes, ~20k llamadas/seg, máximo 50 métricas) + anotaciones Tags + Scenes | <1% CPU según marketing (NO auditado de forma independiente) | **SÍ, OBLIGATORIO**: sube a perfdog.qq.com (China) o perfdog.wetest.net (internacional). Los silos de datos están separados por cumplimiento normativo, sin intercambio entre ellos. Requiere cuenta de Tencent. Metodología cerrada. | **Cerrable** (planificado): FPower (§9 #8), CPU% normalizado por frecuencia (§9 #9), fórmula Jank (§9 #10), CLI/headless (§9 #11). **Explícitamente NO vamos a cerrar**: contadores HW de GPU (alianzas con proveedores), dashboard en la nube (anti-posicionamiento), métricas SDK de motor (anti-no-SDK), RUM en producción, latencia de toque, biblioteca de 200k apps de benchmark. También únicos en ellos: GUI multi-dispositivo ≤3 (planificamos paridad + ilimitado vía CLI). |
| **Snapdragon Profiler (Qualcomm)** | Escritorio + adb + perfcounters del driver Qualcomm Adreno | **SÓLO SNAPDRAGON** (Adreno). Inútil en Mali / PowerVR / Xclipse. | GRATIS (cuenta dev de Qualcomm) | Contadores HW | Captura de frame (GL/Vulkan), system trace | Despreciable (contadores HW) | NO (local) | Bloqueado por proveedor, deep-dive de dev, no es un harness de QA. Necesita `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter` en Android 13+ |
| **ARM Streamline (Arm Performance Studio)** | Agente gatord dentro de un wrapper APK, o fuentes de datos Perfetto en Mali sin root | **SÓLO MALI**. ~50% del mercado. | GRATIS (cuenta dev de ARM) | Contadores HW | A nivel de código fuente | Despreciable | NO (local) | Espejo bloqueado por proveedor de Snapdragon Profiler. Particularidades del sampler de Mali según generación |
| **Unity Profiler + Profile Analyzer** | Integrado DENTRO del motor Unity. Requiere build de desarrollo O IL2CPP+flag de desarrollo | **Sólo juegos Unity**. No perfila Unreal/native/Cocos/Godot. | GRATIS (licencia de Unity) | Marcadores de motor por frame | ProfilerMarker (`Profiler.BeginSample`/`EndSample`) | Coste de integración del motor | NO | Bloqueado por motor, requiere build de desarrollo (NO un APK distribuible), sin coste a nivel de dispositivo (temperatura, reloj GPU, memoria del sistema, batería) |
| **Android Studio Profiler (Google)** | adb + Perfetto + Simpleperf; dentro del IDE | Todo Android | GRATIS | System trace | Eventos de trace, atrace | adb estándar | NO | (1) Requiere `debuggable=true` (no perfila APKs release distribuidos). (2) Vinculado al IDE, sin modo headless/CI. (3) Sin comparativa de sesiones largas. (4) Sin dashboard de equipo. (5) Foco en una sola app, no flota/laboratorio. |
| **Nuestra (GamePerf Desktop v4.4.1)** | Sólo adb en el lado del host. Lecturas puras de /sys + dumpsys + logcat. Sin instalación en el dispositivo. | CPU/RAM/térmica/FPS multi-proveedor ya hechos. GPU Mali+Adreno **pendiente del Sprint 1** (en pausa). PowerVR candidato a Sprint 1.5. | Gratis / licencia interna MIT | Línea base 0.5-1 Hz | Detección automática de eventos (6 SDKs: AdMob/UnityAds/IS/AppLovin/Meta/PlayBilling) + marcadores manuales (5 tipos) | Objetivo <2% CPU del host (1-2% medido); sobrecoste del dispositivo TBD | **NUNCA en la nube.** Sin cuenta. 100% local. | GPU usage% (Sprint 1), ancho de banda de red (Sprint 2), CPU por core con frecuencia, mA de batería, tendencias entre builds, CLI headless |

**Conclusiones transversales** (de obs #303 + #298 + #288):

1. **PerfDog es la única herramienta multi-proveedor + multi-plataforma estilo adb** del conjunto. El competidor funcional más cercano junto a GameBench.
2. **Snapdragon Profiler y ARM Streamline son herramientas de deep-dive de dev bloqueadas por proveedor**, no harnesses de QA. Aportan detalle de contadores HW que no podemos replicar sólo con adb: complementarias, no competidoras.
3. **Unity Profiler y Android Studio Profiler son herramientas de motor/dev que requieren builds de desarrollo.** Inutilizables en builds release distribuidos. Esta es la razón #1 por la que GameBench/PerfDog tienen mercado.
4. **Ninguna de las herramientas gratuitas** (Android Studio, Snapdragon, ARM Streamline, Unity) **ofrece una vista multi-sesión/multi-dispositivo de flota.** Es feature de herramientas de pago. Nuestra herramienta puede acertar el punto justo: sesiones locales guardadas en disco + comparación lado a lado, sin nube.
5. **Las 5 requieren un coste de configuración**. Sólo adb con `pm list packages` para elegir cualquier app instalada en cualquier dispositivo = la fricción de configuración más baja posible.

**Nuestro foso de posicionamiento**:
- **Soberanía del dato** (sin nube, sin cuenta) — gana a PerfDog, gana a GameBench con nube por defecto.
- **Transparencia metodológica** — open source MIT, fuentes /sys/dumpsys/Perfetto documentadas. Gana a TODOS los competidores (cerrados).
- **Perfilado de APK release** (sin flag debuggable) — gana a Unity y Android Studio Profilers. Paridad con PerfDog/GameBench.
- **Detección automática de eventos sin SDK** (v4.4.0, 6 SDKs en catálogo) — GameBench requiere SDK o marcadores manuales.
- **Capacidad CI/headless** (planificado) — gana a AS Profiler y Unity Profiler.
- **Calificación consciente del hardware + conclusiones cualitativas** (v4.4.0) — GameBench da datos, nosotros te decimos qué hacer.

Observaciones de referencia: `research/competitive-analysis-direct-tools` (#303), `research/gamebench-comparison` (#288), `research/gamebench-docs-gpu-section` (#298), `roadmap/gamebench-parity` (#289).

### 2.2 Herramientas APM / RUM adyacentes (monitorización en producción)

| Herramienta | Mecanismo | Vocabulario de KPIs expuesto | ¿Umbrales publicados? | Segmentación | ¿Específica de juegos? |
|------|-----------|------------------------|------------------------|--------------|----------------|
| **Google Play Console — Android Vitals** | Telemetría a nivel de SO, sin SDK | Tasa de ANR, tasa de crash, arranque lento en frío/templado/caliente, frames lentos (>16ms), frames congelados (>700ms), sesión lenta 30/20 FPS, wake locks, wake-ups, red en background | **SÍ — autoritativos** (ver §3.1) | Por modelo de dispositivo, por versión | SÍ (umbrales de FPS específicos para juegos) |
| **Sentry Performance / Mobile Vitals** | SDK | TTID (Time to Initial Display), TTFD (Time to Full Display, opt-in), Cold/Warm App Start (sin hot), Frames lentos (auto-ajusta a 60/120Hz), Frames congelados, Frames Delay | SÍ — Cold start Bueno <3s / Regular 3-5s / Malo >5s; Warm <1s / 1-2s / >2s; Frame lento >16ms@60fps, >8.33ms@120fps; Congelado >700ms | Por transacción (root-span = nombre de transacción), hasta 10 mediciones custom por transacción | Limitada |
| **Firebase Performance Monitoring** | SDK (`Performance.startTrace(name)` / `trace.stop()`) | Frames lentos (>16ms, malo si >50% son lentos), Frames congelados (>700ms, malo si >0.1%), Traces automáticos `_app_start`/`_app_in_foreground`/`_app_in_background`, renderizado por pantalla. Traces custom (máx 5 atributos, 32 métricas incluida la duración). | **No publica umbrales de bueno/malo** — sólo línea base histórica | Traces custom; aviso explícito: "Evita crear traces de código custom a alta frecuencia (por ejemplo, una vez por frame en juegos)" | No (móvil en general) |
| **New Relic Mobile** | SDK | Tiempos de lanzamiento, análisis de crashes, red. Cita a Google + Apple textualmente. | Cold <5s (Google), Hot <1.5s (Google), Apple cold ≤400ms / límite duro 20s | Por pantalla, por interacción | No |
| **Embrace** | SDK | Arranque, tasa de crash, tasa de ANR, región, duración de sesión, churn/retención, terminación por el usuario, acciones clave, memoria, conectividad | NO hay umbrales fijos — filosofía de segmentación (segmentar por usuarios de alto valor / región / dispositivo) | Por evento/por pantalla | Algún soporte para juegos |

**Vocabulario universal de eventos** entre las 5 herramientas APM/RUM (obs #304):
- **Ciclo de vida**: arranque en frío/templado/caliente, foreground, background, inicio/fin de sesión, terminación
- **Renderizado**: frame lento, frame congelado, frame delay, TTID, TTFD, sesión lenta (FPS para juegos)
- **Estabilidad**: crash, crash percibido por el usuario, ANR, ANR percibido por el usuario, LMK, OOM, excepción manejada
- **Pantalla**: vista de pantalla/carga/transición, inicio de Activity
- **Red**: inicio/fin HTTP, 4xx, 5xx, petición lenta
- **Recursos**: presión de memoria, wake lock, wake-up, escaneo Wi-Fi, red en background, batería
- **Usuario**: permiso concedido/denegado, acción custom del usuario, paso del journey

**Conclusiones estratégicas para nuestra herramienta** (obs #304):
1. Adoptar los umbrales de Google Play Console textualmente: son autoritativos + están atados a la visibilidad en Play Store.
2. Usar la tricotomía de arranque cold/warm/hot: es universal.
3. Presupuesto de frame: 16ms @60Hz, 8.33ms @120Hz, 33ms @30Hz (juegos).
4. Distinguir TTID vs TTFD (Sentry es el diferenciador).
5. El filtro "percibido por el usuario" para crashes/ANRs es crítico.
6. Exponer las sub-métricas de renderizado de Google (Vsync, latencia de input, hilo UI, draw, subida de bitmap).
7. La puntuación específica de juegos basada en FPS (suelo de 30 FPS, mínimo 20 FPS) es el estándar de Play Console.

Observación de referencia: `research/competitive-analysis-apm-rum` (#304).

### 2.3 Cómo la prensa especializada en videojuegos reporta el rendimiento (informes liderados por gameplay)

Las reseñas públicas de teléfonos son la referencia de rendimiento más leída por los usuarios finales. Aunque su público principal son compradores y no desarrolladores, anclan las expectativas del público sobre qué se considera "buen rendimiento" para un determinado tier de dispositivo. Eso a su vez moldea lo que los laboratorios de QA y los estudios de juego acaban teniendo que defender. Estos medios pasan revisión por pares en sus hilos de comentarios (GSMArena recibe rutinariamente 200-300+ comentarios por reseña de buque insignia), así que su metodología, aunque informal, es lo más cercano a un estándar aceptado por la comunidad para benchmarks de gaming móvil fuera de los laboratorios financiados por proveedores. Entender su vocabulario de reporte nos dice frente a qué KPIs y visualizaciones se va a medir nuestro propio informe HTML cuando lo lean no-desarrolladores.

Esta sección cataloga **6 medios**, con URLs de muestra fechadas, enfocados en el rendimiento de juego móvil Android (no portátiles/GPU). §2.4 abajo cubre la prensa pública de benchmarks de forma más amplia.

| Medio | KPIs típicos reportados | Estilos de gráficas | ¿Segmentación por fase? | ¿Herramienta divulgada? | Artículo de muestra (fecha de acceso) |
|--------|----------------------|--------------|----------------------|------------------|------------------------------|
| **GSMArena** | GeekBench 6 single + multi, AnTuTu v10 + v11, 3DMark Wild Life Extreme (Highest), 3DMark Solar Bay (Ray Tracing). Capturas de pantalla de stress test CPU + GPU durante ~20 min. | Comparativas en barras horizontales vs 6 dispositivos pares por benchmark; cada fila anotada con SoC + RAM + resolución nativa. Rendimiento sostenido mostrado como **captura de pantalla del gráfico del stress test in-app** (no interactivo). Veredicto en prosa sobre thermal throttling. | **No** hay división por fase de gameplay real. Sólo bucketeo por benchmark sintético (CPU vs GPU vs ray-tracing). | Herramientas sintéticas nombradas (Geekbench, AnTuTu, 3DMark, stress tests in-app). **Herramienta de FPS in-game no divulgada** — sin números de gameplay por título en la sección de rendimiento. | Samsung Galaxy S26 Ultra review, "Software and performance" (página 4 de 6), GSMArena Team, 06 de marzo de 2026. <https://www.gsmarena.com/samsung_galaxy_s26_ultra-review-2939p4.php> (acceso 2026-05-18) |
| **Notebookcheck** | Geekbench 6.6 single + multi, AnTuTu v10, **PCMark for Android**, CrossMark, BaseMark OS II, UL Procyon AI Inference, AImark, Geekbench AI, AI Benchmark. Tiempos de respuesta de pantalla rise/fall en ms. Parpadeo PWM (Hz). iperf3 Wi-Fi. Puntuación compuesta "very good (89%)". | Comparativas en barras vs predecesor + media de clase + 4 competidores nombrados, cada uno con delta-% explícito. **Badge de premio descargable en SVG/PNG** para la calificación. Tablas detalladas por benchmark. | Por benchmark, no por gameplay. Las reseñas centradas en cámara (la X300 Ultra está enfocada en cámara) pueden omitir FPS dedicado por juego. | Todas las herramientas sintéticas nombradas. **GameBench se nombra explícitamente en sus secciones de FPS por título** (en reseñas no centradas en cámara — ver §2.4 referencia y obs #305). La sección de FPS de gameplay real está ausente aquí. | Vivo X300 Ultra review, Marcus Herbrich, publicado 2026-05-12, actualizado 2026-05-15. <https://www.notebookcheck.net/Vivo-X300-Ultra-Review-Best-2026-camera-smartphone-with-a-surprising-number-of-weaknesses.1293093.0.html> (acceso 2026-05-18) |
| **Android Authority** | Geekbench 6 (CPU), 3DMark Solar Bay (ray tracing), **3DMark Wild Life Extreme Stress Test (20 loops, gráfica de líneas)**, drenaje de batería emparejado entre reproducción 4K / grabación 4K / cámara. Gráfica de curva de carga (tiempo vs %). | Gráficas de barras para pico; **gráfica de líneas para el stress test mostrando 20 loops con varios trazos de dispositivo superpuestos** (S26 Ultra vs OnePlus 15 vs serie iPhone 17 vs Pixel 10 Pro). Barras de tiempo de carga. | **Sólo por tipo de uso** (reproducción 4K vs grabación 4K vs cámara) en la sección de batería. Sin división por fase de gameplay. El rendimiento sostenido se modela como tiempo en test, no como cambio de escena in-game. | Herramientas sintéticas nombradas. **Sin mediciones de FPS in-game publicadas** en esta reseña. | Samsung Galaxy S26 Ultra review, Ryan Haines, 31 de marzo de 2026. <https://www.androidauthority.com/samsung-galaxy-s26-ultra-review-3652705/> (acceso 2026-05-18). Deep-dive de benchmarks vinculado: `samsung-galaxy-s26-ultra-benchmarks-3652232`. |
| **Tom''s Guide** | Vida de batería, rendimiento (sintético), pantalla (tasa de refresco, brillo, pico de nits) como los tres pilares para la evaluación de teléfono gaming. Veredicto subjetivo por teléfono sobre "sensación" jugando. | Formato "best-of" — puntuación comparativa entre categorías con justificación en prosa. Tarjetas de especificaciones por dispositivo. | **Sin segmentación por fase en la sección de rendimiento.** Ejemplos de juego citados anécdoticamente (e.g. *Ex Astris* mostrado en ROG Phone 9 Pro). | Metodología referenciada en la sección "How we test gaming phones" pero la herramienta concreta de captura de FPS no se nombra en el cuerpo del artículo. | "The best gaming phone 2026 — I tested them all to crown a winner", Richard Priday, última actualización 30 de marzo de 2026. <https://www.tomsguide.com/best-picks/best-gaming-phones> (acceso 2026-05-18) |
| **XDA Developers** | Formato de roundup "mejores teléfonos gaming": especificación de SoC, Hz de pantalla, RAM, almacenamiento, batería como volcado de specs en crudo. Hardware específico para gaming (gatillos, ventiladores, RGB) destacado cualitativamente. | Tarjeta-por-dispositivo con pros/cons. Sin gráficas comparativas de benchmark en el formato roundup. | **Sin segmentación por fase.** Sin FPS por juego ni datos por escena en el roundup. | **Metodología no divulgada** en el roundup; herramientas de medición de FPS no nombradas. | "Best gaming phones in 2024", Ryan-Thomas Shaw, última actualización significativa Nov 2023 (artículo aún surge como vigente). <https://www.xda-developers.com/best-gaming-phones/> (acceso 2026-05-18). **Necesita verificación** — las reseñas individuales por teléfono de XDA (e.g. ROG Phone 9 Pro) devolvieron 404 en URLs directas durante la investigación; piezas recientes dedicadas al rendimiento de teléfonos por XDA no se pudieron confirmar en 2 intentos. |
| **AnandTech (archivado)** | **Necesita verificación — el sitio está en modo sólo archivo desde 2024; no surgieron reseñas recientes de rendimiento gaming en 2 intentos.** Los archivos históricos son conocidos por sus deep-dives rigurosos de microarq CPU/GPU con datos de contadores HW; la cobertura de FPS de gaming móvil fue menos consistente. | Patrón histórico: prosa detallada por test + gráficas de líneas por benchmark. | Histórico: por benchmark, no por fase de gameplay. | Histórico: herramientas de proveedor (estilo Snapdragon Profiler) nombradas cuando se usaban. | Sólo archivo; sin URL fresca capturada en esta pasada. |

**Patrones que podemos copiar** (portar a nuestro informe HTML):

- **`Ø avg (min-max)` como abreviatura para FPS** (convención de Notebookcheck, confirmada en obs #305 previa) — la mayor cantidad de información por pixel de cualquier visualización de framerate vista en medios.
- **Gráfica de líneas emparejada de puntuación + temperatura en un eje de tiempo compartido** (estilo de overlay de stress test de 3DMark de Android Authority) — la causalidad entre envoltorio térmico y colapso de rendimiento se ve de un vistazo.
- **Anotaciones por fila de dispositivo en las barras de comparación** (etiquetas "SoC + RAM + resolución nativa" de GSMArena) — los lectores entienden de inmediato por qué dos dispositivos puntúan distinto.
- **Overlay multi-competidor en rendimiento sostenido** (Android Authority superpone 4 dispositivos en la misma curva de stress de 20 loops) — convierte "¿está este dispositivo haciendo throttle?" de anécdota a ranking.
- **Puntuación compuesta + barras de categoría explícitas** (cabecera "very good (89%)" de Notebookcheck + barras por categoría debajo) — da tanto un número único compartible como un desglose auditable. Ya tenemos calificación consciente del hardware + superficie de categorías por KPI; formalicemos la doble presentación.
- **Badge descargable de calificación** (premio SVG/PNG de Notebookcheck) — convierte el informe en contenido social compartible para estudios de juego que distribuyen nuestras puntuaciones externamente. Feature de bajo coste, alto apalancamiento de marketing.
- **Bloque de divulgación de metodología de herramientas** (Notebookcheck nombra explícitamente cada benchmark usado, con versión) — nuestros informes ya rastrean la procedencia interna de herramientas; mostrarlo visiblemente convierte la transparencia de hecho arquitectónico en señal de confianza.

**Anti-patrones a evitar**:

- **Medias de un solo número sobre sesiones largas** (universal en los 6 medios) — esconde justamente el problema de segmentación que el detector de eventos de v4.4.0 existe para resolver. Nuestro informe DEBE tener por defecto división por fase, no una media de sesión entera.
- **Metodología sólo sintética sin números de gameplay real** (GSMArena, reseñas centradas en cámara de Notebookcheck, pieza de Android Authority de la S26 Ultra: todas saltan los FPS in-game por título). 3DMark Wild Life es una gran sonda de peor caso pero no es como se comporta realmente el juego que tu equipo de QA está probando.
- **Herramientas no divulgadas para la parte de gameplay** (Tom''s Guide, roundups de XDA) — los lectores no pueden reproducir ni auditar el resultado. Nuestra metodología abierta + spec hospedada en GitHub es el anti-posicionamiento inverso.
- **Gráficas de stress sólo en captura de pantalla** (el stress test CPU/GPU de GSMArena es una captura de un gráfico in-app) — no interactivo, no zoomable, sin ancla temporal. Nuestro player de timeline y exportación CSV cierran esta brecha.
- **Tiers de dispositivo no coincidentes sin etiquetas explícitas de tier** (todos los medios comparan flagship-a-flagship implícitamente pero nunca anclan "FPS esperado para tier X"). Nuestra calificación consciente del hardware explícitamente normaliza por clase de SoC — formalícemoslo en la cabecera del informe.

Ver §5 (catálogo de KPIs) para cómo estos patrones de reporte mapean a qué KPIs específicos ya exponemos, y §7 (informe HTML compartible) para el slot de implementación.

### 2.4 Prensa pública de benchmarks (inspiración para nuestro informe compartible)

| Fuente | Métricas mostradas | Tipos de gráfica | Puntuación | Segmentación | Datos crudos | Lección para nosotros |
|--------|---------------|-------------|---------|--------------|----------|---------------|
| **GSMArena** | GeekBench 6, AnTuTu v10/v11, 3DMark Wild Life Extreme (Highest + Lowest sostenido), 3DMark Solar Bay, % de thermal throttling | Comparativas en barras horizontales vs ~10 dispositivos de referencia + thumbnail/chip/RAM/resolución por fila. Throttling = captura de pantalla. | Numérico crudo, sin letra de calificación. Veredicto en prosa. | Por tipo de benchmark, NO por fase de gameplay | Sólo capturas de pantalla, sin CSV/JSON | Alta densidad de info mediante anotaciones por fila de dispositivo. **Evitar**: gráficas sólo en captura (no interactivas) |
| **Notebookcheck** | 3DMark (8 sub-tests), GFXBench Manhattan/Car Chase/Aztec. FPS de gameplay real vía **GameBench** integrado (nombrado). Formato `Ø60 (59-61)` para media(min-max). | Barras de comparación vs predecesor + media de clase + 4 competidores con delta-%. FPS por juego como gráfica de líneas. | Compuesta "very good (89%)" + badge de premio descargable en SVG/PNG | Por JUEGO (Genshin, PUBG) Y por preset de calidad (Smooth/HD/Ultra) | No descargables, pero tablas inline completas | **Patrón principal a copiar**: abreviatura `Ø avg (min-max)`. **Evitar**: compuesta 89% opaca |
| **Android Authority** | GeekBench 6, PCMark Work 3.0, 3DMark Wild Life Extreme Stress Test (20 runs), 3DMark Solar Bay Stress Test, temperatura pico | Comparativas en barras + GRÁFICAS DE LÍNEAS de stress test por iteración de loop. Gráficas de líneas **emparejadas** de puntuación + temperatura (causalidad de un vistazo) | Deltas-%, sin calificación numérica | Por SKU dentro de la familia | No | **Patrón principal a copiar**: timeline emparejada puntuación+temperatura |
| **Eurogamer / Digital Foundry** | Framerate cappeado vs no cappeado, frame-pacing, frames dropeados por escena, comparación de features visuales vs consola, thermal throttling | Overlay de FPS en tiempo real sobre el gameplay capturado, vídeo lado a lado | Sólo en prosa | SÍ — por FASE de gameplay (interior/exterior, combate/exploración, cinemática). Sello distintivo de DF | Nunca | **Patrón principal a copiar**: segmentación por fase de gameplay |
| **NanoReview** | AnTuTu v11, GeekBench CPU/GPU v6, 3DMark Steel Nomad Light, Cinebench 2024 | Tablas, calificación compuesta de SoC 0-100 | Listas ordenadas, crowdsourced | Ninguna — sólo sintético | Entradas por submisión públicas con timestamps | Lo más cercano al modelo "crudo descargable". Posible **ancla externa para clasificación de tier de dispositivo** (§6.3) |

**Patrones recurrentes en la prensa** (obs #305):
1. Media FPS + mínimo + máximo es universal. `Ø60 (59-61)` de Notebookcheck es la abreviatura más limpia.
2. **Percentiles de frame-time del 1% / 0.1% inferiores son estándar en prensa PC, la prensa móvil se los salta** — oportunidad para nosotros de traer rigor PC al móvil.
3. "Stability %" lo acuñó GameBench y ahora está en todas partes — Notebookcheck literalmente nombra a GameBench en sus reseñas.
4. Curvas de thermal throttling sobre 20+ loops de stress son el estándar para "rendimiento sostenido".
5. Segmentación por preset de calidad común; segmentación por fase de gameplay rara fuera de Digital Foundry (y sólo en YouTube).
6. **Ningún medio publica datos crudos descargables.** Exportar CSV/JSON es un hueco de mercado.
7. Las calificaciones compuestas (Notebookcheck 89%) son editoriales, no basadas en datos — opacas.

**Nuestros 4 diferenciadores (huecos de mercado)**:
- CSV/JSON crudo descargable
- Percentiles de frame-time (p1, p0.1) por defecto — rigor PC en móvil
- Segmentación por fase de gameplay (carga vs combate vs cinemática)
- Timeline conjunta FPS + térmica + draw de potencia en una sola URL compartible

Observación de referencia: `research/competitive-analysis-press-reports` (#305).

### 2.5 Cómo presentan visualmente los datos de rendimiento las herramientas competidoras

Aunque nuestra herramienta entrega una GUI de escritorio y un informe HTML estático (no un dashboard en la nube), la **gramática visual** que los QA leads, productores y devs ya hablan viene de estos competidores. Cualquiera que abra nuestro informe tras usar GameBench o PerfDog compara al instante: ¿sacamos los FPS de la misma forma? ¿Se destacan los eventos de jank? ¿Dónde está la vista de comparación? Tomar prestados los patrones correctos es atajo para credibilidad — y evitar los incorrectos (compuestos opacos, pestañas escondidas) mantiene el informe escaneable. Esta sección cataloga cómo 8 herramientas competidoras presentan datos y destila los patrones a copiar para el diseño del informe en §7.

> **Nota de alcance**: muchas páginas de producto de competidores son sitios de marketing JS-SPA (PerfDog wetest, Snapdragon Profiler, AS Profiler) que no exponen los layouts de dashboard vía fetch estático. Donde la verificación directa de UI fue imposible, la fila se marca como **"necesita verificación — capturas públicas no encontradas"** y los detalles inferidos se etiquetan explícitamente como tales. Nunca inventamos detalles de UI que no pudimos ver en una página pública.

#### Tabla de comparación — presentación visual por herramienta

| Herramienta | Layout del dashboard | KPI cards mostrados | Tipos principales de gráfica | Codificación de severidad por color | ¿Drill-down? | ¿Vista de comparación? | URL de muestra (fecha de acceso) |
|------|------------------|-----------------|-------------------|------------------------|-------------|------------------|--------------------------|
| **GameBench Web Dashboard** | Multi-página: Home → Trends Explorer → Sessions → Session Detail (Metrics Timeline / Summary / FPS / Power / CPU / GPU / Memory / Network / Markers / Comparison). El detalle de sesión usa una **tira de capturas de pantalla arriba + timeline interactiva + tabla resumen de métricas por región + gráficas Metrics-Over-Time apiladas** debajo. | FPS mediana + rango min-max, FPS Stability %, FPS Variability, **1% Low FPS**, Frame Time (percentil 95), **Big Janks /10 min**, CPU media, GPU media, Memoria media con pico, Network In/Out | **Gráficas de series temporales en línea** (Primary + overlay opcional "Compare with"), **tira de thumbnails de capturas** sincronizada con la timeline, **bloques coloreados de región/marcador** en la barra de tiempo, **gráficas de distribución** (mediana + caja Q1-Q3) en el Trends Explorer por build, **tabla de rendimiento por dispositivo** con % de tendencia por dispositivo | Indicadores de tendencia por gráfica ("Declining" / "Trending Up"), badges **AFFECTED** y **OUTLIER** en las filas de dispositivo, líneas de umbral de objetivo de calidad dibujadas en las gráficas (e.g. "FPS Median ≥ 30") | SÍ — clica cualquier punto de la timeline para ver la captura en ese momento; clica región → métricas filtradas a la región; clica gráfica de métrica → página detallada del módulo | **SÍ, feature principal.** Selecciona 2+ sesiones en la tabla Sessions → "Compare" → abre tabla resumen (una fila por sesión, numerada con índice) + **gráfica overlay en timeline compartida**, codificada por color por sesión. Filtrado opcional por región de marcador. | <https://docs.gamebench.net/docs/web-dashboard/session-detail/metrics-timeline/> (2026-05-18); <https://docs.gamebench.net/docs/web-dashboard/session-detail/comparison/> (2026-05-18); <https://docs.gamebench.net/docs/web-dashboard/trends/> (2026-05-18) |
| **PerfDog Desktop + Web** | Multi-panel en tiempo real durante captura: flujos de métricas en vivo arriba (FPS / CPU / Memoria / Batería / Red) con eje X compartido; Tags + Scenes superpuestos como marcadores verticales; post-captura va al Web Dashboard con jerarquía proyecto/caso/tarea. Las páginas de marketing enfatizan 5 módulos de producto (General Test / In-depth / Network / PerfDogService / Web Dashboard). | Las páginas de marketing listan **FPS, Jank, Big Jank, Stutter, CPU, GPU, Memoria, Batería (FPower mW/frame), Red, Temperatura, Frame Time**. El layout específico de las KPI cards no es verificable desde páginas públicas (muro de autenticación). | Los docs públicos referencian **gráficas de líneas multi-stream en tiempo real** con eje de tiempo compartido y anotaciones overlay de **Tags/Scenes**. El catálogo específico de gráficas no es verificable (engram obs #328: help center cerrado tras login). | No verificable desde páginas públicas | Inferido del marketing: clic en timeline → snapshot de métrica. **Necesita verificación — capturas públicas no encontradas 2026-05-18.** | SÍ (marketing claim: "团队合作" comparación cross-session y cross-dispositivo via Web Dashboard). UI específica no verificable. | <https://perfdog.qq.com/> (2026-05-18); <https://perfdog.wetest.net/> (2026-05-18); sólo páginas de marketing — el Help Center está cerrado tras login según engram obs #328 |
| **Snapdragon Profiler (Qualcomm)** | Estilo IDE de escritorio: selector de fuentes de datos en árbol a la izquierda, **timeline multi-track** (CPU por core / etapas GPU / perfcounters de driver / eventos de sistema) en el canvas principal, visor de captura de frame en ventana separada para diseccionar frames GL/Vulkan. | Lecturas de métrica en vivo (FPS, utilización GPU, CPU por core %, memoria) en la cabecera de la timeline durante captura — no "cards de resumen" en el sentido del dashboard. | **Tracks apilados de series temporales** (estilo Perfetto), **barras de desglose por etapa del pipeline GPU**, **vista flame de captura de frame** (coste por draw call). | Destacado basado en umbral en tracks de métricas (picos rojos para superar presupuesto). Paleta específica no verificable desde docs públicos. | SÍ — drill profundo: clic en frame del trace → captura de frame → coste por draw call / shader / texture binding. Workflow de deep-dive de proveedor. | NO hay comparación multi-sesión incorporada; el usuario abre manualmente dos capturas lado a lado. **Necesita verificación — capturas públicas no encontradas 2026-05-18** (Qualcomm dev portal requiere JS para docs completas). | <https://www.qualcomm.com/developer/software/snapdragon-profiler> (2026-05-18, JS-SPA — sólo landing); inferido de capacidades documentadas |
| **ARM Streamline / Performance Studio** | App de escritorio — timeline multi-track (layout derivado de Perfetto) con **flame chart de CPU por core arriba, contadores Mali GPU debajo, sitios de llamada a nivel de código fuente enlazados abajo**. | Contadores en vivo en las cabeceras de track (ciclos/frame, % miss de caché, FMA GPU / textura / load-store). No "resumen por cards". | **Flame chart de CPU por core**, **series temporales de contadores Mali GPU por etapa**, **atribución por línea de fuente** (líneas calientes destacadas en la vista de fuente). | Líneas de fuente codificadas por calor (rojo = camino caliente), tracks de contador graduados por color. | SÍ — clic en pico de contador → ubicación en fuente del camino caliente (feature distintiva de ARM). | NO hay comparación incorporada; lado a lado manual. **Necesita verificación — capturas públicas no encontradas 2026-05-18** (developer.arm.com es una app JS, no se alcanzan capturas estáticas). | <https://developer.arm.com/Tools%20and%20Software/Streamline%20Performance%20Analyzer> (2026-05-18) |
| **Unity Profiler** | **Gráficas de módulo apiladas** en tiras verticales dentro del Unity Editor: CPU Usage / Rendering / Memory / Audio / Video / Physics / UI / Global Illumination / etc., cada módulo es una gráfica de área coloreada. Seleccionar un frame en cualquier gráfica abre un **panel de detalles inferior** con vista hierarchy / timeline para ese frame. | Sin cards de resumen top-level — cada módulo *es* su propia gráfica de un vistazo. Frame Count por defecto 2.000, configurable 600-4.000. | **Gráficas de área apiladas por subsistema**, **flame chart por frame** (vista Timeline), **tabla de jerarquía de llamadas** (vista Hierarchy). | Codificación de color específica por módulo (CPU = azul/amarillo/naranja por categoría, Rendering = verde/rojo); sin RAG global. **Sin línea de umbral de referencia en la gráfica de frame-time** por defecto — el usuario debe leer el valor absoluto en ms. | SÍ — clic en frame → panel de detalle → drill jerarquía / timeline / específico del módulo. | NO hay comparación multi-sesión en el Profiler en sí. Un paquete separado **Profile Analyzer** hace esto — histogramas de distribución sobre N frames, comparación de dos capturas. | <https://docs.unity3d.com/Manual/Profiler.html> (2026-05-18); <https://docs.unity3d.com/Manual/profiler-window-navigating.html> (2026-05-18) |
| **Android Studio Profiler** | **Tiras de métrica apiladas verticalmente** (CPU / Memory / Network / Energy) dentro de la herramienta Profile, eje de tiempo compartido. Clic en cualquier tira para "expandir" a su sesión dedicada — System Trace / Method Trace / Heap Dump / Network Inspector con sus propias vistas profundas. | Lecturas de métrica en vivo en cabeceras de tira (CPU %, memoria MB, KB/s subida/bajada). Sin card resumen de KPI. | **Tiras de series temporales apiladas** en la vista unificada, **flame chart + call chart + tablas top-down/bottom-up** en drill-down de CPU, **tabla de objetos en heap + grafo de retención** en drill-down de Memoria. | Codificación de color por subsistema (CPU = colores de thread, Memoria = tipo de allocation), destacados rojos para eventos ANR y OOM. | SÍ — clic en tira → abre herramienta a pantalla completa con su propio drill-down. **Información crítica escondida tras clics** es una queja UX conocida. | NO. AS Profiler es de una sola sesión, vinculado al IDE. **Necesita verificación — capturas públicas del dashboard no encontradas 2026-05-18** (developer.android.com fetch falló dos veces vía webfetch del agente). | <https://developer.android.com/studio/profile/> (2026-05-18 — fetch falló); inferido de comportamiento ampliamente documentado |
| **Firebase Performance Monitoring** | **Consola web** con dos vistas top-level: (1) Dashboard / Issues, (2) Agregado on-device. Páginas por trace y por petición de red muestran gráficas de distribución y violaciones de umbral. Centrado en consola, no en sesión (diseñado para agregado de flota en producción). | KPIs agregados: % frames lentos, % frames congelados, tiempo de arranque p50/p75/p90 por plataforma/país/dispositivo/versión-OS. **Sin timeline por sesión** — Firebase es agregado por diseño. | **Histogramas de distribución** (tiempos de respuesta, tiempos de frame), **series temporales de métrica agregada sobre rango de fechas**, **gráficas de barras de desglose por atributo** (país / dispositivo / OS). | Basado en umbrales: e.g. "Malo si >50% frames lentos" (alineado con Vitals). Color: rojo para violación, verde para OK. La vista Issues hace surface a las violaciones de umbral. | SÍ — clic en desglose por atributo → filtrar a ese atributo → drill métrica. | SÍ (cross-version): Firebase compara versiones / fechas nativamente para métricas agregadas. No es comparación por sesión. | <https://firebase.google.com/docs/perf-mon> (2026-05-18). Nota: aviso explícito contra traces custom por frame en juegos. |
| **Sentry Mobile Vitals** | **Cuadrante de Performance Score** (bandas Good/Meh/Poor) arriba, luego **App Starts**, **Screen Loads**, **Slow & Frozen Frames**, **Frames Delay**, **TTID/TTFD** como widgets separados. El drill-down por trace muestra waterfall de spans con mediciones en la sidebar derecha. | App Start Cold (<3s bueno, 3-5s regular, >5s malo), App Start Warm (<1s / 1-2s / >2s), % de frame rate lento, % de frame rate congelado, Frames Delay (ms), TTID, TTFD. **Bandas Good/Meh/Poor explícitas** en cada vital. | **Series temporales agregadas** con bandas de percentiles (p50/p75/p95/p99), **histogramas de distribución**, **waterfall de spans** en detalle de trace (Suspect Spans destacados en rojo). | **Bandas RAG** incorporadas: Bueno = verde, Regular = ámbar, Malo = rojo — aplicado por métrica usando los umbrales publicados. Es el ejemplo más claro de RAG explícito en cualquier herramienta revisada. | SÍ — clic en vital → página de feature screen-loads / app-starts con la misma métrica filtrada a una pantalla/transacción específica. Suspect Span destacado → root span. | SÍ (release-a-release): Sentry compara deltas de vitals entre releases nativamente. Los traces por sesión también son apilables. | <https://docs.sentry.io/product/insights/mobile/mobile-vitals/> (2026-05-18); <https://docs.sentry.io/product/dashboards/sentry-dashboards/mobile/mobile-vitals/> (2026-05-18) |

#### Mejores patrones de visualización observados (a copiar)

- **Bandas RAG explícitas de Sentry por vital con umbrales PUBLICADOS** — cada vital tiene cortes Good/Meh/Poor visibles justo al lado del valor. Cero ambigüedad, escaneo instantáneo. Lo mejor de la clase para "¿es este número bueno o malo?".
- **Tira de capturas de pantalla de GameBench sincronizada con la timeline de métricas** — contexto visual para cada caída de framerate. Elimina la pregunta "¿qué pasaba en pantalla?" que todas las demás herramientas obligan a adivinar.
- **Overlay de comparación de sesiones de GameBench en timeline compartida** con líneas por sesión codificadas por color + tabla resumen numerada con índice — la implementación más limpia de comparación multi-sesión vista.
- **Gráficas de distribución del Trends Explorer de GameBench** (mediana + caja Q1-Q3 por build) — hace surface a la variancia entre builds de un modo que los deltas de un solo número esconden. Combínalo con los **badges AFFECTED / OUTLIER** en la tabla por dispositivo para señalar focos de regresión.
- **Anotaciones de marcador/tag de PerfDog superpuestas en la timeline de métricas** — líneas verticales etiquetadas con el nombre de escena/evento convierten las curvas crudas en una narrativa. (Patrón también en GameBench markers — ambas herramientas convergieron en esto.)
- **Suspect Span destacado de Sentry en los waterfalls de trace** — flagea automáticamente el span que causó el trace lento. Hace surface a la causalidad sin forzar al usuario a escanear una lista larga.
- **Gráficas de área apiladas por subsistema de Unity Profiler en eje X compartido** — correlación de un vistazo entre CPU / GPU / Rendering / Memory. El eje de tiempo compartido es esencial.
- **Barras de desglose por atributo de Firebase** (país / dispositivo / OS) — el patrón correcto para "¿dónde está peor el problema?". Útil para nuestros rollups por tier de dispositivo en §6.3.
- **KPI card de GameBench `Big Janks /10 min` normalizado por tasa** — conteos normalizados por longitud de sesión para que sesiones cortas y largas comparen directamente. Deberíamos hacer lo mismo para nuestros conteos de jank.
- **Codificación de calor por línea de fuente de ARM Streamline** — no directamente portable (no tenemos atribución a fuente), pero el principio de "colorea la línea donde está el problema" aplica a nuestros rangos de eventos (tinta rojo la región de gameplay donde el FPS p1 fue malo).

#### Anti-patrones observados (evitar)

- **Gráfica de frame-time de Unity Profiler en microsegundos sin línea de referencia de 16ms / 33ms** — el usuario debe convertir mentalmente y recordar el objetivo. Dibuja siempre la línea de presupuesto.
- **"Información crítica escondida tras clics" de AS Profiler** — las tiras colapsan las sub-métricas más importantes (ANRs en Memory, draw commands lentos en CPU). Cualquier cosa por encima del umbral debe ser visible sin un clic.
- **Compuestos opacos de un solo número sin desglose** (Notebookcheck "89%", cualquier "performance score" no transparente) — no interpretable para ingenieros. Si publicamos un compuesto, mostrar siempre el desglose por categoría al lado.
- **Vistas sólo agregadas que borran detalle por sesión** (Firebase Perf, Embrace) — bien para RUM en producción, mal para laboratorios QA. Nuestro posicionamiento depende de la fidelidad por sesión.
- **Páginas de marketing JS-renderizadas que esconden la UI real tras muros de login** (PerfDog wetest help center, docs de Qualcomm dev) — anti-patrón en su *propio posicionamiento*; oportunidad para nosotros: publicar las capturas reales del informe en nuestros docs, sin login.
- **Paletas de color que dependen sólo de rojo/verde** — fallo de accesibilidad para usuarios daltónicos. RAG debe incluir forma o etiqueta de texto, no sólo color.

#### Recomendaciones para nuestro informe HTML (ordenadas por valor percibido)

1. **Adoptar bandas RAG estilo Sentry en CADA KPI card** con el umbral publicado al lado del valor — anclar en §3.1 Vitals + §3.6 PerfDog. Usar forma/texto además de color (evitar anti-patrón). Mapea con §5.1 catálogo maestro de KPIs. **— Aplicado en v4.7.0**
2. **Añadir una tira de capturas sincronizada con la timeline FPS+Térmica** (patrón GameBench). Las capturas se pueden muestrear a 1 Hz vía `adb screencap`; embeber como base64 inline en el HTML auto-contenido (§7.6 decisión). Alto impacto visual, coste de implementación moderado.
3. **Añadir líneas de presupuesto de referencia explícitas en gráficas de frame-time** (16.6 ms / 33.3 ms / 8.3 ms — §3.4) — elimina el anti-patrón de Unity Profiler. Una línea de render. **— Aplicado en v4.7.0**
4. **Añadir cajas de distribución por fase (mediana + p1 + p99 + min/max) encima de las tablas existentes por fase** (patrón Trends de GameBench) — hace surface a la variancia que las medias de un solo número esconden. Empaquetar con el cambio §9 #3 `shareable-html-report`. **— Aplicado en v4.7.0**
5. **Añadir vista de comparación de sesiones** con timelines superpuestas codificadas por color por sesión + tabla resumen numerada con índice (patrón GameBench). Alinea con §9 #13 `multi-device-capture`. Diferir a post-CLI.
6. **Añadir destacado de Suspect-Phase en el resumen por fase** (patrón Sentry) — auto-flagear la fase con los peores deltas de KPI vs la línea base de gameplay. Alinea con la segmentación de eventos de §4 + conclusiones cualitativas (v4.4.0).

#### Línea de cierre

Estos patrones alimentan directamente §7 (diseño del informe HTML compartible) — las recomendaciones #1, #2, #3 se empaquetan en el cambio `shareable-html-report` (§9 #3), y #5 se empaqueta en `multi-device-capture` (§9 #13). Los anti-patrones informan de qué NO debemos enviar ni por accidente.

---

## 3. KPIs estándar del mercado (fuentes oficiales)

### 3.1 Google Android Vitals — impacto directo en el ranking

> Fuente: <https://support.google.com/googleplay/android-developer/answer/9844486> (recuperado 2026-05-12). Google Play penaliza la visibilidad de la app cuando se cruzan estos umbrales. Ventana de evaluación: últimos 28 días. El mal comportamiento por modelo de dispositivo también se rastrea por separado.

**Estabilidad — Core Vitals (impacta visibilidad en Play Store)**

| KPI | Umbral malo global | Umbral malo por dispositivo | Notas |
|-----|-----------------------|--------------------------|-------|
| Tasa de ANR percibida por el usuario | **≥ 0.47%** de DAU experimentan ≥1 ANR percibido | **≥ 8%** en un único modelo | Sólo cuentan los ANRs "input dispatching timed out" |
| Tasa de crash percibida por el usuario | **≥ 1.09%** de DAU experimentan ≥1 crash percibido | **≥ 8%** en un único modelo | Sólo foreground/foreground-service |
| Tasa de ANR múltiple | (indicador, sin umbral publicado) | — | ≥2 ANRs el mismo día — señala bucles |
| Tasa de crash múltiple | (indicador, sin umbral publicado) | — | ≥2 crashes el mismo día |
| Tasa de LMK percibido por usuario | (sin umbral publicado aún) | — | LMKs durante foreground |

**Tiempo de arranque — umbrales lentos**

| Tipo de lanzamiento | Umbral lento |
|-------------|----------------|
| Cold start | **≥ 5 segundos** |
| Warm start | **≥ 2 segundos** |
| Hot start | **≥ 1 segundo** |

Nota: se registra el MÁX del día por estado del sistema (no la media). Reportado al percentil 90/99 por sesión.

**Renderizado — Juegos (NUEVO core vital)**

| KPI | Definición | Notas |
|-----|------------|-------|
| Tasa de sesión lenta (30 FPS) | % de sesiones diarias donde **>25% de los frames fallan 30 FPS** (percentil 75) | "La mayoría de los juegos deberían apuntar a 30+ FPS" |
| Tasa de sesión lenta (20 FPS) | % de sesiones diarias donde **>25% de los frames fallan 20 FPS** (percentil 75) | **"Play empezará a desviar usuarios fuera de juegos que no puedan alcanzar 20 FPS en sus teléfonos"** (cita exacta) |

Datos de frame vía SurfaceFlinger en Android 9+. La monitorización empieza 1 minuto tras el arranque del juego. Incluye frames de OpenGL, Vulkan y Android UI toolkit.

**Renderizado — Apps (UI Toolkit)**

| KPI | Umbral malo |
|-----|---------------|
| Frames lentos excesivos | **>50% de frames con tiempo de render >16 ms** por sesión |
| Frames congelados excesivos | **>0.1% de frames con tiempo de render >700 ms** por sesión |

Sub-métricas para frames >16ms:
- Alta latencia de input: eventos de input **>24 ms**
- Hilo UI lento: **>8 ms**
- Draw commands lentos: envío de comandos de draw GPU **>12 ms**
- Subidas de bitmap lentas: subida de bitmap a GPU **>3.2 ms**
- Vsyncs perdidos (cuenta por frame)

**Batería — Comportamientos malos**

| KPI | Umbral malo |
|-----|---------------|
| Wake locks parciales atascados | ≥1 wake lock **>1 hora** por sesión de batería |
| Wake-ups excesivos | **>10 wake-ups/hora** por sesión de batería |
| Escaneos Wi-Fi excesivos (bg) | **>4 escaneos/hora** por sesión de batería |
| Uso de red excesivo (bg) | **>50 MB/día** en background por sesión de batería |
| Uso de batería excesivo (watch face) | **>4.44%/hora** |
| Wake locks parciales excesivos (BETA) | ≥1 wake lock que sume **>3 horas** por sesión de batería |

**Core Value (retención)**

| KPI | Umbral |
|-----|-----------|
| DAU/MAU | **<8%** dispara aviso |
| Tasa de pérdida de usuarios | **>5%** dispara aviso |

#### 3.1.1 Umbrales de Google Play Vitals 2024 — fuente oficial de Google

> Fuente: Google Play Console — Android Vitals "umbrales de mal comportamiento" (Octubre 2024, recopilado y confirmado vía Gemini deep-dive). Engram observation: `#424 — research/google-play-vitals-2024-thresholds`. Estos son los umbrales que **bloquean la tienda**: cruzarlos dispara penalizaciones directas en Play Store (reducción de visibilidad, eliminación de Top Charts, throttling de descubrimiento) — no son advisory, no son "buena práctica", son señales reales de ranking aplicadas por Google.

| Métrica (Vital) | Umbral Máximo General | Umbral Máximo Por Dispositivo | Penalización si lo superas |
|-----------------|-----------------------|-------------------------------|----------------------------|
| User-Perceived Crash Rate | < 1.09% de los usuarios | < 8.0% en un modelo específico | Reducción visibilidad + avisos tienda |
| User-Perceived ANR Rate | < 0.47% de los usuarios | < 8.0% en un modelo específico | Eliminación Top Charts + recomendaciones |
| Excessive Partial Wake Locks | < 5.0% sesiones (>2h en 24h screen-off) | — | Throttling descubrimiento app |
| Cold Start | < 5s | — | Pérdida prioridad algoritmo calidad |
| Warm Start | < 2s | — | Pérdida prioridad algoritmo calidad |
| Hot Start | < 1s | — | Pérdida prioridad algoritmo calidad |
| Slow UI Sessions (frames > 700ms) | < 0.1% sesiones | — | Peor posicionamiento "Apps similares" |

**Cómo GamePerf mapea estos a KPIs v1 single-session** (engram `sdd/vitals-rate-and-wakelocks/spec`):

| Umbral Vital (Google) | KPI GamePerf v1 | Proxy single-session |
|--------------------------|-----------------|----------------------|
| User-Perceived Crash Rate < 1.09% | `KpiId.CRASH_RATE_USERS` (Categoría: Estabilidad) | `CRASH_COUNT > 0` dispara banner |
| User-Perceived ANR Rate < 0.47% | `KpiId.ANR_RATE_USERS` (Categoría: Estabilidad) | `ANR_COUNT > 0` dispara banner |
| Excessive Partial Wake Locks < 5% sessions | `KpiId.WAKE_LOCKS_RATE` (Categoría: Recursos, unidad `h`) | `wakeLocksScreenOffMs >= 2h` dispara banner |

**Spec de medición de wake locks** (engram `#425 — research/wake-locks-measurement-spec`): GamePerf lee `adb shell dumpsys batterystats --charged <pkg>` y parsea la sección "All partial wake locks:", sumando duraciones atribuidas al paquete objetivo. Ventana de plausibilidad: `0 ≤ ms ≤ 24*3600*1000`. Entradas fuera de rango se descartan con diagnóstico `OUT_OF_RANGE_VALUE`. Cadencia de polling: 30 ticks (~15s) — los wake locks son métricas acumulativas donde el valor final es lo que importa, los muestreos intermedios son sólo orientativos.

**Confianza v1**: MEDIA. Los umbrales son oficiales de Google (tabla arriba). La medición es **single-session**, lo que es un proxy del ratio cross-session que Vitals calcula realmente (% de usuarios / % de sesiones). Si una sola sesión ya cruza el umbral de wake locks de 2h, la tasa cross-session está casi seguro por encima del umbral del 5%. Si una sola sesión NO cruza, no podemos confirmar la tasa Vitals real sin agregación cross-session (v2 diferido — requeriría roll-up de `history.json` entre N sesiones del mismo modelo de dispositivo).

**Trabajo diferido v2**: bloqueo del 8% por modelo de dispositivo (Vitals penaliza cada modelo por separado), agregación cross-session para cálculo de tasa real, soporte iOS (sin equivalente directo a `dumpsys batterystats`), desglose de wake locks por componente (qué SDK mantiene el lock).

### 3.2 Modelo de rendimiento RAIL de Google

> Fuente: <https://web.dev/articles/rail> (recuperado 2026-05-12). Core Web Vitals supersede a RAIL para web, pero los presupuestos RAIL siguen siendo canónicos para la percepción del usuario y la guía de ingeniería para cualquier software interactivo.

**Percepción del usuario sobre el retraso** (números ancla — improbable que cambien):

| Rango de retraso | Percepción del usuario |
|-------------|-----------------|
| 0–16 ms | Animación suave (60 FPS) |
| 0–100 ms | Se siente instantáneo |
| 100–1000 ms | Se siente como progresión natural de la tarea |
| >1000 ms | El usuario pierde el foco en la tarea |
| >10000 ms | Frustración del usuario, abandono probable |

**Objetivos/presupuestos RAIL**:

| Aspecto | Objetivo | Por qué |
|--------|------|-----|
| **R**esponse | Procesar input del usuario en **<50 ms** | Deja 50 ms para trabajo idle encolado, total ≤100 ms percibidos |
| **A**nimation | Producir cada frame en **≤10 ms** | Presupuesto de 16 ms − ~6 ms render del navegador/sistema = 10 ms presupuesto app |
| **I**dle | Tareas idle **≤50 ms** por bloque | Bloques más grandes arriesgan interferir con el siguiente input |
| **L**oad | Página interactiva **≤5 segundos** en móvil de gama media + 3G lento | Cargas subsiguientes objetivo <2s |

Condiciones de test base que Google recomienda: Moto G4 + 3G lento (400 ms RTT, 400 kbps).

### 3.3 Certificación de consola

> **Sujeto a NDA, sin umbrales públicos verificables.** Usar sólo guía genérica del titular de la plataforma.

Lo que es conocido públicamente y reportado consistentemente por charlas GDC y proveedores de motor (sin URL única autoritativa):
- **Sony PS5/PS4 (TRC)**: 30 FPS o 60 FPS estables según el modo declarado; resume desde rest en menos de 1 segundo; cero crashes durante la pasada de certificación.
- **Microsoft Xbox (XR / Xbox Requirements)**: frame rate objetivo estable, sin hangs/ANRs, presupuesto de memoria por SKU.
- **Nintendo Switch (Lotcheck)**: estabilidad bajo stress (long-run, casos límite), cumplimiento de localización, sin crashes durante el camino de certificación.

**Recomendación para el sistema de puntuación**: NO citar números específicos de consola como "oficiales" a no ser que obtengamos los documentos reales de TRC/XR/Lotcheck. Marcar como "certificación del titular de la plataforma" con requisitos generales de "frame rate estable, cero crashes durante la certificación".

### 3.4 Guía de motor (Unity / Unreal)

> **Requiere descarga manual de e-book de Unity/Unreal, no verificado en esta pasada.** Los docs de perf móvil de Unity y Unreal son SPAs renderizadas en JS — no se pudieron extraer umbrales numéricos vía WebFetch.

Fuentes autoritativas (descarga manual necesaria):
- Unity: "Optimize your game performance for mobile, XR, and Web in Unity 6" (e-book)
- Unreal: "Performance Guidelines for Mobile Devices" (PDF de docs de Epic)

**Presupuestos de frame DERIVADOS (matemáticas, no publicados por proveedor)**:

| FPS objetivo | Presupuesto de frame |
|------------|--------------|
| 30 FPS | **33.3 ms** |
| 60 FPS | **16.6 ms** |
| 120 FPS | **8.3 ms** |

Citados públicamente (no en URL única):
- Los presupuestos de draw calls varían por tier (Unity aprox: gama baja ~50–100, media ~200–300, alta ~500+ por frame). No verificable a una URL.
- Convención de presupuestos de memoria: dispositivo 1 GB → ~512 MB presupuesto del juego; 2 GB → ~1 GB.

### 3.5 Guía de proveedores GPU (ARM Mali, Qualcomm Adreno, Apple Metal)

> **Recuperación manual de PDF pendiente.** Los sitios de dev de ARM, Qualcomm y Apple son todos JS-SPA — no se pudieron extraer umbrales numéricos vía WebFetch.

Fuentes autoritativas (descarga manual necesaria):
- ARM Mali GPU Best Practices Guide (PDF, developer.arm.com)
- Qualcomm Adreno GPU Game Developer Guide (PDF)
- Apple "Metal Best Practices Guide" (developer.apple.com/documentation/metal)

**Único objetivo cuantitativo publicado por proveedor verificado en esta pasada**:
> Presupuesto de lanzamiento de Apple iOS: **lanzamiento total ≤ 400 ms** (primer frame renderizado). Init del sistema ~100 ms, disponible para el desarrollador ~300 ms. Fuente: <https://developer.apple.com/videos/play/wwdc2019/423/> — "Optimizing App Launch", Apple Performance Team WWDC 2019.

Desglose de fases de lanzamiento de Apple iOS (6 fases): dyld → libSystemInit → Static Runtime Init → UIKit Init → Application Init (mayor impacto para el dev) → First Frame Render. Fase extendida opcional (carga async de datos tras el primer frame).

Guideline 2.4.2 de App Store Review de Apple: "Las apps no deben drenar batería rápido, generar calor excesivo, ni poner cargas innecesarias en los recursos del dispositivo." Sin umbrales numéricos — juicio del revisor.

Observación de referencia: `research/market-kpis-official-sources` (#309).

### 3.6 Fórmulas de métricas publicadas por PerfDog (referencias de industria)

> Fuente: post #1189 del blog WeTest (entrevista del dev fundador Awen Cao por el Sr. PM Baojian Shen, Marzo 2026) + <https://perfdog.wetest.net/> página de producto. Verificado en obs #312.

#### Jank (PerfDog 2019)

```
FrameTime > 2 × avg(últimos 3 frames) AND FrameTime > 84 ms   (Jank Simple)
FrameTime > 2 × avg(últimos 3 frames) AND FrameTime > 125 ms  (Big Jank)
```

Más estricto que Android Vitals slow-frame (>16 ms). Nuestra puntuación EXPONDRÁ AMBOS como columnas KPI separadas — ver §5.1.

#### SmallJank (2020, pantallas 120 Hz+)

Umbrales NO públicos. Diferir adopción hasta que nuestra herramienta soporte usuarios objetivo de 120 Hz.

#### Smooth Index

```
Smooth Index = 100 - puntuación_severidad_jank_ponderada
```

Objetivo >95 para AAA. Ponderación NO pública.

Nuestra postura: implementar equivalente en nuestro framework de puntuación con ponderación PÚBLICA (ventaja de transparencia vs la ponderación cerrada de PerfDog).

#### FPower (primer indicador de PerfDog en la industria)

```
FPower = Potencia Total (W) / FPS = mW por frame
```

Umbrales ancla (estudios de caso de PerfDog):
- **< 50 mW/frame**: excelente (60 mW → 46.7 mW dio 22% más vida de batería con FPS sin cambios)
- **50–65 mW/frame**: aceptable
- **> 65 mW/frame**: investigar

Fuente de implementación para nuestra herramienta: `/sys/class/power_supply/battery/current_now` + `voltage_now` vía `adb shell cat`.

#### CPU% normalizado por frecuencia

```
CPU%_normalizado = cpu_pct_crudo × (frecuencia_actual / frecuencia_máx)
```

Por defecto en PerfDog. Elimina distorsión por throttling: una CPU throttled al 60% crudo está más cerca de la saturación que una CPU sin throttling al 60%. Crítico para puntuación consciente del térmico.

---

## 4. Framework de segmentación de eventos

### 4.1 Fases de juego a rastrear (solicitadas por el usuario)

1. Arranque de app / inicialización de SDK
2. Cinemáticas
3. Tutoriales
4. Carga de nivel / mapa
5. Navegación entre pantallas
6. Anuncios intersticiales
7. Vídeos recompensados
8. Gameplay (por defecto / fallback)

### 4.2 Cobertura actual en nuestra herramienta (auditoría 2026-05-12)

> Fuente: `audit/event-segmentation-coverage-2026-05-12` (#308). Verificado contra `core/events/SdkSignatureCatalog.kt`, `core/events/DetectedEvent.kt`, `AppViewModel.kt`.

| # | Fase | ¿Auto-detectada? | ¿Marcador manual? | Brecha | Esfuerzo para cerrar |
|---|-------|----------------|----------------|-----|-----------------|
| 1 | **Arranque app / init SDK** | ❌ NO. Cero firmas para Firebase/GA/AppMeasurement/init. Sin detector cold-vs-warm. | ⚠️ Sólo `CUSTOM` (post-captura, sin ancla en tiempo real). | **CRÍTICA** — la herramienta no sabe cuándo llegó el SDK ni cuánto tardó el init. No existen eventos `APP_STARTUP`/`SDK_INIT`. | **2d** (nuevo `EventType.APP_STARTUP`+`SDK_INIT`, 6 firmas de init, sensor cold-start vía primera match en dumpsys del gamePackage) |
| 2 | **Cinemáticas** | ❌ NO — imposible sin instrumentación (semántica de gameplay, no detectable por SDK). | ✅ `MarkerType.SCENE_CHANGE` cosmético. | La herramienta no puede distinguir semánticamente cinemática de menú. | **1d** (protocolo opt-in de tag `GamePerf:I Cinematic.Start/End` + nuevo `EventType.CINEMATIC`) |
| 3 | **Tutoriales** | ❌ NO (igual que cinemáticas — semántica de gameplay). | ✅ `MarkerType.CUSTOM`. | Lo mismo que #2. | **1d** (protocolo opt-in de tag `GamePerf:I Tutorial.Step name="..."` + nuevo `EventType.TUTORIAL`) |
| 4 | **Carga de nivel / mapa** | ⚠️ `EventType.LOADING` **DECLARADO + RENDERIZADO** en `ReportGenerator` ("Carga"/`#f59e0b`) pero **CERO firmas lo emiten**. Auto-detección muerta. | ✅ `MarkerType.LOADING`. | Falsos negativos garantizados en el camino auto. | **0.5d** (cablear firmas Unity/Unreal/Cocos2d: `Unity:I Loading scene`, `UnityEngine:I AsyncOperation`, `UE4:I LogStreaming`/`LoadingScreen`, `cocos2d:I CCDirector.replaceScene`. Allowlist de tags para limitar falsos positivos) |
| 5 | **Navegación entre pantallas** | ⚠️ Datos **disponibles pero no clasificados**. `DumpsysPoller` ya monitoriza top-of-stack a 1Hz; cualquier cambio de `cmp` dentro del paquete del juego se ignora hoy. | ✅ `MarkerType.SCENE_CHANGE`. | El sensor está; falta el clasificador. | **0.5d** (nuevo `EventType.SCREEN_TRANSITION` emitido cuando `top.cmp` cambia y empieza con `$gamePackage/`, confianza MEDIA. Caveat: los juegos de una sola activity como Unity/Unreal no emitirán) |
| 6 | **Anuncios intersticiales** | ✅ **COMPLETO**. 5 SDKs en catálogo: AdMob, Unity Ads, IronSource, AppLovin/MAX, Meta Audience. El camino a nivel de Activity sobrevive a ProGuard. | ✅ `MarkerType.INTERSTITIAL` redundante. | Ninguna crítica. Brechas potenciales: Vungle, Chartboost, Mintegral, Yandex, Pangle. | **0d** (mantener; añadir Vungle/Chartboost/Pangle si AppMagic revela uso en juegos a testear) |
| 7 | **Vídeo recompensado** | ⚠️ Sólo Unity Ads. Variantes recompensadas de AdMob/IS/AppLovin/Meta **mal etiquetadas como INTERSTITIAL** (mismas clases de activity, el catálogo actual sólo soporta un `type` por firma). | ✅ `MarkerType.VIDEO_REWARD`. | Clasificación errónea en el informe HTML para 4 de 5 SDKs. | **1d** — refactor BREAKING: `SdkSignature.openPatterns: List<(Regex, EventType)>` en lugar de un único campo `type` |
| 8 | **Gameplay (por defecto)** | ✅ Por defecto — cualquier cosa no clasificada como evento = gameplay. | N/A | Ninguna. | 0 |

**Brechas arquitectónicas** (de auditoría obs #308):
- `SdkSignature.type` fija un único EventType por firma → bloquea split rewarded/init dentro del mismo SDK.
- Sin hook para "tag instrumentado" desde el juego (e.g. `GamePerf:I Tutorial.Start`).
- Sin noción de contenedores jerárquicos de "fase". Todo es `List<DetectedEvent>` plano.
- Sin detección cold-vs-warm. Sensor de reinicio de PID funcionaría (`/proc/<pid>` reaparece) — los datos existen, falta la lógica.

`EventType.FOREGROUND_LOSS` está declarado para el sidecar iOS (EVT-010, IOS-003) — el sidecar Python no fue auditado en esta pasada.

### 4.3 Estado del arte — cómo segmentan otros

> Fuente: `research/event-segmentation-state-of-art` (#306).

| Herramienta | Mecanismo | Verificado | Notas |
|------|-----------|----------|-------|
| **GameBench Programmatic Markers** | Escaneo de strings en logcat — `gb_marker_start - <label>` / `gb_marker_stop - <label>` en CUALQUIER línea de logcat. Sufijo opcional `-group=<name>`. | ✅ CONFIRMADO vía <https://docs.gamebench.net/general-information/programmatic-markers/> | **EL patrón.** Sobrecoste SDK cero, agnóstico a lenguaje. Valida nuestro enfoque sólo-adb. |
| **Traces custom de Firebase Performance** | El SDK llama `Performance.startTrace(name)` / `trace.stop()`. Soporta métricas custom (`trace.incrementMetric`), atributos custom (máx 5 K/V), 32 métricas incluyendo duración. | ✅ CONFIRMADO | Aviso explícito: **"Evita crear traces de código custom a alta frecuencia (por ejemplo, una vez por frame en juegos)"** |
| **Transacciones Sentry** | Root-span como nombre de transacción. Mediciones de rendimiento custom (hasta 10/transacción). p50/p75/p95/p99/p100, throughput, Apdex, User Misery (% usuarios >4×umbral). Suspect Spans. Self-time = span − child spans. | ✅ CONFIRMADO | Modelo de agregación maduro que vale la pena tomar prestado para nuestros informes |
| **Perfetto Tracing SDK** | Macros `TRACE_EVENT`, categorías con filtro, backend in-process o en sistema (fusiona eventos de app con ftrace/scheduler/syscalls). | ✅ CONFIRMADO | Android: `android.os.Trace` (Java) / `ATrace_*` (NDK). "Instrumentación basada en atrace totalmente soportada en Perfetto." |
| **Callbacks de ciclo de vida de AdMob** | Callbacks Java/Kotlin (`InterstitialAdLoadCallback`, `FullScreenContentCallback`). Los strings de código de muestra `Log.d("MyActivity", "The ad was shown.")` están CONTROLADOS POR LA APP, NO emitidos por el SDK. | ✅ CONFIRMADO | **AdMob NO emite un tag de logcat por defecto estable para ciclo de vida de anuncios.** No se puede auto-detectar fiablemente el show de un anuncio AdMob vía logcat a menos que el juego añada logging. |
| **PerfDog** | Sólo marketing chino en docs; comportamiento técnico de escena/segmentación NO verificado. | ❌ NO VERIFICADO | — |
| **IronSource, AppLovin, Vungle, Mintegral** | Patrones de logcat NO verificados. El enlace de docs de IronSource redirigió al marketing de Unity LevelPlay. Sin acceso técnico en esta pasada. | ❌ NO VERIFICADO | — |
| **Marcadores de Unity Profiler** | `Profiler.BeginSample`/`EndSample` | ❌ NO RECUPERADO (404 en URLs de docs) | — |

> ⚠️ **NOTA — verificación de realidad sobre auto-detección de SDKs de anuncios**: nuestro catálogo actual detecta AdMob/IS/AppLovin/Meta/UnityAds vía clases de activity conocidas + strings heurísticos de log. Estos patrones funcionan en la práctica pero **NO son contratos estables del proveedor**. La pasada de investigación sólo pudo verificar que los docs oficiales de AdMob **no** publican tags de logcat estables. Hace falta captura empírica para validar nuestros patrones existentes y para extender a Vungle/Chartboost/Mintegral/Pangle. Ver §8 decisión #9 y §9 cambio #6.

**Insight clave transversal**: **NINGUNA herramienta verificada hace auto-detección de ciclo de vida de anuncios sin cooperación del juego.** El protocolo logcat de GameBench ES el patrón — pero depende de que los devs cooperen con la convención `gb_marker_*`.

### 4.4 Niveles de detección propuestos

**TIER 1 — entregar pronto**:
- Adoptar protocolo logcat estilo GameBench: `perf_phase_start - <name>` / `perf_phase_stop - <name>` (cero sobrecoste, opcional, funciona con cualquier juego cooperativo).
- Señales del sistema Android vía dumpsys + átomos: `am_proc_start` (cold start), `am_resume_activity` (nav de pantalla), `dumpsys activity LaunchTime`.
- Cablear el `EventType.LOADING` existente a firmas Unity/Unreal/Cocos2d (brecha de auditoría #4).

**TIER 2 — medio plazo**:
- Heurísticas de firmas de frame-time para detección de carga (períodos sostenidos de frames dropeados rodeados por recuperación).
- Ingesta atrace vía fuentes de datos Perfetto (contadores Mali kbase publicados vía Perfetto en Android reciente).

**TIER 3 — diferir**:
- Auto-detección de SDKs de anuncios más allá del catálogo (requiere primero un laboratorio empírico de captura — §8 #9).
- Heurísticas de firma de métricas (salto de RAM + pico de red correlacionado con carga de anuncio, cinemática vía GPU sostenida + audio).

---

## 5. Framework de KPIs por fase

### 5.1 Catálogo maestro de KPIs

Cada KPI definido UNA SOLA VEZ aquí, puntuado de forma diferente por fase en §5.2.

| KPI | Definición | Unidad | Métrica fuente | Muestreo | Agregación | Ancla de umbral |
|-----|------------|------|---------------|----------|-------------|------------------|
| FPS media | media de FPS en ventana | fps | `dumpsys SurfaceFlinger` | 1 Hz | media | §3.1 juegos 30/20 FPS |
| FPS p1 | percentil 1 de FPS | fps | derivado | por frame | percentil | prensa §2.3 rigor PC |
| FPS p0.1 | percentil 0.1 de FPS | fps | derivado | por frame | percentil | prensa §2.3 rigor PC |
| Estabilidad FPS | % de frames dentro de ±10% del objetivo | % | derivado | por frame | ratio | convención GameBench |
| Frame time p99 | percentil 99 de tiempo de frame | ms | por frame | por frame | percentil | RAIL §3.2 16ms |
| Frames lentos | cuenta de frames > 16ms (60Hz) o >33ms (30Hz) | int | derivado | por frame | conteo | §3.1 >50% malo |
| Frames congelados | cuenta de frames > 700ms | int | derivado | por frame | conteo | §3.1 >0.1% malo |
| CPU media | % CPU medio | % | `adb top` / `dumpsys cpuinfo` | 0.5 Hz | media | dependiente del tier |
| CPU máx | % CPU pico | % | igual | igual | máx | dependiente del tier |
| GPU media | % de uso GPU medio | % | sysfs (Sprint 1 en pausa) | 0.5 Hz | media | dependiente del tier |
| GPU máx | % de uso GPU pico | % | sysfs | igual | máx | dependiente del tier |
| RAM media | RSS medio | MB | `dumpsys meminfo` | 0.5 Hz | media | dependiente del tier (§3.4 convención) |
| RAM máx | RSS pico | MB | igual | igual | máx | dependiente del tier |
| Temperatura media | temperatura piel media | °C | sysfs térmica | 0.5 Hz | media | específico del proveedor |
| Temperatura máx | temperatura piel pico | °C | sysfs | igual | máx | específico del proveedor |
| Eventos de throttling | cuenta de disparos de thermal throttle | int | `dumpsys thermalservice` | evento | conteo | 0 preferido |
| Red total | bytes totales RX+TX durante la fase | MB | `dumpsys netstats` | por fase | suma | §3.1 bg >50 MB/día malo |
| Ancho de banda red | throughput pico | KB/s | derivado | derivado | máx | — |
| Drenaje batería | mAh consumidos durante la fase | mAh | `dumpsys battery` | por fase | diff | §3.1 watch face 4.44%/h |
| **Cold start time** | tiempo desde lanzamiento al primer frame | ms | eventos ActivityManager | evento | duración | **§3.1 ≥5s LENTO** / BUENO <2s / OK 2-5s |
| **Warm start time** | tiempo desde background-a-foreground al primer frame | ms | evento | evento | duración | **§3.1 ≥2s LENTO** / BUENO <0.5s / OK 0.5-2s |
| **Hot start time** | tiempo de resume | ms | evento | evento | duración | **§3.1 ≥1s LENTO** / BUENO <0.2s / OK 0.2-1s |
| **TTID** (Time to Initial Display) | primera pintura | ms | evento | evento | duración | convención Sentry §2.2 |
| **TTFD** (Time to Full Display) | contenido totalmente cargado | ms | evento | evento | duración | convención Sentry §2.2 (opt-in) |
| Tiempo de carga | tiempo dentro de una fase de carga | ms | límites de fase | evento | duración | — |
| **Conteo ANR** | cuenta de ANRs durante la fase | int | logcat | evento | conteo | **§3.1 percibido por usuario ≥0.47% DAU malo** (cero por sesión única preferido) |
| Tasa de sesión lenta (FPS-target-aware) | % de frames perdiendo el objetivo por tier | % | derivado | por frame | ratio | §3.1 >25% = sesión lenta |
| Conteo de crashes | cuenta de crashes durante la fase | int | logcat / tombstones | evento | conteo | §3.1 percibido por usuario ≥1.09% DAU malo |
| Frame con hilo UI lento | input >24ms O hilo UI >8ms O GPU draw >12ms O bitmap upload >3.2ms | bool | derivado | por frame | conteo | §3.1 sub-presupuestos |
| **FPower** | mW por frame | mW/frame | `/sys/class/power_supply/battery/{current_now,voltage_now}` ÷ FPS | 1 Hz | media / por fase | §3.6 <50 / 50-65 / >65 |
| **CPU% normalizado** | CPU ajustado por frecuencia | % | cpu_crudo × freq_actual/freq_máx | 0.5 Hz | media | §3.6 (default PerfDog) |
| **PerfDog Jank count** | eventos de jank por fase | int | FT > 2×avg(3) AND > 84 ms | por frame | conteo | §3.6 |
| **PerfDog Big Jank count** | eventos de jank severo por fase | int | FT > 2×avg(3) AND > 125 ms | por frame | conteo | §3.6 |

**Diferencias respecto al esqueleto inicial**:
- Split cold/warm/hot start (tricotomía de Sentry §2.2 + Vitals §3.1).
- TTID + TTFD como métricas separadas (convención Sentry).
- ANR + crash como conteos de eventos.
- Tasa de sesión lenta consciente del FPS-objetivo (umbral Vitals juegos).
- Compuesto "frame con hilo UI lento" (sub-presupuestos Vitals §3.1).

### 5.2 Relevancia de KPIs por fase (refinada desde investigación)

Propuesta inicial — producto puede ajustar pesos en §8.

| Fase | **KPIs críticos** | Importantes | Útiles | Irrelevantes |
|-------|-------------------|-----------|--------------|------------|
| **Arranque app / init SDK** | Cold start time, TTID, RAM en boot, conteo de frames lentos primeros 5s | CPU pico (normalizado), conteo ANR, conteo crashes | Bytes de red durante init | GPU (idle), térmica, FPower |
| **Cinemáticas** | Estabilidad FPS, frame time p99, frames congelados | CPU media (normalizado), GPU media, frames lentos, FPower | RAM, temperatura, batería | TTID, cold start, red |
| **Tutoriales** | Estabilidad FPS, frames lentos, TTID por pantalla | CPU media (normalizado), RAM, FPower | GPU, red, frame time p99 | Cold start, throttling |
| **Carga de nivel / mapa** | Tiempo de carga, RAM pico, red total (bytes de carga) | CPU pico (normalizado), frame time p99 | GPU, temperatura, FPower | FPS (pantallas de carga suelen ser estáticas), TTID |
| **Navegación entre pantallas** | TTID por transición, frame time p99 | CPU pico (normalizado), delta RAM, frames lentos | GPU, red, FPower | Cold start, throttling |
| **Anuncios intersticiales** | Delta RAM, red total durante carga del anuncio, frame time al cerrar, frames lentos durante anuncio | CPU media (normalizado) | GPU, drenaje batería | FPS (FPS del vídeo del anuncio != FPS del juego), TTID, FPower |
| **Vídeo recompensado** | Igual que Intersticial + continuidad de FPS del vídeo | CPU (normalizado), GPU media | Temperatura | Cold start, throttling, FPower |
| **Gameplay (por defecto)** | FPS media, FPS p1, estabilidad FPS, temperatura media/máx, eventos de throttling, **FPower** | GPU media, CPU media (normalizado), RAM, tasa de sesión lenta, drenaje batería, PerfDog Jank count | Red | Cold start, TTID |

> **NOTA sobre CPU% normalizado**: es el REEMPLAZO para CPU% crudo allá donde CPU% crudo está listado actualmente. CPU% crudo se puede deprecar una vez la versión normalizada se valide contra capturas con thermal-throttling.

Pesos TBD por fase en §8 una vez producto confirme qué fases importan más para qué géneros de juego.

---

## 6. Sistema de puntuación

### 6.1 Objetivos

- Una sola puntuación 0-100 por sesión de juego
- Sub-puntuaciones por fase (un juego con mala carga pero buen gameplay muestra el split)
- Sub-puntuaciones por categoría de KPI (Suavidad, Uso de recursos, Térmica, Estabilidad)
- Comparable entre dispositivos (normalizado por clase de dispositivo)

### 6.2 Modelo de puntuación — tres opciones

**Modelo A — Umbral lineal (el más simple)**: cada KPI obtiene una puntuación 0-100 por interpolación lineal entre umbrales "bueno" y "malo". Puntuación de fase = media ponderada. Puntuación de sesión = media ponderada de puntuaciones de fase.
- Pro: simple, explicable, fácil de depurar
- Contra: bordes con cliff edges en los umbrales, no reconoce "excelente" vs "bueno"

**Modelo B — Sigmoide (estándar industrial para benchmarks)**: cada KPI mapea a través de un sigmoide. Pequeñas recompensas por estar ligeramente mejor que el umbral, gran penalización por estar mucho peor.
- Pro: suave, sin cliffs, fácil de ajustar la forma de la curva por KPI
- Contra: más difícil de explicar a responsables no técnicos, más difícil de depurar

**Modelo C — Bucket + multiplicador (sensación de gamificación)**: cada KPI cae en un bucket (S/A/B/C/D/F). Los buckets se agregan a una nota literal. Puntuación numérica opcional = punto medio del bucket × peso.
- Pro: fácil de comunicar ("tu juego sacó B+ en carga")
- Contra: pierde granularidad, vuelven los cliffs de umbral

**Recomendación para v1**: **Modelo A (Lineal) anclado en los umbrales de Android Vitals.**

> **Nota sobre anclaje de umbrales**: los puntos de anclaje de umbrales vienen de §3.1 — NO son arbitrarios, son aquello sobre lo que Google penaliza apps para la visibilidad en Play Store. Usar umbrales arbitrarios significaría que estamos puntuando algo distinto de lo que Play rankea. Evolucionar a Modelo B una vez ≥50 sesiones estén puntuadas y sepamos qué curvas importan.

### 6.2.1 Alternativas de puntuación compuesta

Más allá del Modelo A (Lineal ponderado), evaluamos dos compuestos:

**Smooth Index (convención PerfDog)**: `100 - puntuación_severidad_jank_ponderada`
- Pro: reconocible en industria, número único, comparable a informes PerfDog
- Contra: ponderación NO pública de PerfDog; nosotros definiríamos la nuestra (ventaja de transparencia)
- Decisión pendiente: §8 #10

**Señal de ranking alineada con Vitals**: % de sesiones diarias que exceden los umbrales "slow session" de Play (>25% frames bajo 30 FPS / 20 FPS)
- Pro: directamente alineado con impacto en visibilidad en Play
- Contra: requiere agregación multi-sesión (trabajo de tendencias Sprint 3+)

### 6.3 Ponderación por clase de dispositivo

El mismo umbral de KPI difiere según el tier de dispositivo:

| Tier | Definición | FPS objetivo | Presupuesto cold start | Margen RAM |
|------|------------|------------|-------------------|--------------|
| Gama alta | ≥ flagship 2024 | 60 fps | < 2s (BUENO), ≥5s LENTO (Vitals) | < 8 GB |
| Gama media | 2022-2023 | 30-60 fps | < 3s | < 4 GB |
| Gama baja | 2020-2021 o budget | 30 fps | < 5s | < 3 GB |

**De dónde obtener la clasificación de tier de dispositivo**: proponer **catálogo interno de proveedor** (única fuente de verdad, espejo del patrón `ThermalZoneClassifier`). Poblado desde bases de datos públicas de SoC. Las puntuaciones de SoC de **NanoReview** (§2.3) son un posible ancla externa — puntuación SoC compuesta 0-100 entre AnTuTu/GeekBench/3DMark, crowdsourced con entradas públicas por submisión con timestamps.

Ver §8 decisión #4.

### 6.4 Fórmula de agregación

```
kpi_score(kpi, fase) = mapear_a_0_100(valor_medido(kpi, fase), umbrales(kpi, tier_dispositivo))
phase_score(fase)    = suma_ponderada(kpi_score(kpi, fase) * peso(kpi, fase) for kpi in kpis_de(fase))
session_score        = suma_ponderada(phase_score(fase) * peso(fase) for fase in fases_presentes)
category_score(cat)  = suma_ponderada(kpi_score(kpi, fase) for (kpi, fase) where categoria(kpi) == cat)
```

Categorías propuestas:
- **Suavidad** (FPS media/p1/p0.1/estabilidad, frame time p99, frames lentos/congelados)
- **Uso de recursos** (CPU, GPU, RAM, red)
- **Térmica** (temperatura media/máx, eventos throttling)
- **Estabilidad** (conteo ANR, conteo crashes, frames con hilo UI lento, tasa de sesión lenta)
- **Capacidad de respuesta** (Cold/Warm/Hot start, TTID, TTFD)

### 6.5 Preguntas abiertas para producto

- ¿Un único número por juego o por (juego, dispositivo, build)?
- ¿Mostrar evolución en el tiempo (detección de regresión)?
- ¿Comparación por versión ("v3.4 vs v3.5 del mismo juego")?
- ¿Puntuaciones cara al público o sólo internas?
- ¿Cómo manejar fases ausentes (el juego no tiene cinemáticas — penalizar, ignorar o rebalancear pesos)?

---

## 7. Informe HTML compartible

### 7.1 Requisitos (solicitados por el usuario)

- Compartible vía enlace O PDF
- HTML preferido (compartir por enlace)
- Contenido equivalente al informe in-app

### 7.2 Estado actual del informe

- `ReportGenerator.kt` — salida HTML, auto-contenida
- v4.4.1 añadió banners diagnósticos en castellano formal tuteo
- v4.4.1 añadió renderizado N/D térmico
- Tabla unificada `#sec-events` con columna Origen (Auto / Manual)
- Mapeo de render para todos los valores `EventType` incluyendo `LOADING` y `FOREGROUND_LOSS` no usados

### 7.3 Patrones a COPIAR (de investigación de prensa §2.3)

1. **Anotación por juego `Ø60 (59-61)` de Notebookcheck** — la abreviatura avg(min-max) más limpia encontrada.
2. **Timeline emparejada puntuación+temperatura de Android Authority** — causalidad de un vistazo.
3. **Anotación por fila de dispositivo de GSMArena** (thumbnail de teléfono + chip + RAM + resolución) — alta densidad de info sin desorden.

### 7.4 Anti-patrones a EVITAR

1. Calificaciones compuestas opacas de un solo número (89% de Notebookcheck).
2. Gráficas no interactivas sólo en captura de pantalla (thermal throttling de GSMArena).
3. Ausencia de métricas de percentiles (p1, p0.1 — la mayoría de la prensa móvil los salta).

### 7.5 Huecos de mercado que DEBEMOS rellenar (nuestros diferenciadores)

- **Datos crudos descargables** (exportar CSV + JSON)
- **Percentiles de frame-time p1, p0.1 por defecto** — rigor PC en móvil
- **Segmentación por fase de gameplay** (carga vs combate vs cinemática) — sólo DF lo hace y sólo en YouTube
- **Timeline conjunta FPS + térmica + draw de potencia** en una sola URL compartible

### 7.6 Decisión de hospedaje

**Recomendación: HTML auto-contenido** (CSS + JS inline, sin CDN externa, sin telemetría) para compartir enlace de forma privada.
- Privacidad segura, funciona offline, enlace = URL de archivo local o adjunto de email, coste de infra cero.
- Opcional: variante hospedada por la empresa más tarde si el equipo necesita URLs versionadas.

Ver §8 decisión #6.

---

## 8. Decisiones pendientes (para el responsable de producto)

| # | Decisión | Recomendación |
|---|----------|----------------|
| 1 | **Lista de juegos a testear** (cuáles de nuestros juegos en scope) | Necesita lista interna de producto. Sin recomendación externa posible. |
| 2 | **Elección de benchmark competidor por juego** | **Prueba de pago de 1 mes de GameBench** (suficiente para calibración de paridad) Y tier de investigación gratuito de PerfDog donde aplique. |
| 3 | **Pesos de KPIs por fase** (§5.2) | Usa los valores iniciales propuestos en §5.2 como punto de partida. Producto puede ajustar tras las primeras 10 sesiones. |
| 4 | **Fuente de tabla de tiers de dispositivo** (§6.3) | Catálogo interno de proveedor (espejo del patrón `ThermalZoneClassifier`). Puntuaciones SoC de NanoReview como ancla externa para población inicial. |
| 5 | **Modelo de puntuación** (§6.2) | **Modelo A (Lineal)** v1, anclado en umbrales de Android Vitals (§3.1). Evolucionar a Modelo B una vez ≥50 sesiones puntuadas. |
| 6 | **Hospedaje del informe** (§7.6) | **HTML inline auto-contenido** (sin CDN, sin telemetría). Variante hospedada diferida. |
| 7 | **Cuenta de prueba de pago de GameBench** | **SÍ** — para calibración de paridad. 1 mes de prueba suficiente. |
| 8 | **Visibilidad de la puntuación** | **Sólo interno inicialmente.** Cara al público requiere revisión legal. |
| 9 | **Laboratorio empírico de captura de SDKs de anuncios** (NUEVO) | **PROPUESTA: SÍ — 1 día de laboratorio.** Capturar tags de logcat por defecto de AdMob/IS/AppLovin/Meta/UnityAds/Vungle/Mintegral con juegos reales. **SI SÍ** → desbloquea auto-detección Tier 3 para eventos de anuncios + valida patrones del catálogo de 5-SDK existentes. **SI NO** → la cobertura de eventos de anuncios se queda al nivel de cooperación-del-juego (estado actual). |
| 10 | **Implementación de Smooth Index** (§6.2.1) | ¿Adoptar Smooth Index de PerfDog junto al Modelo A Lineal? Si SÍ, definir nuestra propia ponderación — recomendamos pesos publicados transparentes (ventaja de transparencia vs ponderación cerrada de PerfDog). |
| 11 | **Umbrales ancla de FPower** (§3.6) | ¿Confirmar las anclas <50 / 50–65 / >65 mW/frame de los estudios de caso de PerfDog, o correr nuestras propias bases por juego por tier de dispositivo? Recomendamos correr las nuestras para anclar el suelo en Mali + Adreno + PowerVR de forma independiente. |

---

## 9. Próximos cambios SDD propuestos

Priorizados por ROI × esfuerzo (auditoría obs #308 + roadmap obs #289):

| # | Cambio | Esfuerzo | Depende de | Notas |
|---|--------|--------|------------|-------|
| 1 | **`event-segmentation-coverage`** | **5-6d total** | Ninguna | Sprint 0 refactor `SdkSignature.openPatterns: List<(Regex, EventType)>` (1d) → Sprint 1 APP_STARTUP + SDK_INIT (2d) → Sprint 2 SCREEN_TRANSITION (0.5d) + cableado de firmas LEVEL_LOADING (0.5d) + split Rewarded (1d). Sprint 3 opcional instrumented mode CINEMATIC/TUTORIAL (1d). |
| 2 | **`kpi-scoring-framework`** | **4-5d** | Este doc finalizado + decisiones de producto §8 | Catálogo (§5.1) + modelo Lineal + pesos por fase + tabla de tier de dispositivo |
| 3 | **`shareable-html-report`** | **2-3d** | #2 puntuación implementada | Inline auto-contenido + descarga CSV/JSON + percentiles p1/p0.1 en el renderer |
| 4 | **`gpu-usage-percent`** | **~4.25d** al retomarlo | Ninguna | Sprint 1 EN PAUSA. Las tareas ya existen en `openspec/changes/gpu-usage-percent/`. Cobertura Mali+Adreno+PowerVR. Mali post-Android 12 necesita root para frecuencia; el uso% no. Adreno A13+ necesita `echo 1 > /sys/class/kgsl/kgsl-3d0/perfcounter`. |
| 5 | **`network-bandwidth`** | **~3d** | #2 puntuación decide la prioridad | Sprint 2 de `roadmap/gamebench-parity`. `dumpsys netstats detail --uid <uid>` bytes totales RX/TX. SIN datos por conexión (requiere hooks de libc, traiciona zero-touch). |
| 6 | **`ad-sdk-empirical-capture-lab` (NUEVO)** | **1d investigación/lab** | §8 #9 = SÍ | Capturar logcat por defecto de 7 SDKs de anuncios con juegos reales + 2 dispositivos (Mali + Adreno). Salida: actualizaciones del catálogo de firmas + matriz de verificación por SDK. |
| 7 | **`loading-event-signatures-quickfix` (NUEVO)** | **0.5d independiente** | Ninguna | Cablear firmas Unity/Unreal/Cocos2d al tipo `EventType.LOADING` existente. Alto ROI, bajo coste. Arregla brecha de auditoría #4 — auto-detección actualmente muerta. |
| 8 | **`fpower-metric` (NUEVO, post-PerfDog)** | **2-3d ALTO ROI** | Ninguna | FPower (mW/frame) leído de `/sys/class/power_supply/battery/{current_now,voltage_now}` ÷ FPS. Independiente del framework de puntuación KPI, puede salir primero. Anclas §3.6 / §8 #11. |
| 9 | **`cpu-freq-normalized` (NUEVO, post-PerfDog)** | **0.5d** | Empaquetar en #2 `kpi-scoring-framework` | `cpu_crudo × freq_actual/freq_máx`. Default PerfDog; elimina distorsión por throttling. §3.6. |
| 10 | **`perfdog-jank-formula` (NUEVO, post-PerfDog)** | **0.5d** | Empaquetar en #2 `kpi-scoring-framework` | Jank Simple (>2×avg(3) AND >84 ms) + Big Jank (>125 ms) como columnas KPI separadas junto al slow-frame de Vitals. §3.6. |
| 11 | **`cli-headless-mode` (NUEVO, post-PerfDog)** | **3-4d** | Ninguna | Entrada Picocli/kotlinx-cli. Reusa pipeline de captura. Salida JSON. Código de salida cuando se viola umbral. **Desbloquea #12 y #13.** |
| 12 | **`gh-action-wrapper` (NUEVO, post-PerfDog)** | **1-2d** | #11 | Envuelve CLI en GitHub Action para CI. Contador directo al pricing enterprise de PerfDog Service para CI/CD. |
| 13 | **`multi-device-capture` (NUEVO, post-PerfDog)** | **3-5d** | Ninguna | Sesiones tabuladas ≤3 en GUI (paridad con límite PerfDog) + ilimitadas vía CLI (#11). Viewmodels por dispositivo independientes. |
| 14 | **(DIFERIR) `engine-mode-perfetto-capture`** | **5-10d** | Ninguna | Captura atrace vía `adb shell perfetto`. Sólo bajo demanda explícita del usuario. Tensión anti-no-SDK — diferir hasta que se pida. |

**Orden recomendado** (impacto × dependencia):
1. #7 quickfix de firmas LOADING (0.5d independiente, ROI inmediato sobre código muerto existente)
2. #8 `fpower-metric` (2-3d, sale independientemente, cierra la mayor brecha PerfDog)
3. #1 event-segmentation-coverage (desbloquea la puntuación con límites de fase apropiados)
4. #2 kpi-scoring-framework + empaquetados #9 + #10 (anclas §3.1 Vitals + fórmulas §3.6 PerfDog)
5. #11 `cli-headless-mode` (desbloquea posicionamiento CI/CD vs PerfDog Service)
6. #3 shareable-html-report (el premio cara al usuario)
7. #12 `gh-action-wrapper` (tras #11)
8. #13 `multi-device-capture` (tras línea base CLI estable)
9. #4 gpu-usage-percent (retomar Sprint 1 cuando GPU sea crítico para puntuar)
10. #6 ad-sdk-empirical-capture-lab (si §8 #9 = SÍ)
11. #5 network-bandwidth (Sprint 2 del roadmap existente)
12. #14 DIFERIR `engine-mode-perfetto-capture` hasta que se pida

---

## 10. Declaración de posicionamiento

> **Perfilador de rendimiento Android local-first, de metodología abierta y gratuito para equipos QA que necesitan perfilar builds release sin subir IP a la nube de terceros, sin integración de motor, sin lock-in de proveedor. Complementa (no reemplaza) las herramientas de deep-dive de proveedor (Snapdragon Profiler / ARM Streamline / Unity Profiler).**

Diferenciadores concretos reforzados por el análisis competitivo (2026-05-12):
- **Transparencia metodológica** — cada fórmula y cada umbral que usa nuestra puntuación se publica en este doc y en las specs openspec. PerfDog Help Center confirmado cerrado el 2026-05-12 (engram `research/perfdog-help-center-2026-05-12`, obs #328) — su metodología de medición requiere login para acceder. Nuestra ventaja: reproducibilidad, auditabilidad, sin puntuación de caja negra; cualquier ingeniero QA puede re-derivar nuestros números desde los endpoints `/sys` / `dumpsys` / Perfetto documentados sin onboarding de proveedor.

Anclado en (refinado post-PerfDog deep-dive, obs #312):
- **Soberanía del dato** — sin nube, nunca. PerfDog requiere upload obligatorio a perfdog.qq.com o perfdog.wetest.net con silos duros de cumplimiento entre China e Internacional (sin opción del usuario). Nosotros nunca salimos del host.
- **Metodología abierta** — cada métrica viene de endpoints públicos `/sys` / `dumpsys` / Perfetto y está documentada inline. Las fórmulas Jank/Smooth Index/FPower de PerfDog son parcialmente públicas (§3.6) pero los pesos y umbrales de SmallJank son cerrados.
- **Gratis para uso comercial** — PerfDog es gratis sólo para uso no comercial / investigación; comercial requiere contacto de ventas con pricing bajo NDA.
- **Perfilado de APK release** — sin `debuggable=true` requerido. Paridad con PerfDog/GameBench, gana a Unity Profiler + Android Studio Profiler.
- **CI/CD-first** — `cli-headless-mode` planeado (§9 #11) + `gh-action-wrapper` (§9 #12) apuntan al mercado indie/mid-tier que PerfDog Service deja fuera de precio.
- **Multi-dispositivo por diseño** — `multi-device-capture` planeado (§9 #13) iguala el límite GUI de PerfDog ≤3 + añade ilimitado vía CLI (paralelo = a nivel SO, no limitado por adb según Q&A de Awen Cao).

Explícitamente **NO** competimos en:
- Contadores HW de GPU (alianzas con proveedores requeridas, foso de PerfDog — usar Snapdragon Profiler / ARM Streamline como complementos)
- Dashboards en la nube / features de colaboración de equipo (anti-posicionamiento)
- Métricas SDK de motor (Unity Mono / UE stat / draw calls / memoria de textura — principio anti-no-SDK)
- RUM en producción (categoría de producto distinta — usar Firebase Perf / Sentry / Embrace)
- Latencia de input táctil (necesita automatización UI o build instrumentado)
- Biblioteca de benchmark de 200k apps a escala de efecto-red (foso de PerfDog, no nuestro)

---

## 11. Referencias

### Observaciones engram

- `research/competitive-analysis-direct-tools` (#303) — PerfDog, Snapdragon Profiler, ARM Streamline, Unity Profiler, Android Studio Profiler
- `research/competitive-analysis-apm-rum` (#304) — Firebase Perf, Sentry, New Relic, Embrace, Android Vitals
- `research/competitive-analysis-press-reports` (#305) — GSMArena, Notebookcheck, Android Authority, Digital Foundry, NanoReview
- `research/event-segmentation-state-of-art` (#306) — marcadores GameBench, traces Firebase, transacciones Sentry, Perfetto, verificación de realidad de SDKs de anuncios
- `research/gamebench-comparison` (#288) — matriz completa de features de GameBench
- `research/gamebench-docs-gpu-section` (#298) — especificidades GPU de GameBench
- `roadmap/gamebench-parity` (#289) — roadmap de paridad en 3 sprints
- `audit/event-segmentation-coverage-2026-05-12` (#308) — auditoría interna de cobertura actual de la herramienta, matriz de brechas
- `research/market-kpis-official-sources` (#309) — Google Android Vitals, RAIL, presupuesto de lanzamiento Apple iOS
- `docs/competitive-analysis-skeleton-2026-05-12` (#307) — esqueleto precursor de este doc

### Documentos de trabajo

- `GAMEBENCH-COMPARISON.md` (raíz del proyecto)
- `openspec/specs/core/spec.md` (superficie actual de capacidades)
- `openspec/changes/gpu-usage-percent/` (tareas Sprint 1 en pausa)
- `CLAUDE.md` (reglas del proyecto)

### URLs externas (citadas inline arriba)

**Autoritativas (verificadas en esta pasada, 2026-05-12)**:
- <https://support.google.com/googleplay/android-developer/answer/9844486> — Android Vitals
- <https://web.dev/articles/rail> — Modelo de rendimiento RAIL
- <https://developer.apple.com/videos/play/wwdc2019/423/> — Apple Optimizing App Launch (presupuesto 400 ms)
- <https://developer.apple.com/app-store/review/guidelines/> — Apple Store Review (cualitativo)
- <https://firebase.google.com/docs/perf-mon> — Firebase Performance (sin umbrales publicados)
- <https://firebase.google.com/docs/perf-mon/custom-code-traces> — Firebase traces custom
- <https://docs.sentry.io/product/dashboards/sentry-dashboards/transaction-summary/> — Transacciones Sentry
- <https://docs.sentry.io/product/dashboards/sentry-dashboards/mobile/mobile-vitals/> — Sentry Mobile Vitals
- <https://docs.newrelic.com/docs/mobile-monitoring/new-relic-mobile/get-started/introduction-app-launch-times/> — New Relic Mobile
- <https://embrace.io/blog/mobile-app-performance-metrics/> — top 10 métricas de Embrace
- <https://perfetto.dev/docs/instrumentation/tracing-sdk> — SDK Perfetto
- <https://docs.gamebench.net/general-information/programmatic-markers/> — protocolo de marcadores GameBench
- <https://developers.google.com/admob/android/interstitial> — AdMob (sin tags de logcat estables)
- <https://docs.gamebench.net/> — raíz de docs GameBench
- <https://docs.gamebench.net/docs/web-dashboard/session-detail/metrics-timeline/> — layout visual Metrics Timeline de GameBench (§2.5, recuperado 2026-05-18)
- <https://docs.gamebench.net/docs/web-dashboard/session-detail/comparison/> — vista de Comparación de sesiones de GameBench (§2.5, recuperado 2026-05-18)
- <https://docs.gamebench.net/docs/web-dashboard/trends/> — GameBench Trends Explorer (gráficas de distribución + tabla de rendimiento por dispositivo) (§2.5, recuperado 2026-05-18)
- <https://docs.sentry.io/product/insights/mobile/mobile-vitals/> — dashboard Sentry Mobile Vitals (§2.5, recuperado 2026-05-18)
- <https://docs.sentry.io/product/dashboards/sentry-dashboards/mobile/mobile-vitals/> — Sentry Mobile Vitals (bandas RAG Bueno/Regular/Malo) (§2.5, recuperado 2026-05-18)
- <https://docs.unity3d.com/Manual/profiler-window-navigating.html> — navegación de ventana Unity Profiler (gráficas de módulo apiladas) (§2.5, recuperado 2026-05-18)
- <https://www.qualcomm.com/developer/software/snapdragon-profiler> — landing Snapdragon Profiler (§2.5, JS-SPA, sólo landing alcanzable, recuperado 2026-05-18)
- <https://perfdog.qq.com/> / <https://perfdog.wetest.net/> — landing pages PerfDog
- <https://perfdog.wetest.net/helpCenter> — Help Center PerfDog (verificado cerrado 2026-05-12, engram obs #328: JS-SPA + probable muro de auth, 12 fetches confirmaron shell idéntico, cero features nuevas extraíbles)
- <https://www.wetest.net/blog/mobile-game-performance-testing-2026-perfdog-guide-1189.html> — blog WeTest #1189: entrevista al dev fundador de PerfDog (Awen Cao) por el Sr. PM Baojian Shen, Marzo 2026. Fuente pública individual con mayor información sobre PerfDog (fórmula Jank, fórmula FPower, SmallJank, Smooth Index, lista de 11 plataformas, plugins CI/CD, límite de 3 dispositivos en GUI, Custom Data API). Citada en §3.6.
- <https://docs.unity3d.com/Manual/Profiler.html> — manual Unity Profiler
- <https://developer.arm.com/Tools%20and%20Software/Streamline%20Performance%20Analyzer> — ARM Streamline

**Referencias de prensa** (verificadas):
- <https://www.gsmarena.com/vivo_x300_ultra-review-2957p4.php>
- <https://www.notebookcheck.net/Samsung-Galaxy-S25-review-The-star-among-compact-smartphones-is-losing-ground.989246.0.html>
- <https://www.androidauthority.com/galaxy-s25-series-performance-3521707/>
- <https://nanoreview.net/en>
- <https://quality.gamebench.net> (herramienta partner nombrada por Notebookcheck)

**Pendientes / no verificadas en esta pasada** (recuperación manual requerida):
- E-books de rendimiento móvil Unity / Unreal (JS-SPA, descarga manual)
- Best Practices ARM Mali, Qualcomm Adreno Game Developer Guide, Apple Metal Best Practices (JS-SPA, descarga PDF)
- Sony TRC / Microsoft Xbox XR / Nintendo Lotcheck (bajo NDA)
- Patrones de logcat IronSource / AppLovin / Vungle / Mintegral (docs sólo de marketing, necesita captura empírica — §9 #6)
- Docs técnicos en inglés de PerfDog (sólo marketing chino)

---

## 12. Changelog

- **2026-05-12** — Consolidación inicial desde 7 observaciones de investigación + auditoría interna. Reemplaza el esqueleto (`docs/competitive-analysis-skeleton-2026-05-12` obs #307). Los 22 marcadores `<!-- AWAITING -->` resueltos; las 8 tablas placeholder rellenadas. Estado del documento: investigación completada, pendientes decisiones de producto §8 antes de que puedan empezar los cambios SDD #2/#3. Notas de transparencia preservadas sobre certificaciones de consola (NDA), PDFs de motor/proveedor (descarga manual requerida) y auto-detección de SDKs de anuncios (NO verificado más allá del catálogo actual de 5-SDK).
- **2026-05-12 (PM)** — Integración de deep-dive de PerfDog. Añadidas §3.6 fórmulas PerfDog (Jank / SmallJank / Smooth Index / FPower / CPU% normalizado por frecuencia), §5.1 +4 KPIs (FPower + CPU normalizado + PerfDog Jank count + PerfDog Big Jank count), §5.2 relevancia por fase actualizada (ponderación FPower + CPU normalizado), §6.2.1 alternativas de puntuación compuesta (Smooth Index + señal de ranking alineada con Vitals), §8 +2 decisiones (#10 Smooth Index, #11 anclas FPower), §9 +7 cambios SDD (#8 fpower-metric, #9 cpu-freq-normalized, #10 perfdog-jank-formula, #11 cli-headless-mode, #12 gh-action-wrapper, #13 multi-device-capture, #14 DIFERIR engine-mode-perfetto-capture), fila PerfDog §2.1 actualizada con hallazgos del deep-dive (nube obligatoria, 11 plataformas, 1 Hz por defecto, Custom Data Extension SDK, brechas cerrables vs no cerrables). Nueva §10 Declaración de posicionamiento. Renumeradas Referencias → §11, Changelog → §12. Fuente: engram obs #312 (`research/perfdog-deep-dive-2026-05-12`).
- **2026-05-12 (PM 2)** — Deep-dive del Help Center de PerfDog (engram obs #328). Sitio confirmado cerrado; cero features nuevas extraíbles. Ángulo de transparencia metodológica añadido a §10 posicionamiento.
- **2026-05-18** — Añadida §2.5 "Cómo presentan visualmente los datos de rendimiento las herramientas competidoras" (renumerada desde §2.4 para evitar colisión con el reordenamiento paralelo de §2.3/§2.4 commiteado en `770f3c4`). Cubre 8 herramientas (GameBench, PerfDog, Snapdragon Profiler, ARM Streamline, Unity Profiler, Android Studio Profiler, Firebase Perf, Sentry Mobile Vitals). Destila layouts de dashboard, KPI cards, tipos de gráfica, codificación de severidad, patrones de drill-down y vistas de comparación. Hace surface a 10 mejores patrones a copiar, 6 anti-patrones a evitar y 6 recomendaciones priorizadas para nuestro informe HTML (§7) — las 3 principales empaquetadas en el cambio SDD `shareable-html-report` (§9 #3). Notas de transparencia preservadas sobre herramientas cuya UI está cerrada/JS-SPA (PerfDog wetest, Snapdragon Profiler, ARM Streamline, AS Profiler — marcadas como "necesita verificación" donde el acceso directo a capturas falló). Engram obs `research/competitor-viz-patterns`.
- **2026-05-25** — Traducción completa al castellano formal tuteo (España) preservando todos los datos técnicos, URLs, nombres de productos y referencias engram. Texto adaptado para audiencia de producto + QA: oraciones más cortas donde el original era denso, terminología técnica preservada (FPS, percentiles, ANR, etc.).