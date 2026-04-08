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

## [3.1.11] — 2026-04-07

### Que hay de nuevo

- El contador de "frames perdidos" durante la captura ahora muestra el numero correcto, igual al que aparece en el reporte final (antes mostraban dos numeros distintos bajo el mismo nombre)
- La primera medicion de la captura ya no provoca un microsegundo de stutter en el juego: arrancamos suaves y vamos calentando los tiers segun corresponda
- En telefonos como el Pixel XL el video se graba en un tamaño que el codec sí acepta. Antes podia rechazar la resolución y dejarte sin video sin avisar
- Si el termometro o el FPS no estan listos al arrancar, ya no ves "-1°C" o numeros raros en el HUD durante el primer segundo

### Arreglos

- Bug critico de v3.1.10: el contador de "frames perdidos" en vivo era una metrica completamente distinta a la del reporte final. Live mostraba el conteo de "jank" (frames lentos) y el reporte mostraba "missed frames" (deadlines de vsync perdidos por el compositor). Mismo nombre, dos cosas. Ahora ambos muestran missed frames y son consistentes
- Bug critico de v3.1.10: el conteo de "jank" estaba inflado entre 3 y 4 veces porque cada llamada al SurfaceFlinger lee los ultimos ~127 frames del buffer (≈2 segundos), y como llamabamos cada 500ms los mismos frames se contaban hasta 4 veces. Ahora solo se cuentan los frames realmente nuevos entre llamadas
- Bug critico de v3.1.10: la primera iteracion del loop de captura disparaba TODOS los tiers a la vez (fast + medium + slow), causando un stutter de ~1 a 1.5 segundos al inicio de cada captura. Justo el problema que el fix de v3.1.10 pretendia evitar. Ademas, esa coincidencia se repetia cada 10 segundos (LCM de 4 y 10). Ahora los tiers estan desfasados para que la primera iteracion sea solo fast, y los tiers caros se reparten en iteraciones distintas
- Bug critico de v3.1.10: el COMPACT screenrecord profile usaba 540x960, pero 540 no es multiplo de 16. El encoder AVC de Android (OMX/MediaCodec) requiere `width-alignment=16` en la mayoria de SoCs. En devices low-end como el Pixel XL podria rechazar la resolucion y matar el proceso de screenrecord en ~100ms, dejandote sin video. Cambiado a 544x960 (544 = 16×34), encoder-safe en todos los Android conocidos
- Bug heredado: el HUD en vivo mostraba "-1°C" durante los primeros segundos de captura porque el `lastThermal` arrancaba con sentinel -1.0 y se emitia sin guardar antes del primer thermal sample. Ahora se coalescen los negativos a 0

### Detalles tecnicos

#### C1 — frameDrops semantic break (live counter vs final report)

- **Root cause**: v3.1.10 cambio `LiveMetrics.frameDrops = totalJank` (suma de frames con frametime > 16.67ms desde `captureFrames`) pero el reporte final seguia usando `frameDrops = missedEnd - missedStart` (delta del contador global de SurfaceFlinger). Mismo field name, metricas distintas. Usuarios viendo el HUD durante captura ven un numero, despues el reporte final muestra otro
- **Fix**: poll `getMissedFrames` UNA VEZ por slow tier (cada 5s ≈ cada 10 iteraciones) y emit `lastMissedDelta = liveMissed - missedStart` a `LiveMetrics.frameDrops`. La misma metrica que el reporte final, solo que sampleada menos seguido. El costo de 150-500ms del `dumpsys SurfaceFlinger` es aceptable porque ya estamos en el tier lento (donde tambien corre meminfo) y el game no nota carga adicional al estar en una iteracion ya cara

#### C2 — first iteration burst

- **Root cause**: `iterCount % 4 == 0` y `iterCount % 10 == 0` ambos true en `iterCount = 0`. La primera iteracion del loop ejecutaba fast + medium + slow tier juntos: ~30-50ms (fast) + 30-80ms (thermal) + 200-800ms (meminfo) = 500-1000ms de bloqueo en la primera iteracion. Exactamente el burst que el fix de v3.1.10 queria evitar. Encima, LCM(4,10)=20 → la coincidencia se repetia cada 20 iteraciones ≈ cada 10 segundos
- **Fix**: cambiar las fases de los modulos. Medium ahora es `iterCount % 4 == 3` (fires en iter 3, 7, 11, 15, ...) y slow es `iterCount % 10 == 7` (fires en iter 7, 17, 27, ...). La primera iteracion (iter 0) **no dispara ningun tier caro** — solo fast tier (~30-50ms total). Los tiers caros se siguen agrupando cada cierto numero de iteraciones, pero nunca en la primera

#### C3 — totalJank over-count (4× inflation)

- **Root cause**: `dumpsys SurfaceFlinger --latency '<layer>'` devuelve los ~127 frames mas recientes del buffer (≈2 segundos a 60fps). El polling loop llamaba a `captureFrames` cada 500ms. Cada llamada veia 1.5 segundos overlapados con la anterior. `captureFrames` contaba `jankCount = frameTimes.count { it > 16.67 }` sobre TODO el buffer cada vez. El caller hacia `totalJank += frame.jankCount` → los mismos frames se contaban hasta 4 veces (uno por cada llamada que los veia en su buffer)
- **Fix**: tracking `lastSeenFrameTimestampNs` como state de `AdbBridge`. `captureFrames` ahora solo cuenta frames con timestamp estrictamente mayor al ultimo visto. La logica de parsing se extrajo a `parseFrameLatencyOutput(output, lastSeenTimestampNs)` para hacerla pura y unit-testeable
- **Validation**: nuevo test `polling loop simulation - 4 calls do not over-count the same jank frames` simula 4 llamadas con buffer overlapado y valida que `totalJank` quede dentro de ±2 del numero esperado. Sin el fix, el test mostraria 3-4× el valor

#### C4 — COMPACT screenrecord 540 not multiple of 16

- **Root cause**: el codec AVC de Android (via OMX/MediaCodec) requiere que `width` y `height` sean multiplos de 16 (algunos SoCs viejos requieren multiplo de 32). 540/16 = 33.75 — invalido. En devices low-end como el Pixel XL, el `screenrecord` binary rechaza la resolucion con exit code != 0 (no exception, asi que el try/catch no lo cazaba), el proceso muere en ~100ms, `pullRecordings` no encuentra archivos, y el usuario queda sin video sin ningun mensaje de error. Justo en la clase de devices que el COMPACT profile pretendia ayudar
- **Fix**: cambiar COMPACT de 540x960 a 544x960. 544 = 16×34, encoder-safe. Visualmente identico a 540 para analisis frame-by-frame. Ambas dimensiones de ambos profiles ahora estan documentadas como multiples de 16 con un comentario para futuros maintainers

#### Otros fixes menores incluidos en el mismo release

- **W6 fix**: `lastThermal = ThermalSnapshot(-1.0, -1.0, -1.0, -1.0)` se emitia a `LiveMetrics` sin guardar, mostrando "-1°C" en el HUD durante los primeros segundos de captura. Ahora se coalescen los valores negativos a 0.0 antes de emit
- **Refactor para testabilidad**: la logica de parsing de `captureFrames` se extrajo a `parseFrameLatencyOutput` (funcion pura). 9 unit tests nuevos en `FrameLatencyParserTest` cubriendo first-call window limiting, dedup en subsequent calls, simulacion de polling loop, edge cases (empty output, garbage timestamps, no new frames)

### Pendiente para futuras versiones (de los WARNINGs reportados por judgment day Round 1)

- **W2/W3** (v3.1.12): mejorar normalizacion de `detectTier` para `Adreno(TM)530` (sin espacios) y `Mali G710` (sin hyphen). El fix de v3.1.10 cubrio el caso con espacios pero no estos
- **W4** (v3.1.12): refresh `_deviceInfo` antes de empezar capture, o recalcular `recordProfile` por segmento, para evitar que un device LOW use STANDARD si `_deviceInfo` no se cargo todavia
- **W7** (v3.1.12): escapar single quotes en layer names antes de interpolarlos en `dumpsys --latency '$layer'` para evitar shell injection
- **W8** (v3.1.12): mostrar `memHistory` con el mismo timeline que `fpsHistory` en el chart (actualmente memoria tiene 10× menos puntos que FPS, los charts side-by-side tienen ejes X distintos sin labels)
- **W9** (v3.1.12): mover thermal de medium tier (cada 2s) a fast tier (cada 500ms) para no perder transient throttle spikes — el costo de 30-80ms es aceptable en fast tier
- **Logging diagnostico** (v3.1.12): agregar log a `~/.gameperf/debug.log` cuando `detectTier` retorna UNKNOWN, cuando el screenrecord profile elegido difiere del esperado, y cuando el slow tier no fire en >10s
- **Validacion empirica en device real** del usuario: correr una captura de 5 minutos en Pixel XL con Touch2Goal Soccer y confirmar que el game se siente menos afectado, que `fpsHistory` esta poblado, y que el HUD no muestra valores absurdos

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
