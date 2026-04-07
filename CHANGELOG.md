# Changelog

All notable changes to this project are documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Each release uses three sections:

- **Que hay de nuevo** — user-facing changes in plain Spanish, no jargon. This is what the
  in-app update banner shows in the mini-changelog (parsed automatically from the GitHub
  release body).
- **Arreglos** — bug fixes also in plain language.
- **Detalles tecnicos** — implementation notes for developers (refactors, libraries, file
  changes, root causes). The in-app banner ignores this section.

## [3.1.12] — 2026-04-07

### Que hay de nuevo

- **Reproductor de video resistente a segmentos corruptos**: si durante una grabacion algun segmento queda dañado (porque el chain del recordJob lo cortó antes de tiempo), el sistema ahora descarta el segmento roto y une los buenos. Vas a tener el video aunque sea parcial, en vez del error "No se pudo leer la duración del video"
- Mensajes de error del reproductor mucho mas claros: si el video no se puede leer, ahora te dice exactamente por que (archivo no existe / archivo vacio / moov atom dañado por interrupcion)
- Cuando algunos segmentos quedaron corruptos, vas a ver un aviso amarillo claro ("El video se grabo solo parcialmente") en vez de descubrirlo silenciosamente

### Arreglos

- **Bug critico, regresion de v3.1.9**: el reproductor mostraba "No se pudo leer la duración del video" en sesiones donde el chain del recordJob había producido un primer segmento corrupto. Root cause: cuando v3.1.9 introdujo el concat de segmentos, si un segmento estaba corrupto el concat fallaba entero y el codigo caia al fallback `recordings.first()` que era... el segmento corrupto. El usuario perdia el video entero por culpa de un solo segmento roto al inicio
- **Bug critico de grabacion**: el chain del recordJob esperaba solo 1 segundo entre `stopScreenRecord` y el siguiente `startScreenRecord`. Eso no le daba tiempo a Android a flushear el moov atom del MP4 al disco, dejando el segmento `_0.mp4` (y a veces los siguientes) sin metadata MP4 — son archivos de bytes validos pero ningun reproductor los puede leer. Aumentado a 3 segundos por chain step. El delay post-stop final ya era de 2 segundos pero tambien lo subi a 3 por consistencia
- Si todos los segmentos de una sesion estan corruptos, ahora se muestra un mensaje explicito en vez de fallar silenciosamente

### Como probar

1. Abrir la app actualizada
2. Si tenes una sesion que mostraba "No se pudo leer la duración del video", deberia funcionar ahora (puede tener menos duracion total si algun segmento se descartaba). Tambien podes abrir la sesion del Pixel XL del 7 abril 15:06 que estaba rota y ahora deberia mostrar 7:00 de video reproducible (en lugar de 10:00 que era la duracion original — perdimos los primeros 3 minutos por el `_0.mp4` corrupto, pero el resto se recupero)
3. Capturar una sesion nueva de mas de 3 minutos (para forzar el chain del recordJob). Verificar que el video resultante se reproduce sin errores
4. Si por alguna razon un segmento queda corrupto, vas a ver un banner amarillo "El video se grabo solo parcialmente" durante o despues de la captura

### Detalles tecnicos

#### Bug #1 — Segmentos corruptos rompian todo el video (regresion v3.1.9)

- **Root cause**: `AdbBridge.concatSegments` invocaba `ffmpeg -f concat` sin validar primero que cada segmento fuera legible. ffmpeg concat demuxer falla entero en el primer input invalido (`moov atom not found`), retornaba null al caller, y el caller (`AppViewModel.startCapture` linea ~752) hacia `concatenated?.absolutePath ?: recordings.first().absolutePath` — cayendo al `first()` que era el `_0.mp4` corrupto. El user terminaba con un `videoPath` apuntando a un archivo dañado.
- **Caso real del usuario**: sesion del 7 abril 15:06 en Pixel XL. 4 segmentos en disco: `_0.mp4` (7.3 MB, corrupto), `_1.mp4` (41 MB), `_2.mp4` (42 MB), `_3.mp4` (17 MB). Total valido = 7 minutos sobre 10 originales. v3.1.11 fallback al concat → `videoPath = _0.mp4` → reproductor abre el archivo → ffprobe falla → "No se pudo leer la duración del video".
- **Fix #1 (`AdbBridge.concatSegments`)**: nueva validacion via `isValidVideoFile(file: File): Boolean` que llama a ffprobe con `-show_entries format=duration` y verifica que retorne un duration > 0. Si ffprobe falla / timeout / no duration → invalid. La funcion `concatSegments` ahora filtra los segmentos antes de armar el manifest del concat y skipea los invalidos. Si despues del filtro queda 1 archivo, lo retorna directo (sin concat). Si quedan 2+, los concatena. Si quedan 0, retorna null y el caller surface a `_captureWarning`.
- **Fix #2 (`AppViewModel.startCapture`)**: el fallback ahora usa `firstValidSegment` en vez de `first()`. Si concat falla pero hay al menos un segmento valido, usa ese y setea `_captureWarning` con el mensaje correspondiente. Si todos estan corruptos, `videoPath = ""` y warning.
- **Fix #3 (`EmbeddedVideoPlayer.kt`)**: el mensaje de error "No se pudo leer la duración del video" se reemplazo por un mensaje contextual segun la causa: archivo no existe / archivo vacio / archivo dañado (moov atom corrupto). El nuevo mensaje del moov-atom dice explicitamente "Las metricas del reporte siguen siendo validas" para que el usuario no piense que perdio toda la sesion.
- **Validacion**: 7 nuevos unit tests en `ConcatResilienceTest.kt` (gateado por `RUN_FFMPEG_TESTS=true`) que generan archivos sinteticos validos y corruptos via ffmpeg con `lavfi testsrc` source y verifican: (a) `isValidVideoFile` detecta corruptos, (b) `concatSegments` skipea el primer corrupto y produce output valido con los demas, (c) `concatSegments` retorna null cuando todos son corruptos, (d) `concatSegments` retorna el unico segmento valido si los demas estan corruptos, (e) caso happy path con 2 segmentos validos. **Ademas** se corrio un test E2E one-shot contra los archivos REALES del usuario (Pixel XL 4 segmentos) que confirmo: seg0 detectado como invalido, seg1/seg2/seg3 validos, concat produce 100MB unified file playable. El test E2E se borro despues porque depende de archivos especificos del usuario.

#### Bug #2 — Chain delay insuficiente, root cause de los segmentos corruptos

- **Root cause**: el `recordJob` chain en `AppViewModel.startCapture` hacia `delay(1000)` entre `stopScreenRecord` y el siguiente `startScreenRecord`. `stopScreenRecord` invoca `process.destroyForcibly()` que mata el `adb shell` en el PC, pero el binario `screenrecord` corriendo en el device necesita tiempo para flushear el moov atom del MP4 a `/sdcard`. 1 segundo era insuficiente en devices low-end (Pixel XL Android 10). Resultado: el segmento que se estaba grabando quedaba con frames + ftyp box pero sin moov box → invalido para cualquier reproductor.
- **Fix**: aumentado de `delay(1000)` a `delay(3000)` en el chain (linea ~520 de `AppViewModel`). Tambien aumentado el `delay(2000)` post-stop final a `delay(3000)` por consistencia. El overhead extra sobre una sesion de 10 minutos es ~8 segundos = 1.3% — totalmente aceptable a cambio de no perder el video.
- **No previene 100% de casos**: si Android esta haciendo I/O pesado (juego con muchos assets, GC pause, etc.), 3 segundos podrian no ser suficientes. Pero el fix #1 (concat resiliente) asegura que aunque pase, no perdes el resto del video.

#### Reparacion retroactiva del usuario afectado

- La sesion rota del usuario (Pixel XL 7 abril 15:06, id `1775567795318`) se reparo manualmente durante la investigacion del bug: ffmpeg concat de los 3 segmentos validos (`_1`, `_2`, `_3`) → `video_20260407_150625.mp4` (100 MB, 7:00 duracion), y `videoPath` en `history.json` actualizado. Backup del history original en `history.json.backup-pre-3.1.12`. La sesion ya es reproducible AHORA, antes de que el usuario instale v3.1.12.
- Para futuros casos similares, `FileCleanup.repairTruncatedVideos` (que corre en startup desde v3.1.9) tambien hereda el fix automaticamente: ahora valida cada segmento antes de concatenar y skipea los corruptos.

### Pendiente para futuras versiones

- v3.1.13 o posterior: capturar el stderr del proceso `screenrecord` en el chain (no solo en el initial start) para diagnosticar por que algunos chain steps producen segmentos corruptos. Actualmente sabemos que el delay de 3 segundos previene la mayoria de los casos pero no sabemos si hay devices donde aun falla.
- v3.1.13 o posterior: agregar un boton "Reparar videos viejos" en la pantalla de history que fuerce un re-run de `repairTruncatedVideos` con el fix nuevo de filtrar corruptos.

## [3.1.11] — 2026-04-07

### Que hay de nuevo

- **Zoom en la timeline del reporte**: ahora podes hacer Ctrl + rueda del raton sobre la timeline para ampliar la zona donde se producen las caidas de FPS. Doble clic para resetear al ver toda la sesion. Especialmente util en grabaciones largas (10+ minutos)
- Cuando el video no se puede grabar (algunos dispositivos rechazan screenrecord), ahora ves un aviso amarillo claro durante la captura en vez de descubrir al final que el video estaba vacio. Las metricas siguen registrandose normal
- Las notas de los reportes son menos estrictas: un Huawei Y5 Lite a 28-30 FPS estables ahora se evalua como A o B (que es lo justo para ese hardware), no como D
- Mejor deteccion de GPUs PowerVR de gama baja (Y5 Lite y similares) y otras GPUs con prefijos del fabricante

### Arreglos

- **Bug critico de scoring**: el GPU PowerVR Rogue GE8300 del Huawei Y5 Lite no se reconocia porque vendors reportan `Imagination Technologies, PowerVR Rogue GE8300` y el codigo solo buscaba `powervr ge8300` literal. La palabra `Rogue` en el medio rompia el match. Resultado: el device caia a tier UNKNOWN y le aplicaba expectativas de telefono flagship (60 FPS), penalizandolo brutalmente
- **Bug critico de scoring**: el tier UNKNOWN tenia los mismos `expectedFps=60, fpsFloor=30` que los telefonos flagship. Cualquier device no reconocido se evaluaba con expectativas de Snapdragon 8 Gen 3. Cambiado a `45/30` (mid-range razonable)
- **Bug critico de scoring**: los penalties intermedios eran demasiado agresivos. Un device corriendo al 90% de su expectativa se castigaba con -15 puntos. Suavizado a -3 / -7 segun cuan cerca este del esperado
- **Bug critico de captura**: cuando elegias un tiempo predefinido de captura (10 min, 5 min, etc.), a veces el video no se grababa en absoluto y el sistema lo tapaba sin mostrarte error. Detras: `screenrecord` puede morir en ~100ms con exit code != 0 (resolucion no soportada por el encoder, falta de permisos, codec rechazado) sin lanzar excepcion. Ahora el codigo verifica que el proceso siga vivo despues del warm-up de 1.5s y, si murio, intenta automaticamente con el perfil estandar (720p) y muestra un aviso amarillo claro al usuario si los dos intentos fallan
- Sentinel `avgFps == 0` ahora se interpreta como "el tool no pudo medir el FPS" en vez de "el device es horrible". Devuelve grade F + score 0 distinguible de un device real con problemas

### Como probar

1. Abrir la app actualizada
2. **Para el zoom de timeline**: abrir cualquier reporte de session anterior, scrollear hasta la timeline grande del reporte. Hacer Ctrl + rueda del raton hacia adelante (zoom in) sobre una zona donde haya caidas de FPS. Verificar que (a) la timeline se amplifica anchored al cursor, (b) los ticks de tiempo se vuelven mas densos automaticamente, (c) el hint de abajo cambia a "Zoom: X visible". Doble click resetea.
3. **Para el video predefinido**: capturar una sesion con tiempo predefinido (ej. 5 o 10 minutos). Si tu device tiene problemas con screenrecord, ahora vas a ver un banner amarillo durante la captura. Las metricas siguen funcionando.
4. **Para el scoring**: capturar una sesion en un Huawei Y5 Lite (o cualquier device de gama baja con PowerVR). Verificar que el tier detectado en el reporte ya no diga "Unknown" y que el grade sea coherente con la performance real del device.

### Detalles tecnicos

#### Bug #1 — Zoom de timeline (`InteractiveTimeline.kt`)

- Rediseñado con viewport state local (`viewStartMs`, `viewEndMs`) que reemplaza los calculos hardcoded de `(0, durationMs)`. Todos los calculos de coordenadas (X de marker, X de playhead, X de tick, X del path del FPS line, X del seek por drag, X del long-press para marker) ahora usan el viewport.
- Mouse scroll handler con `awaitPointerEvent()` + `event.keyboardModifiers.isCtrlPressed`. Sin Ctrl el scroll se forwarda al ancestor. Con Ctrl, zoom factor 0.85x por tick (in) o 1.18x por tick (out) — simetrico para que entrar y salir del zoom usen el mismo total de scrolls.
- **Anchored zoom**: el cursor X define el time-pivot que queda estacionario durante el zoom. La fraccion del cursor a lo largo del viewport se mantiene constante antes y despues del zoom, asi que la time-bajo-el-cursor no se mueve.
- Tick interval auto-adaptativo via `chooseTickInterval(viewDurationSec)` con breakpoints hand-picked: 1s para <10s view, 2s para <30s, 5s/10s/20s/30s/60s/120s segun escala. Garantiza ~6-12 ticks visibles a cualquier zoom.
- Doble click resetea el viewport (`onDoubleTap` en `detectTapGestures`).
- Hint del bottom cambia de "Clic para posicionar..." a "Zoom: X visible..." cuando el viewport esta zoomed.
- `drawFpsLineWithFill` filtra `fpsData` a `[viewStart - 1s, viewEnd + 1s]` para optimizacion + correctness en los bordes.
- Min viewport 1 second (no se puede zoomear a sub-frame), max viewport = full duration.

#### Bug #2 — Tiempo predefinido no graba video (`AppViewModel.kt`)

- **Root cause**: `AdbBridge.startScreenRecord` retorna `Process?` con un `try { pb.start() } catch (_: Exception) { null }`. Pero el caso real del bug no es una excepcion en `start()` — es que `screenrecord` arranca correctamente, exita en ~100ms con un exit code != 0 (resolucion rechazada por encoder, etc.), y el `Process` queda en estado terminated pero con la referencia valida. El codigo viejo no detectaba esto.
- **Fix**: nueva helper local `tryStart(profile)` en `AppViewModel.startCapture` que (a) llama a `startScreenRecord`, (b) hace el `delay(1500)` warm-up, (c) verifica `process.isAlive()`, (d) si el proceso murio, lee el stderr (via `redirectErrorStream`) para diagnostico, (e) retorna null. El caller intenta primero el profile elegido por hardware tier; si falla y no era STANDARD, retry con STANDARD; si los dos fallan, surface a `_captureWarning` (nuevo StateFlow) que se renderiza como banner amarillo en `CaptureScreen`. La captura continua con metricas — el video es nice-to-have, no critical.
- **Chain segments**: el `recordJob` ahora tambien null-checkea cada segmento. Si el chain falla a mitad de sesion, rompe el loop pero NO mata la captura (los segmentos previos se preservan, las metricas siguen). Stderr se loggea para diagnostico.
- **Diagnostico**: cuando el bug se reproduce, ahora hay un mensaje en el log de la app con el exit code y el primer 500 chars del stderr de `screenrecord`.

#### Bug #3 — Scoring demasiado estricto (`HardwareScoring.kt`)

- **Root cause #1**: GPU detection. La normalizacion de v3.1.10 manejaba `(tm)`, `(r)`, commas y whitespace, pero NO manejaba "family qualifiers" como `Rogue` (PowerVR), `Series` (Mali). El Huawei Y5 Lite reporta `Imagination Technologies, PowerVR Rogue GE8300` que despues de v3.1.10 normalization queda `imagination technologies powervr rogue ge8300`. La key del map es `powervr ge8300` literal. El `rogue ` en el medio rompia el `contains()`.
- **Root cause #2**: tier UNKNOWN tenia `expectedFps = 60, fpsFloor = 30` — los mismos defaults que `ULTRA_HIGH`.
- **Root cause #3**: los thresholds del scoring tenian un bracket -15 entre `expectedFps` y `fpsFloor` — demasiado agresivo.
- **Fixes**:
  - **Normalizacion mejorada**: `detectTier` ahora strippea `\bqualcomm\b`, `\bimagination technologies\b`, `\bimagination\b`, `\barm\b` (vendor prefixes), y `\brogue\b`, `\bseries\b`, `\bfamily\b`, `\bopengl es \d+(\.\d+)?\b` (family qualifiers + OpenGL version suffix).
  - **gpuTierMap rebalanceado**: PowerVR GE8300 movido de LOWER_MID a LOW (es un GPU de 2017 para MT6739, claramente low-end). Agregadas entries para PowerVR GE8200, G6200, G6400, SGX 540, VideoCore.
  - **UNKNOWN tier rebajado**: `expectedFps = 60, fpsFloor = 30` → `45, 30`.
  - **Brackets de scoring suavizados**: nuevo bracket "90% del expected" (-3) entre "above expected" (-0) y "above 80%" (-7). El bracket entre `fpsFloor` y `expectedFps * 0.8` ahora es -12 (era -15). El bracket "below floor pero arriba del 70%" es -25 (era -30).
  - **Sentinel `avgFps <= 0`**: nuevo guard en `calculateDeviceGrade` que devuelve `'F' to 0` directamente cuando avgFps es 0 o negativo.
- **Validacion**: 8 nuevos unit tests en `HardwareScoringTest` que cubren Y5 Lite GPU string completo, Y5 Lite a 28 fps con problemas, Pixel XL Adreno 530 a 43 fps, Galaxy S7 a 32 fps, flagship a 30 fps (sigue siendo D — el softening no afecta al lado fuerte), avgFps == 0 sentinel, PowerVR variants con brand prefix.

### Pendiente para futuras versiones

- v3.1.12: investigar el por que exacto del rechazo de `screenrecord` en algunos devices ahora que el logging de stderr esta agregado
- v3.1.12: pan horizontal de la timeline cuando esta zoomed (con shift+drag o middle-click drag) — actualmente solo hay zoom anchored al cursor
- v3.1.12: tooltip con FPS exacto al hacer hover sobre un punto de la timeline
- Validacion empirica del Bug #3 en el Huawei Y5 Lite real

## [3.1.10] — 2026-04-07

### Que hay de nuevo

- La app ahora afecta mucho menos el rendimiento del juego mientras graba. Los juegos se sienten mas fluidos durante la captura, especialmente en telefonos menos potentes
- En telefonos antiguos como el Pixel XL o similares, el video se graba con un formato mas liviano que no le pone carga a la GPU del juego
- El tool ahora detecta correctamente las GPUs Adreno, Mali y otras aunque el fabricante les ponga sufijos como (TM) o (R). Antes decia "GPU Tier: Unknown" para GPUs que si estaban en la base de datos
- En telefonos con Android 10 y 11, el FPS ahora se mide correctamente. Antes podia quedarte un reporte con "FPS promedio: 0" aunque el juego corriera normal

### Arreglos

- Bug critico: la app hacia 2 llamadas por segundo a Android para pedir info de memoria del juego, y cada llamada pausaba el juego durante 50-200 milisegundos. Ahora la info de memoria se pide cada 5 segundos (no cambia entre un medio segundo y el otro)
- Bug critico: la app pedia el dump completo del compositor grafico de Android cada segundo para contar frames perdidos, lo cual tomaba un lock global que el mismo juego necesita para presentar frames. Ese contador en vivo se elimino (el numero final del reporte sigue siendo exacto porque se calcula en los bordes de la sesion)
- Bug del Pixel XL y similares con Android 10: el formato del comando `dumpsys SurfaceFlinger --list` cambio en Android 12, y el codigo solo entendia el formato nuevo. En telefonos con Android 10 o 11 no encontraba el layer del juego y devolvia ceros todo el rato. Ahora maneja ambos formatos
- Bug del GPU tier "Unknown": muchas GPUs Qualcomm Adreno reportan su nombre como `Adreno (TM) 530`, pero el codigo buscaba `adreno 530` literal. Ahora normaliza el string antes de buscar
- El Pixel XL (Adreno 530) ahora se clasifica correctamente como Lower Mid-Range con el score que corresponde, no como Unknown con score 20/100

### Detalles tecnicos

#### Tiered cadence en el polling loop (`AppViewModel.startCapture`)

- **Root cause 1**: cada iteracion del loop llamaba a `dumpsys meminfo <pkg>` con timeout de 8s. Esta llamada hace un binder transaction contra el proceso del juego y bloquea su main looper 50-200ms mientras AMS recolecta PSS. A 2 polls/sec eso = 100-400ms/sec de bloqueo directo sobre el hilo del juego. En un juego a 60 FPS (16.67ms/frame) eso garantiza jank visible.
- **Root cause 2**: cada iteracion llamaba a `getMissedFrames` que hace un `dumpsys SurfaceFlinger` COMPLETO (sin `--latency`). Ese comando toma el lock global de SF, serializa todo el layer tree, y toma 150-500ms. Peor aun: ese es el mismo lock que usa SurfaceFlinger para schedulear los frames del juego. La app estaba literalmente bloqueando al compositor dos veces por segundo.
- **Fix**: el loop ahora tiene tres tiers:
  - **Fast (cada ~500ms)**: FPS via `--latency` (5-20ms), CPU via `/proc/stat` (5-10ms), battery (5-15ms). Todas cheap, ninguna toca el proceso del juego.
  - **Medium (cada ~2s, cada 4ta iteracion)**: thermal sensors via sysfs (30-80ms).
  - **Slow (cada ~5s, cada 10ma iteracion)**: `dumpsys meminfo <pkg>` (200-800ms). Memoria es un signal lento por naturaleza, 5s es mas que suficiente.
- `getMissedFrames` removido del loop por completo. El valor `frameDrops` que aparece en el reporte final se calcula como `missedEnd - missedStart` en los bordes de la sesion, asi que la precision del numero final es identica. El contador en vivo se reemplazo por `totalJank` (que ya se calculaba gratis en cada sample via `captureFrames`).
- Pattern de last-known-value: las llamadas a `LiveMetrics` siempre reciben data en cada campo aunque el slow tier no haya corrido esta iteracion — mantenemos `lastMem` y `lastThermal` como variables del loop y las re-emitimos.
- Resultado esperado: el costo del loop por iteracion baja de ~1.0-1.5s a ~100-200ms. El juego deja de tener su main thread bloqueado dos veces por segundo, y el compositor deja de perder el lock global.

#### Fix de `findLayer` para Android 10/11 (`AdbBridge`)

- **Root cause**: `dumpsys SurfaceFlinger --list` devuelve formatos diferentes segun la version de Android. En Android 12+ envuelve cada layer en `RequestedLayerState{<name>  parentId=<n>}`. En Android 10/11 devuelve el nombre directo, una linea por layer. El regex viejo solo matcheaba el formato nuevo, caia al `firstOrNull()` con la linea cruda en Android viejo, y despues `dumpsys --latency '<raw>'` no reconocia el nombre y devolvia vacio. Resultado: `fpsHistory` vacio en sesiones enteras, reportes con `avgFps = 0` aunque el juego corriera normal.
- **Fix**: `findLayer` ahora intenta primero el regex del formato moderno. Si no matchea, usa la linea trimmed directamente como nombre de layer (formato pre-12). La candidate selection (prefer SurfaceView BLAST, luego SurfaceView non-Background, luego first) no cambio.
- **Testeabilidad**: la logica de parsing se extrajo a una funcion pura `parseSurfaceFlingerListOutput(output, pkg)` para poder unit-testearla sin mockear adb. 8 tests cubren ambos formatos, edge cases y regression guards (ej. que el suffix `@0#0` no se pierda).

#### Fix de `detectTier` para GPUs con sufijo (TM)/(R) (`HardwareScoring`)

- **Root cause**: real Android devices report GPU strings como `Qualcomm, Adreno (TM) 530, OpenGL ES 3.2 V@384.0 (GIT@4a00b6)`. El codigo viejo hacia `gpu.lowercase().contains("adreno 530")` pero el string tiene `(tm)` en el medio, asi que el contains fallaba y el device caia a `UNKNOWN`. Mismo problema con `(R)` en devices Mali. Sintoma: reportes que decian "GPU Tier: Unknown" y "Hardware Score: 20/100" para GPUs conocidas.
- **Fix**: `detectTier` ahora normaliza el string antes del lookup: quita `(tm)`, `(r)`, reemplaza commas por espacios, collapsa whitespace multiple. El `gpuTierMap` no cambio.
- **Irony note**: habia un test en `HardwareScoringTest` que ACTIVAMENTE testeaba el bug, esperando `UNKNOWN` para `"Adreno (TM) 619"`. Ese test se actualizo para esperar `MID` (que es el tier correcto).

#### Screenrecord adaptativo por tier (`AdbBridge.startScreenRecord`)

- **Root cause**: el comentario original decia "hardware encoder on SoC, no game GPU impact" pero eso es solo parcialmente cierto. El H.264 hw encoder es cheap, pero screenrecord igual tiene que acquirear cada frame via un virtual display de SurfaceFlinger. En devices con panel nativo mayor al recording size (ej. Pixel XL 1440x2560 vs record 720x1280), SF tiene que downscalear cada frame, lo cual consume GPU cycles que el juego tambien necesita. El penalty escala con el scaling factor: ~3-5% overhead en 1080p→720p, ~8-15% en 1440p→720p.
- **Fix**: nueva enum `ScreenRecordProfile` con dos variantes:
  - `STANDARD` = 720x1280 @ 4 Mbps (default, lo que habia antes)
  - `COMPACT` = 540x960 @ 2 Mbps (para LOW y LOWER_MID tier)
- `AppViewModel` selecciona el profile basado en `HardwareScoring.detectTier(gpu)` al arrancar la captura. El Pixel XL (Adreno 530 = LOWER_MID) ahora graba en compact, reduciendo la carga del virtual display downscale.
- Tradeoff: el video del Pixel XL queda en 540p en vez de 720p. Sigue siendo usable para analisis frame-by-frame pero con menos detalle. En devices potentes el comportamiento no cambia.

## [3.1.9] — 2026-04-07

### Que hay de nuevo

- Los videos largos ya no se cortan a los 2 minutos y 56 segundos. Ahora podes grabar sesiones de 10, 15, 30 minutos o lo que quieras, y el video se ve entero
- Las sesiones que ya tenias guardadas con el video truncado se reparan automaticamente la primera vez que abras la app
- El video del historial ahora se ve como un archivo unico, no como un fragmento del principio

### Arreglos

- Bug critico: si grababas una sesion de mas de 3 minutos, el reproductor solo te mostraba los primeros 2:56. Los datos estaban capturados pero invisibles
- El video del reproductor ahora abarca toda la duracion real de la captura, no solo el primer fragmento
- Las sesiones largas que ya tenias guardadas (las que decian "duracion: 14 minutos" pero el video se cortaba al rato) se arreglan solas cuando abris la nueva version

### Detalles tecnicos

- Root cause: `adb screenrecord` tiene un limite hardcoded de 3 minutos por archivo. El recording loop en `AppViewModel` ya encadenaba segmentos correctamente (`gp_${sid}_0.mp4`, `_1.mp4`, ...) y los bajaba todos a disco via `pullRecordings`, pero la linea `recordings.firstOrNull()?.absolutePath` exponia solo el primer segmento al UI. Bug de un solo `firstOrNull()`, sintomas catastroficos
- Nueva funcion `AdbBridge.concatSegments(segments, output)` que invoca ffmpeg con `concat` demuxer + `-c copy` (lossless, sin re-encoding). Para 14 minutos de video tarda 5 segundos, no 5 minutos. ffmpeg ya era dependencia del proyecto (lo usa `EmbeddedVideoPlayer` para `getVideoFps` y extraccion de frames)
- `AppViewModel` ahora produce un `video_${sessionId}.mp4` unificado al final de cada sesion con 2+ segmentos. Si solo hay 1 segmento (sesiones de menos de 3 min) se mantiene el comportamiento viejo
- Nueva funcion `FileCleanup.repairTruncatedVideos(snapshot)` que recorre las entradas de `history.json`, detecta paths que terminan en `_0.mp4` con segmentos hermanos en disco, los concatena, y devuelve las entries actualizadas con el path al archivo unificado. Hookeada en `AppViewModel.init()` despues del prune normal — corre una sola vez por arranque
- Idempotente: si el archivo unificado ya existe (porque un repair previo lo creo), la funcion solo reescribe el path en history sin volver a invocar ffmpeg
- No-destructivo: los segmentos `_N.mp4` originales NO se borran. Quedan como backup hasta v3.1.10 minimo. La logica de `pruneOrphans` ya los preserva via la regla de segment-preservation por sessionId
- Tolerante a fallos: si ffmpeg no esta instalado o el concat falla, la entry queda apuntando al `_0.mp4` (sintoma actual) en vez de quedarse sin video. Degraded > broken
- 6 unit tests nuevos en `FileCleanupTest` cubriendo: skip empty path, skip non-existent file, skip legacy `recording_*.mp4`, skip single-segment, idempotency, multi-repair, failed-concat tolerance
- Validacion empirica: corrido contra el `~/GamePerf Reports/` real con 5 entries (2 multi-segmento de 10min y 14min, 3 simples). Las dos largas se repararon a `video_${sid}.mp4` de 277MB y 404MB respectivamente, durations confirmadas via ffprobe a 9:58 y 14:09. Comparado con los 2:55 de los `_0.mp4` originales

## [3.1.8] — 2026-04-06

### Que hay de nuevo
- El PDF exportado ahora se ve como un informe corporativo profesional, con buen contraste entre cards y fondo
- Las cards de las metricas se distinguen claramente del fondo de la pagina (antes parecia "blanco sobre blanco")
- Las stat pills (FPS, P1, Frame Time, etc.) ahora son capsulas con cuerpo visible, no texto flotando
- Las tablas tienen header oscuro con texto blanco y alternancia de filas, como un dashboard real
- El header del documento ahora se ve como un bloque solido con borde, no un fantasma gris

### Arreglos
- Los recuadros, cards y pills tenian fondos casi identicos al blanco de la pagina (delta de 8 sobre 255), por eso parecia que todo flotaba. Ahora los fondos tienen contraste real (delta 30+) y los bordes son visibles
- El card grande del Resumen Ejecutivo ahora es claramente diferente del fondo de la pagina
- Las metric cards (FPS, FRAME TIME, MEMORIA, CPU) tienen bordes fuertes que las delimitan
- Los problem cards (warnings, criticals) tienen bordes de 2px en sus colores semanticos
- El footer ahora tiene una separacion clara del contenido principal

### Detalles tecnicos
- Reescrita la `@media print` rule completa de `ReportGenerator.kt` con foco en contraste sobre papel blanco
- Sistema de doble nivel de cards: cards padre en gris claro `#f1f5f9` (delta 30 del blanco) con borde `#94a3b8` (delta 80), cards hijas en blanco puro con el mismo borde fuerte. Esto crea jerarquia visual sin necesidad de sombras o gradientes
- Stat pills cambiadas de `#ffffff` (invisible) a `#e2e8f0` (delta 30) con borde `#94a3b8` para que tengan cuerpo
- Header background gradient cambiado de `#f1f5f9 → #e2e8f0` (deltas 18-30) a `#cbd5e1 → #94a3b8` (deltas 50-100) para que el header sea visible como bloque solido
- Tabla `.data-table` ahora tiene `tr:nth-child(even)` con fondo `#f1f5f9` y `tr:nth-child(odd)` blanco para alternancia de filas
- Headers de tablas con fondo `#475569` y texto `#ffffff` (antes eran `#f1f5f9` con texto `#1e293b` casi imperceptibles)
- Problem cards con bordes de 2px en lugar de 1px y colores semanticos mas saturados
- Validacion empirica via muestreo de pixeles con PIL: confirmado que los deltas del blanco subieron de 8-18 a 30-80

## [3.1.7] — 2026-04-06

### Que hay de nuevo
- Los informes exportados a PDF ahora se ven con calidad profesional
- Las graficas (FPS, frame time, memoria, CPU, temperatura) usan colores legibles sobre fondo blanco en vez del tema oscuro
- El layout del PDF es mas denso y aprovecha mejor las paginas: lo que antes ocupaba 8 hojas ahora ocupa 5
- La primera pagina del PDF ya no aparece casi vacia con solo el titulo

### Arreglos
- El cartel "GAME PERFORMANCE TOOL", grade ring, metricas y graficas ahora se renderizan correctamente en el PDF
- Las graficas Chart.js se inicializan con paleta clara cuando se exporta a PDF, en vez de seguir usando los colores del tema oscuro que quedaban ilegibles

### Detalles tecnicos

#### Print mode detection en el HTML del reporte
- `PdfExporter.kt` ahora pasa `?print=1` en el `file://` URL al spawnar Chrome `--print-to-pdf`
- `ReportGenerator.kt` agregado un detector JS al principio del `<script>` block: `IS_PRINT = window.location.search.indexOf('print=1') >= 0`
- Cuando `IS_PRINT` es true, `Chart.defaults.color` y la base `B` de Chart.js options se construyen con paleta print-friendly (texto oscuro, grids visibles, sin tooltips, sin animacion)
- Nueva paleta `C = {primary, accent, good, warn, bad}` con dos sets de colores: `COLORS_DARK` (cyan/orange/emerald para el tema oscuro de pantalla) y `COLORS_PRINT` (azul oscuro `#0369a1`, naranja oscuro `#9a3412`, verde oscuro `#15803d`, ambar `#b45309`, rojo oscuro `#b91c1c`)
- Los 5 charts del reporte individual (FPS, Frame Time, Memory, CPU, Temperature) ahora usan `C.primary`, `C.bad`, etc. en vez de hex hardcoded, asi se adaptan automaticamente al modo
- Los gradientes de los charts (FPS y CPU) se reemplazan por fills solidos en print mode porque Chrome flatten los alpha channels y los gradientes quedan washed out

#### `@media print` rule reescrita
- Cambio critico: removido `page-break-after:avoid` del `.report-header`. Esa regla causaba que la primera pagina quedara casi vacia porque el header forzaba que las cards posteriores no rompieran inmediatamente despues, y como cada card tenia `page-break-inside:avoid`, todas se iban a la pagina 2
- Cambiado `page-break-inside:avoid` a `page-break-inside:auto` en `.card` y `.card-summary` para permitir que cards grandes se partan entre paginas si hace falta
- Reduccion de tamanos: header de 24px a 18px, fonts de 11px a 10.5px base, chart container height de 360px a 220px, grade ring de 140px a 110px
- Mejor color contrast: texto principal `#0f172a` (casi negro) en vez de `#1e293b`, badges con borders mas marcados, grade values con peso 700-900
- Tablas mas compactas: 10px base, 9px headers, padding reducido de 12px a 5-8px

#### `ReportRenderingTest.kt` nuevo
- Test de fixture que genera un HTML de prueba con datos sinteticos en `~/GamePerf Reports/` para que el desarrollador pueda regenerarlo y validar visualmente cambios al ReportGenerator sin necesitar una captura real
- Disabled por default, se activa con `RUN_REPORT_FIXTURE=true ./gradlew test`
- 60 segundos de session sintetica con un FPS drop intencional y un CPU spike para que las graficas tengan data interesante

#### Validacion empirica
- Generado un HTML de prueba via el test
- Pasado por Chrome `--print-to-pdf` con los flags exactos de PdfExporter.kt + `?print=1`
- Extraidas las 5 paginas del PDF resultante via PyMuPDF
- Inspeccion visual: header con background gris claro correcto, 6 stat pills visibles, grade ring B verde, charts FPS/Frame Time/Memory/CPU/Temperature con colores oscuros legibles sobre fondo blanco, axis labels visibles, leyendas legibles
- PDF baseline (v3.1.6): 8 paginas, primera pagina casi vacia, charts oscuros invisibles
- PDF con fix (v3.1.7): 5 paginas, primera pagina densa y completa, todas las graficas legibles

## [3.1.6] — 2026-04-06

Consolidated release covering everything done since v3.0.1. The intermediate versions
(v3.1.0-beta.1 through v3.1.5) were development iterations that have been superseded.
v3.1.1, v3.1.2 and v3.1.4 are still in the GitHub releases page for historical context but
all users should install v3.1.6.

### Que hay de nuevo

- Limite de 5 sesiones en el historial: cuando capturas la sexta, la mas vieja se borra automaticamente para no llenar el disco
- Exportar a PDF: nuevo boton en el historial, en la pantalla de resultados y en la comparativa para guardar el informe en cualquier carpeta de tu PC
- Limpieza automatica al iniciar la app: borra archivos huerfanos (videos sueltos sin entrada en el historial, informes sin video) que se acumulaban desde versiones anteriores
- Cartel de actualizacion mejorado: ahora muestra un resumen con los cambios principales de la version nueva
- Las graficas de los informes funcionan sin internet: Chart.js viene embebido en el HTML
- Instalador mucho mas liviano: 69 MB en vez de 250 MB que pesaba en las betas anteriores

### Arreglos

- El cartel amarillo de "actualizacion disponible" ahora aparece correctamente cuando hay una version nueva (antes no aparecia con notas de version largas)
- El boton de actualizar ahora reabre la app sola en la version nueva (antes la cerraba pero no la volvia a abrir en bundles .app de macOS)
- El boton de borrar entrada del historial ahora elimina tambien el video y el informe HTML (antes dejaba archivos huerfanos)
- La eliminacion de videos en multiples segmentos ahora limpia todos los archivos del set, no solo el primero
- Los puntos del resumen de actualizacion se ven alineados y con buen contraste sobre el fondo oscuro

### Detalles tecnicos

#### Auto-updater
- Nuevo `enum InstallationType` (FAT_JAR_STANDALONE, MACOS_APP_BUNDLE, WINDOWS_APP_BUNDLE, LINUX_NATIVE_PACKAGE, DEV_MODE) con detection en runtime via `protectionDomain.codeSource.location` + walking del path tree
- `applyUpdate` hace branching segun el tipo: bundles macOS se relanzan con `open -n`, Windows con su `.exe` nativo, Linux con `xdg-open` o el script wrapper. Solo el caso `FAT_JAR_STANDALONE` usa `nohup java -jar`
- Bash y bat scripts hardenados con `set -e`, validacion de tamano del JAR descargado (>= 50 MB para bundles, >= 1 KB para fat JAR), logging estructurado a `~/GamePerf Reports/updates/last-update.log`, `trap EXIT` para self-cleanup
- `AppVersion.kt` es ahora auto-generado por una task de Gradle (`generateAppVersion`) que lee `gradle.properties` — antes estaba hardcoded y nunca se sincronizaba, lo que hacia que todas las versiones desde v3.0.0 reportaran `NAME = "3.0.0"` al runtime aunque el tag git fuera otro. Esto era el root cause del bug "el auto-updater nunca actualiza visualmente"
- `extractJsonString` reemplazado por un parser lineal sin regex despues de descubrir que el regex original (`"$key"\s*:\s*"((?:[^"\\]|\\.)*)"`) tiraba `StackOverflowError` con bodies largos por catastrophic backtracking en alternaciones con cuantificador `*`. El catch original solo atrapaba `Exception`, no `Error`, asi que el fallo era silencioso: `_updateAvailable.value` quedaba en `null` y el banner nunca aparecia con notas de version > 1500 chars
- `checkForUpdate` ahora atrapa `Throwable` en vez de `Exception` para cubrir errores de JVM como `StackOverflowError`
- Nuevo `AutoUpdater.lastDownloadError` y `AutoUpdater.lastCheckError` (ambos `@Volatile`) para capturar el motivo exacto del ultimo fallo y mostrarlo en la UI

#### PDF export
- Reemplazado `com.microsoft.playwright:playwright:1.45.0` (que arrastraba `driver-bundle:1.45.0` con 163 MB de binarios de Node.js para 5 plataformas) por `ProcessBuilder` + `chrome --headless --print-to-pdf` invocando un browser Chromium-based instalado en el sistema
- Nuevo `BrowserDetector` cross-platform con paths candidatos por OS (Chrome, Chromium, Edge, Brave, Vivaldi, Arc) y fallback `command -v` para Linux. Cachea el resultado con sentinel para tolerar el caso "ningun browser instalado"
- 11 flags de Chrome validados experimentalmente para que Chart.js renderice antes del print: `--virtual-time-budget=10000`, `--run-all-compositor-stages-before-draw`, `--no-pdf-header-footer`, `--hide-scrollbars`, `--user-data-dir=<tmp>`, etc.
- Resultado del cambio: JAR de 252 MB → 69 MB
- Nuevo error UX: cuando no hay browser detectado, el banner rojo de error muestra un boton "Descargar Chrome" inline que abre `https://www.google.com/chrome/` via `Desktop.getDesktop().browse(URI(...))`

#### Session retention
- Nuevo `FileCleanup` object: `pruneOrphans` bidireccional, segment-aware delete con regex `video_(\d{8}_\d{6})_\d+\.mp4`, whitelist de prefijos para proteger `updates/` del cleanup
- `SessionHistory.MAX_ENTRIES` bajado a 5, `addEntry` devuelve `List<HistoryEntry>` con las pruneadas para cleanup, `deleteEntry` devuelve `HistoryEntry?` con la removida para cleanup, todas las escrituras `@Synchronized`
- Comparativas se generan en `java.io.tmpdir` y se limpian en `cleanup()` + scan inicial al startup borra leftovers
- Nuevo "Historial: 5/5" passive hint en el HomeScreen cuando se llega al limite

#### Mini-changelog UI
- Nueva funcion `summarizeReleaseBody` en `HomeScreen.kt` que parsea el markdown del release body y extrae hasta 5 bullets priorizados por seccion
- Prioridad de secciones (de mayor a menor): "Que hay de nuevo" / "Highlights" / "Novedades" / "What's new" > "Added" / "Nuevo" > "Fixed" / "Arreglado" > "Changed" / "Cambios" > "Critical" / "Importante"
- Bullets renderizados con un unico `Text` con prefijo inline (`"•  $line"`) para garantizar baseline alignment cuando wrappean. Color `Color.White.copy(alpha=0.82f)` sobre fondo `Color.Black.copy(alpha=0.25f)` para contraste legible
- Sort estable: bullets dentro de la misma prioridad preservan su orden original

#### Tests
- 68 unit tests pasando
- 9 nuevos en `AutoUpdaterDetectionTest` cubriendo todos los `InstallationType` + fallbacks
- 16 en `FileCleanupTest`, 7 en `SessionHistoryTest`

#### Stats
- 21 archivos modificados desde v3.0.1
- ~1800 LOC netas (incluyendo Chart.js inlineado de ~250 KB)
- 0 warnings on clean compile
- Build time CI: ~3 min matrix Linux + macOS arm64 + Windows + macOS x64 (este ultimo subido manualmente porque `macos-13` esta deprecated en GitHub Actions)

## [3.1.4] — 2026-04-06 — superseded by 3.1.6

Critical hotfix for the auto-updater banner that never appeared on releases with long
notes. Replaced by v3.1.6 which adds the bullet alignment fix and the human-friendly
section priority on top.

### Arreglos
- Critical: el banner amarillo de actualizacion volvio a aparecer despues de no funcionar con notas largas

### Detalles tecnicos
- `extractJsonString` reescrito como linear scan parser. Root cause: catastrophic backtracking del regex original causaba `StackOverflowError` que el catch comia silenciosamente

## [3.1.2] — 2026-04-06 — superseded by 3.1.6

First introduction of the mini-changelog feature in the update banner. Superseded by
v3.1.4 (regex fix) and v3.1.6 (UI alignment + priority sections).

### Que hay de nuevo
- Nuevo cartel de actualizacion con resumen de cambios
- Mensajes de error de descarga mas especificos

### Detalles tecnicos
- Nueva `summarizeReleaseBody` en HomeScreen.kt
- Nuevo `AutoUpdater.lastDownloadError` `@Volatile` field

## [3.1.1] — 2026-04-06 — superseded by 3.1.6 (marked prerelease)

Initial fix for the auto-updater bundle relauncher and replacement of Playwright with
ProcessBuilder. Superseded by v3.1.4 which fixes the AppVersion sync bug that prevented
this release from being visibly applied.

### Detalles tecnicos
- Nuevo `InstallationType` enum + branching en `applyUpdate`
- `BrowserDetector` cross-platform replacing Playwright
- Bash/bat scripts defensivos con logging

## [3.1.1-beta.1] / [3.1.0-beta.1] — 2026-04-06 — prereleases

Beta releases shipped during development. Both have the bundle size issue (250 MB JARs)
that was fixed in v3.1.1+. Marked as prereleases in GitHub.

## [3.0.1] — 2026-03-31

- Auto-updater hotfix: use exact `java.home`, handle dev mode
- macOS x64 CI build added (later reverted due to macos-13 deprecation, eventually
  re-enabled and worked around with manual upload in v3.1.x)
- Video FPS calculation: use `avg_frame_rate` instead of `r_frame_rate`

## [3.0.0] — 2026-03-31

Production-quality polish release. Fix of 40+ issues:

- 23 `String.format` calls with locale-unsafe formatting (broke reports in es_AR locale)
- Memory leak in ViewModel `CoroutineScope` (now `cleanup()` on window close)
- Video player OOM (sliding window cache of 200 frames max, ~20 MB)
- Device disconnection detection during capture (3 consecutive ADB failures → auto-stop with red banner)
- `AdbBridge` singleton state reset between captures (CPU delta, SurfaceFlinger cache)
- UX: stop confirmation, delete history confirmation, keyboard shortcuts (Space / ← / → / Esc), pulsing capture dot
- 0 compiler warnings, 0 deprecated APIs, 36 unit tests
- New competition comparison mode: tag sessions as OUR_GAME or COMPETITION, side-by-side comparison screen with bar charts and color-coded table, comparative HTML report with Chart.js radar chart
- Embedded video player with custom frame renderer using ffmpeg + Skia (JPEG → Skia.makeFromEncoded direct, ~0.1 ms/frame vs 46 ms/frame with the previous JPEG → PNG → Skia path)

## [2.2.0] — 2026-03-31

- Embedded video player using JavaFX MediaView
- Interactive timeline with custom markers (Interstitial, VideoReward, Loading, SceneChange)

## [2.1.0] / [2.0.0] — 2026-03-31

- Initial Compose Desktop standalone version
- Auto-updater via GitHub Releases
- Comparison report
- Version management
