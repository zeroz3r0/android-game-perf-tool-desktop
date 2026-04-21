# CLAUDE.md — Base de conocimiento del proyecto para sesiones futuras

Este archivo recoge lecciones aprendidas y patrones de bugs recurrentes **para evitar repetirlos**. Si eres un agente (Claude / Copilot / Gemini / Cursor / etc.) trabajando en este repositorio, léelo antes de tocar código crítico.

---

## Patrón de bug recurrente: "No hay JAR disponible para tu plataforma"

**Síntoma:** el usuario actualiza a una release recién publicada (v4.2.3, v4.2.4, v4.2.9 fueron ocurrencias reales) y el banner muestra el error rojo en la parte inferior: "No hay JAR disponible para tu plataforma. Descarga manualmente desde https://...".

**Causa raíz (tercera iteración del mismo bug):**

1. `gh release create vX.Y.Z` crea la release inmediatamente en GitHub → aparece con `published_at`
2. El tag push del release dispara el workflow `.github/workflows/release.yml`
3. El workflow tarda **6-7 minutos** en compilar + empaquetar + subir binarios (`ubuntu`, `macos`, `windows` en paralelo)
4. Durante esos 6-7 min, el usuario con la versión anterior abre la app, ve el banner "Nueva version vX.Y.Z disponible", pulsa "Actualizar"
5. `AutoUpdater.extractJarAssetUrl` retorna `null` porque los assets todavía no existen en la release
6. El banner aparece igual porque `checkForUpdate()` no filtra releases sin binarios listos → muestra error al descargar

**Fix definitivo (implementado en dos capas, v4.2.10 + v4.2.11):**

Capa 1 — cliente (v4.2.10), mitigación para versiones nuevas:
En `AutoUpdater.kt::checkForUpdate()`, después de obtener la lista de tags ordenada por semver descendente, iterar hasta encontrar una release que tenga un JAR asset matching la plataforma del usuario. Si la más alta todavía está compilando, caer a la siguiente más alta. Si ninguna matchea, retornar `null` (sin banner).

Capa 2 — servidor (v4.2.11), fix que afecta TODAS las versiones cliente:
En `.github/workflows/release.yml`, el job `release` ahora (a) marca la release como `draft=true` al arrancar (usando `gh release edit --draft`), (b) sube todos los assets, y (c) usa `softprops/action-gh-release@v2` con `draft: false` al final para publicarla. Una release en draft NO aparece en GitHub's `/releases` public API, así que el `AutoUpdater` de cualquier instalación (incluso versiones viejas sin el fix de v4.2.10) nunca la ve hasta que los binarios estén listos.

La capa 2 es la que realmente resuelve el problema. La capa 1 queda como defensa en profundidad por si el workflow falla mid-way (entonces la release queda draft para siempre — aceptable, nadie la ve).

**Por qué tardé tres intentos en arreglarlo bien:**

1. v4.2.3/v4.2.4: no identifiqué el patrón, asumí que era un problema de tests fallidos en CI (lo era en parte, pero no era el bug real).
2. v4.2.10: atacé solo el cliente. Problema: el cliente viejo no tiene el fix, así que el bug sigue apareciendo para usuarios que tardan en actualizar (que son la mayoría).
3. v4.2.11: al fin ataqué el servidor. Este es el fix correcto porque no depende de que el cliente haga nada especial.

**Lección meta:** cuando el bug está en la frontera cliente/servidor, siempre mirar PRIMERO si se puede arreglar en el servidor. El servidor lo ven todos los clientes; el cliente lo ven solo los que actualicen.

**Tests que previenen regresión:**

- `AutoUpdaterDetectionTest` cubre la detección del bundle Windows pero no cubre este caso (release sin binarios). Si se hace v4.3 o similar, agregar test `checkForUpdate skips release without JAR assets`.

---

## Patrón de bug recurrente: detector de ffmpeg falla en Windows no estándar

**Síntoma:** "El video solo graba los primeros 2:56 segundos" (el primer segmento de 3 min). Reportado en v4.2.2.

**Causa raíz:** `AdbBridge.findFfmpegImpl` usaba `which ffmpeg` (Unix-only, no-op en Windows) + un solo path hardcoded `C:\ffmpeg\bin\ffmpeg.exe`. Usuarios con ffmpeg via WinGet, Scoop, Chocolatey o carpeta custom tenían `null` y el concat silenciosamente no corría.

**Fix definitivo (implementado en v4.2.3):** `core/ToolResolver.kt` unifica la lógica. En Windows usa `where`, en Unix `which`. Candidates Windows: `C:\ffmpeg\bin\`, `C:\Program Files\ffmpeg\bin\`, `%ProgramData%\chocolatey\bin\`, `%USERPROFILE%\scoop\shims\`, `%USERPROFILE%\scoop\apps\ffmpeg\current\bin\`, y glob de `%LOCALAPPDATA%\Microsoft\WinGet\Packages\*FFmpeg*\ffmpeg-*\bin\`. Tests: `ToolResolverTest`.

**Lección general:** **nunca hardcodear UN solo path de una herramienta externa en un SO**. Usar siempre la búsqueda del OS (`where`/`which`) primero + una lista amplia de candidates por package manager conocido. Factorizar en un helper puro testeable.

**⚠️ Regla operativa — cualquier nueva dependencia externa pasa por `ToolResolver.find` desde el primer commit.** No hand-roll. Si la herramienta tiene paths no-genéricos (ej. Android SDK para adb), agregar una tabla en `toolSpecificCandidates`. Nunca más un `ProcessBuilder("which", ...)` ni un listado Unix-only disperso por el código.

**Reincidencia v4.2.13:** aunque `ToolResolver` existía desde v4.2.3, `AdbBridge.adbPath` e `IosBridge.findFfprobe` tenían cada uno una copia del mismo patrón roto (v4.2.2) — `which` en Windows, lista mínima de Unix paths, `C:\platform-tools\adb.exe` como único Windows candidate para adb. v4.2.13 migra ambos a `ToolResolver.find` y agrega una tabla `adbCandidates` con Android Studio (Win/Mac/Linux), Homebrew casks y distros Linux. **Esta fue la segunda vez que el mismo patrón se escapó; de ahí la regla operativa arriba.**

---

## Patrón de bug recurrente: mojibake (`â€"`, `Ã±`) en textos leídos de GitHub API

**Síntoma:** el banner de actualización muestra caracteres raros en vez de tildes, eñes, o em-dashes. Reportado en v4.2.3.

**Causa raíz:** `BufferedReader(InputStreamReader(stream))` Java-style sin charset explícita → usa `Charset.defaultCharset()` → en Windows con locale español es `windows-1252`. GitHub responde UTF-8 → decodificación incorrecta.

**Fix definitivo (implementado en v4.2.4):** pasar `StandardCharsets.UTF_8` explícitamente a todos los `InputStreamReader` que lean HTTP responses.

**Lección general:** las extensiones Kotlin `InputStream.bufferedReader()` y `File.readText()` defaultean a `Charsets.UTF_8`. Las construcciones Java equivalentes NO. **Preferir las extensiones Kotlin cuando existen.** Si hay que usar Java style por compatibilidad, pasar `StandardCharsets.UTF_8` siempre.

---

## Patrón de bug recurrente: métricas que miden el sistema, no el juego

**Síntoma reportado (v4.2.4):** "El CPU% es siempre muy bajo aunque el juego esté al 100%".

**Causa raíz:** `captureCpuPercent(deviceId)` leía `/proc/stat` (device-wide) en lugar de `/proc/<pid>/stat` (del juego). Similar con Frame Drops (counter de SurfaceFlinger es global), y con Jank threshold (hardcoded a 16.67ms = 60fps target).

**Fix definitivo (implementado en v4.2.5, v4.2.6, v4.2.7):**

- CPU: nuevo overload `captureCpuPercent(deviceId, pkg)` que lee `/proc/<pid>/stat`
- Jank: threshold dinámico `1.5 * inferTargetFrameTime(avgFrameTime)` en vez de 16.67ms constante
- Frame Drops: mantenido en el report pero NO se usa en el grading (se usa `totalJank` per-game)

**Lección general:** cualquier métrica de "performance del juego" tiene que ser **scoped al proceso del juego**. Cuando se agrega una métrica nueva, verificar que el counter sea específico del proceso y no del sistema entero.

---

## Patrón de bug recurrente: «el video va lentísimo al arrastrar el timeline hacia la derecha»

**Síntoma reportado múltiples veces entre v4.2.3 y v4.3.1** (el usuario insistió diciendo que lo había reportado «millones de veces» sin que se arreglara). Al arrastrar el cursor del timeline hacia la derecha el video se vuelve lentísimo de forma súbita — no desde el primer frame, sino después de unos segundos de scrub.

**Causa raíz (v4.3.2): dos bugs superpuestos que se amplificaban entre sí.**

1. **`FrameCache` sobredimensionado** — la clase documentaba en KDoc que el sweet spot eran 600 frames (~900MB), pero el call site en `EmbeddedVideoPlayer` forzaba 1500. A ~1.5MB por frame decodificado, 1500 × 1.5MB ≈ 2.25GB, por encima del heap cap `-Xmx2048m`. Una vez el cache se llena (rápido arrastrando porque cada scrub agrega frames nuevos) el GC entra en stop-the-world de 200ms-1s cada pocos segundos → síntoma «de repente va lentísimo».

2. **`activeProcesses` compartido entre dos subsistemas** — el extractor de frames on-demand y el generador del thumbnail track (low-res preview) escribían al mismo `Collections.synchronizedSet(mutableSetOf<Process>())`. Cada scrub llamaba `preloadWindow(idx)` → `killActiveProcesses()` → **también** mataba el ffmpeg long-running que estaba generando los thumbnails. Resultado: si el usuario tocaba el timeline durante los 15-60s de generación (lo hacían siempre), el thumbnail track nunca se completaba, y entonces cada scrub caía al ffmpeg on-demand que es 10-20x más lento. Esto **amplificaba** el síntoma del bug #1 — más tiempo por scrub → más scrubs fallidos → más frames al cache → GC más frecuente.

**Fix definitivo (v4.3.2):**

- `FrameCache(1500)` → `FrameCache()` para respetar el default documentado de 600.
- `activeProcesses` separado en `activeFrameProcesses` (efímero, killed on scrub) + `activeThumbnailProcesses` (long-running, killed SOLO on dispose).

**Por qué tardé en arreglarlo:**

Los intentos anteriores solo miraban UNO de los dos bugs a la vez. Bajar el cache sin arreglar el sharing de procesos dejaba el problema visible (thumbnails seguían sin completarse). Arreglar el sharing sin bajar el cache dejaba el GC thrashing visible (aunque atenuado). Ambos fixes por separado parecían «no cambiar nada» porque el otro bug seguía presente. **Solo la combinación elimina el síntoma.**

**Lección meta:** cuando un bug persiste «misteriosamente» tras varios fixes, asumir que hay **más de una causa** y buscar la combinación. No declarar victoria hasta reproducir el caso reportado por el usuario end-to-end.

**Lección específica:** si dos subsistemas tienen ciclos de vida distintos (uno efímero, uno long-running) y comparten un `Collection<Process>` / `Collection<Job>` / cualquier recurso con `kill()`/`cancel()`, separarlos en dos colecciones. La alternativa («filtrar por tipo/tag dentro del set compartido») siempre termina olvidando algún caso.

---

## Patrón de bug recurrente: nombres de dispositivo con underscore en vez de guión

**Síntoma (v4.3.3):** Samsung Galaxy S23 aparece en la lista de dispositivos como `SM_S911B` en lugar de `Samsung Galaxy S23`. En el detalle de la sesión se ve correcto.

**Causa raíz:** dos fuentes del modelo devuelven formatos distintos:

- `adb devices -l` → imprime `model:SM_S911B` (guión bajo) porque su parser es space-delimited y reemplaza los guiones para no romper campos.
- `getprop ro.product.model` → devuelve `SM-S911B` (guión), que es la forma canónica de Samsung.

La tabla `DeviceNameResolver.codenameToMarketing` usa la forma con guión. El prefix match `SM-S911B.startsWith("SM-S911")` funciona, pero `SM_S911B.startsWith("SM-S911")` falla → caída al fallback crudo.

**Fix (v4.3.3):** normalizar el input en `resolve()` — `model.replace('_', '-')` antes del lookup. Una línea, cubre ambos call sites (`listDevices` + `getDeviceInfo`) en un solo lugar.

**Lección general:** cuando un módulo recibe el mismo dato desde dos fuentes y una de ellas aplica una transformación silenciosa (underscore por hyphen, case-folding, trim, etc.), **normalizar en el punto de entrada del módulo, no en cada fuente**. Si normalizás solo en `listDevices`, el próximo call site también vas a tener que parchearlo individualmente. El resolver es responsable de aceptar "todas las formas razonables del mismo código".

---

## Convención de releases

- Bump `appVersion` en `gradle.properties`
- Entrada al `CHANGELOG.md` con 3 secciones: Arreglos / Que hay de nuevo / Detalles tecnicos
- Commit + push a `main`
- `gh release create vX.Y.Z --title "vX.Y.Z — resumen" --notes-file <archivo.md> --target main`
- Esperar 6-7 min a que el workflow termine y suba los binarios
- **Desde v4.2.10: no hace falta preocuparse por que el usuario pulse Actualizar antes de que los binarios estén listos — el AutoUpdater ahora filtra releases sin assets.**

## Convención de idiomas

- **README.md**: castellano formal (tuteo, sin argentinismos: "mide", "pulsa", "juega" — NO "medí", "apretá", "jugá")
- **README_EN.md**: inglés completo, tracks el castellano section-by-section
- **UI in-app**: castellano (mismo registro que README)
- **CHANGELOG**: castellano (mismo registro)
- **Commit messages, KDoc, tests, comentarios**: inglés

## Arquitectura crítica

- Todo acceso ADB pasa por `AdbBridgeApi` (interface) → `RealAdbBridge` (producción) o `FakeAdbBridge` (tests)
- Todo acceso iOS pasa por `IosBridge` → `SidecarClient` (HTTP) → sidecar Python
- `AppViewModel` solo habla con tipos `core.model.*` (platform-agnostic)
- Tests puros sin mocks: cualquier función con lógica compleja debe tener una versión pura extraíble (ej: `computeFrameSnapshot`, `inferTargetFrameTime`, `inferGameTargetFps`, `candidatesFor`)
- Regex compilados como `private val` top-level, no inline (hot paths los re-compilarían)

## Historial de releases críticas

- v4.2.2: fix AutoUpdater cuando carpeta de instalación fue renombrada
- v4.2.3: fix ffmpeg detector Windows + thumbnail track del player
- v4.2.4: fix UTF-8 del banner
- v4.2.5-v4.2.7: reliability audit completo de métricas (CPU per-process, jank dinámico, thermal max, PSS App Summary, grading proporcional)
- v4.2.8: eliminación de integración Google Drive → export/import manual `.gameperf`
- v4.2.9: hotfix snackbar del export/import
- v4.2.10+: **fix "No hay JAR disponible"** — filtro de releases sin binarios publicados
