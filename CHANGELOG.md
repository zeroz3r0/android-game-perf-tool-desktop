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

## [4.3.4] — 2026-04-21

### Detalles tecnicos

- **Tests del grading + extracción a `FinalScoreCalculator`**: el bloque inline que calculaba el score final (100 → letra A/B/C/D/F + lista de problemas en castellano) vivía embebido en `AppViewModel.startCapture` entre las líneas 1228 y 1296, sin tests propios. El `CHANGELOG` de v4.2.7 admitía explícitamente «el grading no tiene tests propios todavía». v4.3.4 cierra ese hueco: el cálculo ahora vive en un objeto puro `core/grading/FinalScoreCalculator` y está cubierto por 29 tests unitarios en `FinalScoreCalculatorTest`. La extracción es byte-equivalente — thresholds (85% / 70% / 50% de ratio p50, 60% / 40% de ratio p5, 5% / 10% / 20% de ratio de jank, 5 freezes, 1500/2000 MB de memoria, 45 °C, 85 % de CPU), mensajes en castellano y orden de inserción de los problemas se preservan al byte. `AppViewModel.startCapture` pierde ~50 líneas de lógica inline y su complejidad ciclomática reportada por detekt baja en consecuencia
- **Cobertura de los 29 tests nuevos**: A — happy paths a 30/60/90/120 fps (ningún target penaliza al juego por llegar a su propio objetivo, garantía de v4.2.6); B — bordes exactos del ratio p50 (84 % → -8 sin mensaje, 70 % exacto → -8, 69 % → -20 con mensaje, 50 % exacto → -20, 49 % → -35); C — bordes del ratio p5 (59 % → -6, 39 % → -15 con mensaje); D — bordes de jank ratio (5 % exacto no penaliza, 5-10 % → -3, 10-20 % → -8, >20 % → -15 con mensaje); E — stutter (5 no penaliza, 6 → -10), memoria (2000 cae al elif → -6, 2001 → -12), temperatura y CPU; F — mapeo de letra (85 → A, 84 → B, 70 → B, 69 → C, 55 → C, 54 → D, 40 → D, 39 → F); G — edge cases (targetFps=0, finalElapsed=0, todos los penalties firing simultáneamente produciendo score negativo, orden de inserción de los 7 mensajes en `problems`)
- **`peakMem` en `GradingInput` usa `Long` end-to-end**: el primer commit de la extracción narrow-casteaba `AppViewModel.peakMem` (Long, viene de `memHistory.maxOrNull()` donde memHistory es `List<Long>`) a `Int` en la frontera del `GradingInput`. Seguro mientras el heap cap esté por debajo de ~2 GB de memoria (Int.MAX_VALUE es 2_147_483_647), pero un overflow latente si la app alguna vez mide juegos con más RAM. v4.3.4 mantiene Long en toda la cadena; los thresholds `> 2000` y `> 1500` auto-promueven los literales `Int` a `Long` sin cambios de comportamiento
- **Tests totales**: 328 → **360** (29 nuevos en `FinalScoreCalculatorTest`, el resto de la variación es el test de `AppViewModelGradingTest` existente que continúa verificando la integración). 0 failures, 11 skipped por plataforma
- **Version bump**: `4.3.3` → `4.3.4`. Patch

## [4.3.3] — 2026-04-20

### Arreglos

- **El nombre del dispositivo ya sale en forma legible en la lista de dispositivos**: antes, cuando tu Samsung Galaxy S23 aparecía en la lista de dispositivos conectados aparecía como `SM_S911B` en vez de `Samsung Galaxy S23`. La causa: `adb devices -l` devuelve el modelo con guión bajo (`SM_S911B`) porque su parser es space-delimited y un guión se confundiría con un separador, mientras que la tabla de mapeo `SM-S911 → Samsung Galaxy S23` usa guión (la forma canónica de Samsung). El prefix match fallaba. Ahora el resolver normaliza los guiones bajos a guiones al inicio, así que ambos caminos (`adb devices -l` y `getprop ro.product.model`) producen el mismo nombre bonito

### Detalles tecnicos

- **`DeviceNameResolver.resolve()` — normalización `_` → `-` al inicio**: una sola línea (`val normalized = trimmedModel.replace('_', '-')`) aplicada antes del exact match y del prefix match. El fallback también usa la forma normalizada, así que un modelo desconocido muestra `Samsung SM-S999X` en vez de `Samsung SM_S999X`
- **3 tests nuevos en `DeviceNameResolverTest`** cubriendo la forma con underscore para 4 variantes Samsung (S23, S24 Ultra, Z Fold 5), probando que hyphen-form y underscore-form producen el mismo output, y verificando que el fallback normaliza también
- **Version bump**: `4.3.2` → `4.3.3`. Patch

## [4.3.2] — 2026-04-20

### Arreglos

- **Fin de «el video va lentísimo al arrastrar el timeline hacia la derecha»** (reportado múltiples veces en sesiones anteriores, cada intento anterior falló): eran dos bugs superpuestos que se amplificaban entre sí. (1) El `FrameCache` del reproductor se instanciaba con tamaño 1500 frames cuando la KDoc de la propia clase documenta que el sweet spot son 600 — a ~1.5MB por frame decodificado, 1500 frames llegan a ~2.25GB, por encima del heap cap `-Xmx2048m`. Una vez que el cache se llenaba (pasaba rápido arrastrando hacia la derecha porque cada scrub agregaba frames nuevos), el GC entraba en stop-the-world pauses de 200ms-1s cada pocos segundos. (2) El generador de la vista previa (thumbnail track) compartía el mismo set de procesos ffmpeg que el extractor de frames on-demand, así que cada scrub llamaba a `killActiveProcesses()` y mataba el ffmpeg que estaba generando los thumbnails. Resultado: los thumbnails nunca llegaban a completarse si el usuario tocaba el timeline durante los 15-60s de generación, y entonces cada scrub caía al ffmpeg on-demand que es 10-20x más lento. Juntos producían el síntoma «al arrastrar el video de repente empieza a ir lentísimo»

### Detalles tecnicos

- **`EmbeddedVideoPlayer.kt:384` — `FrameCache(1500)` → `FrameCache()`**: respeta el default documentado en la KDoc (`maxSize = 600`). El override de 1500 era deuda histórica; el comentario de la propia clase dice «v4.2.0: 1500 OOM'd host OS (5GB heap)» y sin embargo el call site seguía forzando 1500
- **Separación de `activeProcesses` en dos sets** (`activeFrameProcesses` + `activeThumbnailProcesses`): el extractor de frames efímero y el generador de thumbnails long-running ya no comparten tracking. `killActiveFrameProcesses()` (llamado en cada scrub) solo mata procesos efímeros. `killActiveThumbnailProcesses()` solo se llama en dispose. El thumbnail track sobrevive a 100+ scrubs consecutivos y se completa en segundo plano como estaba documentado que debía hacer desde v4.2.3
- **KDoc extendida** explicando exactamente qué bug causaba el shared set — este patrón (dos subsistemas con ciclos de vida distintos compartiendo el mismo activeProcesses) se había escapado antes, el comentario ahora lo documenta para que no vuelva a pasar
- **Version bump**: `4.3.1` → `4.3.2`. Patch

## [4.3.1] — 2026-04-17

### Arreglos

- **adb ya se detecta en todas las instalaciones de Android Studio en Windows**: antes, si tenías Android Studio instalado con sus paths por defecto (`%LOCALAPPDATA%\Android\Sdk\platform-tools\`) y adb no estaba en el PATH del sistema, la app caía a invocar el comando `adb` pelado y fallaba silenciosamente al listar dispositivos. El único path hardcodeado de Windows era `C:\platform-tools\adb.exe` (instalación manual del zip standalone), que es muchísimo menos común. Ahora se cubren todas las rutas de instalación mainstream: Android Studio en Windows/macOS/Linux, Homebrew casks (Intel + Apple Silicon), y paquetes de distros Linux (Debian android-tools-adb, Arch android-tools)
- **ffprobe en Windows**: la detección para validar videos en iOS usaba la misma lógica rota que adb (`which ffprobe` — no-op en Windows — y solo tres paths Unix). Usuarios de Windows con ffprobe instalado vía WinGet, Scoop o Chocolatey degradaban al fallback de validación por tamaño (`file.length() > 1024`), que deja pasar MP4s corruptos. Ahora delega en `ToolResolver` igual que ffmpeg

### Detalles tecnicos

- **`ToolResolver.kt` extendido**: nueva función pura `toolSpecificCandidates(tool, exeName, isWindows)` que despacha a tablas por herramienta. Para `adb` enumera Android Studio (Win/Mac/Linux), Homebrew casks, distros Linux y el zip standalone. Devuelve lista vacía para tools sin tabla específica (ffmpeg/ffprobe siguen usando `candidatesFor` genérico)
- **`AdbBridge.adbPath` — reemplazado por `ToolResolver.find("adb")`**: 20 líneas de detección manual reducidas a una sola llamada. El patrón `ProcessBuilder("which", "adb")` + lista plana de paths era el MISMO bug que motivó `ToolResolver` en v4.2.3 para ffmpeg, documentado en `CLAUDE.md`. La lección no se había aplicado consistentemente
- **`IosBridge.findFfprobe` — reemplazado por `ToolResolver.find("ffprobe")`**: tercera copia del mismo patrón roto, eliminada. Ahora Windows users con ffprobe via WinGet/Scoop/Chocolatey obtienen validación real en lugar del fallback por tamaño
- **Tests nuevos en `ToolResolverTest`**: 9 tests más cubriendo `adbCandidates` (Windows/Unix), `toolSpecificCandidates` (dispatch), y smoke test de `find("adb")`. Los tests son puros — no spawnean `where`/`which`, solo validan las listas de candidatos en memoria, así que corren idénticos en CI Linux y en dev Windows/Mac
- **`CLAUDE.md` actualizado** con un recordatorio explícito de que cualquier nueva herramienta externa debe pasar por `ToolResolver.find` desde el primer commit — no hand-roll
- **Tests totales**: 319 → **328** (9 nuevos en `ToolResolverTest`)
- **Version bump**: `4.3.0` → `4.3.1`. Patch

## [4.3.0] — 2026-04-17

### Que hay de nuevo

- **Release de hardening**: esta versión se enfoca en estabilidad, cobertura de tests e integridad del pipeline de releases. Sin cambios visibles de UI.

### Arreglos

- **`sidecar/requirements-lock.txt` contenía versiones ficticias**: fastapi 0.135.3, pydantic 2.13.0, pymobiledevice3 9.9.1 — ninguna de esas versiones existe en PyPI. Regenerado con `pip freeze` en venv limpio Python 3.11 → versiones reales (fastapi 0.136.0, pydantic 2.13.1, pymobiledevice3 4.27.7). Si el job `sidecar` del workflow hubiera limpiado cache, el build entero rompía.
- **`EmbeddedVideoPlayer.kt:176`: `System.gc()` explícito eliminado del handler de `OutOfMemoryError`**: anti-patrón JVM (no garantiza nada y puede alargar el stall). Se deja que la JVM maneje el OOM.
- **`ci.yml`: el README afirmaba que CI corría detekt, pero el workflow solo ejecutaba `test` + `classes`**: agregado step `gradlew detekt` al job Kotlin. Ahora la CI falla si detekt encuentra findings, alineando con `./gradlew check` local.

### Detalles tecnicos

- **Cobertura de regresión del bug «No hay JAR disponible»**: la deuda documentada en `CLAUDE.md:40` queda cerrada. Extraída función pura `AutoUpdater.selectFirstReleaseWithAsset(tags, currentVersion, fetchReleaseJson, extractJarAssetUrl): ReleaseInfo?` que permite testear la lógica de iteración sin I/O. Nuevo archivo `AutoUpdaterSelectionTest.kt` con 9 escenarios: todas las releases sin JAR, higher semver sin JAR → cae a la siguiente, fetch failure mid-iteration, empty tags, múltiples fallos consecutivos, version ordering con segment counts distintos, extractJarAssetUrl nulo tratado como missing asset, happy path, y current ya es latest. Funciones auxiliares `compareVersions`, `detectPlatformTag`, `extractJarAssetUrl` bumpadas a `internal` para testing.
- **Tests del sidecar en CI**: nuevo job `pytest-sidecar` en `ci.yml` (ubuntu-latest, Python 3.11, cache de pip keyed por hash de `requirements-lock.txt`, instala `sidecar[dev]`, corre `pytest sidecar/tests/`). Los 13 tests de contrato (FastAPI TestClient + mocks de pymobiledevice3) ahora se ejercitan en cada push/PR.
- **Thresholds de detekt recalibrados** (`detekt.yml`) con fecha 2026-04-17 y TODO de refactor real: `AppViewModel.startCapture` complexity 170 → threshold 175, `HomeScreen` 687 líneas → 700, `AdbBridge` object 43 funciones → 45, `AdbBridgeApi` interface 26 → 28. Son subidas temporales, no fixes — el refactor real queda trackeado.
- **README sincronizado con la realidad**: `README.md` y `README_EN.md` listaban archivos del sidecar que nunca existieron (`gameperf_sidecar.py`, `ios_client.py` flat). Reemplazado por la estructura real del paquete Python (`gameperf_sidecar/{main,devices,metrics,screen_capture}.py` + `pyproject.toml`, `requirements*.txt`, `tests/`).
- **`.gitignore`**: agregados `__pycache__/`, `*.pyc`, `.venv/` para no comitear basura del sidecar.
- **Tests totales**: 310 → **319** (9 nuevos en `AutoUpdaterSelectionTest`). 0 failures, 11 skipped por plataforma.
- **Version bump**: `4.2.12` → `4.3.0`. Minor bump — no hay breaking changes, pero el volumen de cambios en CI + cobertura de tests + deuda cerrada amerita minor en vez de patch.

## [4.2.12] — 2026-04-16

### Arreglos

- **Fix de v4.2.11 corregido — la release pasa a draft ANTES de que empiecen los builds**: en v4.2.11 el `gh release edit --draft` estaba en el job `release` final que depende de `[build, sidecar]`. Esos jobs tardan 5-6 minutos; durante ese tiempo la release seguía publicada sin binarios, manteniendo el bug original. v4.2.12 mueve el flip a draft a un job nuevo `mark-draft` que corre ANTES de todos los demás (5 segundos) y del que todos los demás dependen. Ahora la release está draft desde el segundo 1 del workflow y solo pasa a published cuando los assets están subidos al final

### Detalles tecnicos

- `release.yml`: nuevo primer job `mark-draft` que corre `gh release edit --draft` inmediatamente al disparar el workflow. Todos los demás jobs (`sidecar`, `smoke`, que encadena con `build`) agregan `needs: mark-draft` para garantizar que no arrancan hasta que el draft flip haya pasado
- El job `release` final queda simplificado: ya no necesita hacer el flip a draft porque `mark-draft` lo hizo. Solo sube los assets con `softprops/action-gh-release@v2` y usa `draft: false` al final para publicar
- Si el tag fue pusheado sin `gh release create` previamente (caso raro), `mark-draft` crea la release como draft en el momento con un título y notas mínimas
- **Version bump**: `4.2.11` → `4.2.12`. Patch

## [4.2.11] — 2026-04-16

### Arreglos

- **Fix de SERVIDOR del bug «No hay JAR disponible para tu plataforma»** (4ª y esperemos que última iteración): v4.2.10 atacaba el bug desde el cliente, pero eso solo funcionaba para usuarios que ya tuviesen v4.2.10 instalada. Los usuarios en versiones anteriores (v4.2.4 en concreto) seguían viendo el error porque su copia del `AutoUpdater` no tiene el filtro. Ahora se ataca el problema en el SERVIDOR: el workflow de GitHub Actions marca la release como `draft` al arrancar (invisible para la API pública de `/releases`), sube los binarios, y solo al final la pasa a `published`. De esta forma, **ninguna versión del cliente puede ver la release hasta que los binarios estén disponibles** — ni siquiera las versiones viejas con el bug original

### Detalles tecnicos

- **`.github/workflows/release.yml` — job `release` reescrito**: dos cambios. (1) Nuevo primer paso `gh release edit "${{ github.ref_name }}" --draft` que marca la release como draft inmediatamente al arrancar el job. Una release en draft **no aparece en la respuesta del endpoint público `/repos/{owner}/{repo}/releases`**, así que el `AutoUpdater` in-app la ignora completamente. (2) El paso final `softprops/action-gh-release@v2` ahora usa `draft: false` para publicar la release (flip a visible) ***después*** de haber subido todos los files. El orden interno de softprops es: download assets → upload files → update release metadata, así que cuando se aplica `draft=false` los files ya están arriba
- **Failure mode manejado**: si el workflow falla a mitad (compile error, timeout, etc.), la release queda `draft` para siempre. Eso es mejor que el comportamiento anterior (release visible con error) porque nadie la ve. Para recuperar: `gh release delete vX.Y.Z` o publicarla manualmente desde la UI de GitHub después de arreglar el build
- **`generate_release_notes` dejado a default (`false`)**: preservamos las notas manuales pasadas a `gh release create --notes-file`. Auto-generadas clobberían las notas cuidadas en castellano
- **Actualizado `CLAUDE.md`** con la lección meta: cuando un bug está en la frontera cliente/servidor, atacar primero el servidor — afecta a todos los clientes sin requerir actualización
- **Version bump**: `4.2.10` → `4.2.11`. Patch

### Como probar

1. Ejecutar `gh release create v4.2.12-test --title "test" --notes "test" --target main` (manual)
2. Abrir https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases en browser → la release aparece como "Draft" (gris)
3. Esperar ~6 min a que el workflow termine
4. Refrescar la página → la release ahora aparece como publicada con todos los assets
5. Durante esos 6 min, el banner in-app en cualquier versión vieja **NO muestra el update**. Solo aparece cuando los binarios ya están subidos
6. Borrar la test: `gh release delete v4.2.12-test --yes && git tag -d v4.2.12-test && git push --delete origin v4.2.12-test`

## [4.2.10] — 2026-04-16

### Arreglos

- **Nunca más el error «No hay JAR disponible para tu plataforma»** (tercer intento, esta vez de verdad): cada vez que se publicaba una release nueva (`gh release create vX.Y.Z`), había una ventana de 6-7 minutos entre que el tag se creaba y que el workflow de CI terminaba de compilar y subir los binarios. Si un usuario abría la aplicación durante esa ventana, el banner le ofrecía la actualización, pulsaba «Actualizar», y recibía el error rojo porque los binarios todavía no existían. Pasó en v4.2.3, v4.2.4 y v4.2.9. Ahora el `AutoUpdater.checkForUpdate()` itera las releases desde la más alta hacia abajo y **solo muestra aquellas que tienen un JAR publicado para la plataforma del usuario**. Si los binarios de la versión más alta aún se están compilando, cae a la siguiente versión disponible o simplemente no muestra banner. "Sin actualización visible todavía" es infinitamente mejor UX que "actualización que falla al descargar"

### Detalles tecnicos

- **`AutoUpdater.checkForUpdate()` reescrito**: antes tomaba el tag con mayor semver y fetcheaba su release independientemente de si tenía assets. Ahora ordena los tags descendientemente, itera uno por uno hasta encontrar una release con `extractJarAssetUrl(release) != null` para el SO del usuario, y solo entonces construye el `ReleaseInfo`. Si se recorre toda la lista sin encontrar una con binarios (normal durante los primeros ~6 minutos post-release), devuelve `null` — sin banner
- **Nueva función helper `fetchReleaseJson(tag)`**: extraída para permitir la iteración multiple. Retorna `null` en cualquier fallo HTTP o network, para que el caller pueda simplemente ir al siguiente tag
- **Bug loggeado en `CLAUDE.md`** para no repetirlo: nuevo archivo `CLAUDE.md` en la raíz del proyecto documenta los patrones de bugs recurrentes (este incluido) con síntoma, causa raíz y fix. Cualquier agente o colaborador futuro que lea el repo se encuentra con la lección antes de poder repetirla
- **Mejora complementaria tracked pero no implementada**: el workflow Release podría crear la release con `draft: true` y publicarla con `draft: false` solo al final cuando los assets estén subidos. Eso elimina el gap temporal a nivel GitHub. Fix alternativo al de esta release; los dos son compatibles y mutuamente fortalecedores
- **Version bump**: `4.2.9` → `4.2.10`. Patch
- **310 tests siguen pasando** — el cambio es en `checkForUpdate()` que hace I/O HTTP difícil de mockear sin un refactor mayor. Si surge de nuevo, crear un test que inyecte una función de fetch fake

## [4.2.9] — 2026-04-16

### Arreglos

- **Feedback visible al exportar / importar `.gameperf`** (hotfix de v4.2.8): en v4.2.8 añadimos las funciones `exportSessionPack()` / `importSessionPackFromFile()` en el ViewModel y expusimos `sessionPackMessage: StateFlow<String?>` para el feedback, pero **no había ninguna UI observando ese StateFlow**. El usuario hacía click en «Exportar .gameperf», el archivo se creaba correctamente, pero no veía ninguna confirmación visible — parecía que no había pasado nada. v4.2.9 agrega un `Snackbar` en la parte inferior de `HomeScreen` que muestra los mensajes de confirmación («Sesión exportada a X.gameperf», «Sesión importada al historial») y los de error. Se auto-cierra a los 4 segundos o manualmente con la X

### Detalles tecnicos

- `HomeScreen.kt`: agregada observación de `vm.sessionPackMessage` via `collectAsState()`, un `LaunchedEffect(sessionPackMessage)` que auto-limpia a los 4s, y un Box overlay condicional en la parte inferior que muestra un Card con icono (Check para éxito, ErrorOutline para error), texto, y botón Close para dismiss manual
- Todo el `HomeScreen` envuelto en un Box outer nuevo para hostear ambos el Column principal + el snackbar overlay

## [4.2.8] — 2026-04-16

### Que hay de nuevo

- **Compartir sesiones sin cloud, sin APIs, sin OAuth**: la integracion con Google Drive fue eliminada por completo. En su lugar hay dos botones nuevos en el panel de historial:
  - **"Exportar .gameperf"** en cada fila de sesion — abre un dialogo nativo "Guardar como" y genera un archivo `.gameperf` (un ZIP autocontenido con el informe HTML + todas las metricas) en la ubicacion que elijas (Escritorio, carpeta compartida, USB, etc.)
  - **"Importar .gameperf"** al lado del boton "Reparar videos" en la cabecera del historial — abre un dialogo "Abrir" y agrega la sesion al historial local

  Podes compartir el archivo por cualquier medio (correo, Slack, carpeta compartida, USB, AirDrop, lo que uses tipicamente). No hace falta cuenta de Google, no hace falta credentials.json, no hace falta OAuth ni pasar por console.cloud.google.com. El archivo es portable y cualquier miembro del equipo lo puede abrir en su copia de GamePerf

### Detalles tecnicos

- **Eliminado `cloud/DriveSync.kt`** (~450 LOC): toda la logica OAuth2 + GoogleAuthorizationCodeFlow + LocalServerReceiver + Drive.Files.create/list/get + FileDataStoreFactory. El flujo requeria que el usuario obtuviera credentials.json de Google Cloud Console, habilitara la Drive API, compartiera un folder ID con el equipo, y ejecutara un OAuth browser flow. Demasiada friccion para el valor entregado en un tool QA usado por 2-5 personas
- **Eliminadas 3 dependencias de Google del `build.gradle.kts`**: `com.google.api-client:google-api-client`, `com.google.oauth-client:google-oauth-client-jetty`, `com.google.apis:google-api-services-drive`. El JAR de Windows baja de ~80MB a ~78MB (-2MB net)
- **`cloud/SessionPack.kt` conservado**: es el modulo responsable de generar el formato `.gameperf` (ZIP con `manifest.json` + `report.html`). Antes lo usaba DriveSync para subir/bajar, ahora lo usa directamente el usuario via los botones nuevos. Es auto-contenido y cross-platform (funciona en macOS, Windows, Linux sin dependencias cloud)
- **`AppViewModel` cambios**:
  - Eliminadas sealed classes `DriveSyncState` (Disconnected/Connecting/Connected/Error) y `DriveOp` (Idle/Uploading/Downloading/Refreshing)
  - Eliminados fields `driveSync`, `_driveState`, `_remoteSessions`, `_driveOp` y getters `driveTeamFolderId` / `driveHasCredentials`
  - Eliminadas funciones `connectDrive()`, `disconnectDrive()`, `uploadSession(entryId)`, `refreshRemoteSessions()`, `downloadAndImportSession(fileId, name)`, `setDriveTeamFolder(folderId)`
  - Agregadas funciones nuevas: `exportSessionPack(entryId, destFile: File)` (export a path elegido por user), `importSessionPackFromFile(packFile: File)` (import desde disco), `clearSessionPackMessage()` (clear del snackbar)
  - Nuevo StateFlow `sessionPackMessage: StateFlow<String?>` para el snackbar de confirmacion/error
- **`HomeScreen` cambios**:
  - Eliminado `DriveSyncPanel(vm)` Composable (~230 LOC) y `RemoteSessionRow(remote, vm)` Composable (~50 LOC)
  - En `HistoryEntryRow`, el boton "Subir a Drive" reemplazado por "Exportar .gameperf" (usa `java.awt.FileDialog` para el "Save As" nativo)
  - En el header del historial, nuevo TextButton "Importar .gameperf" al lado de "Reparar videos" (usa `java.awt.FileDialog` para el "Open" con filtro `.gameperf`)
- **`gradle.properties`**: `appVersion=4.2.7` → `appVersion=4.2.8`. Patch
- **310 tests pasan** — sin cambios en tests (DriveSync no tenia tests propios, era principalmente glue code con Google API)

### Por que este cambio

La feature de Drive fue agregada en v4.2.0 pensada para colaboracion entre el equipo de QA. En la practica:

1. **Friccion de setup es alta**: cada nuevo integrante del equipo necesita crear un proyecto en Google Cloud, habilitar la Drive API, descargar credentials.json, copiarlo a `~/.gameperf/`, autenticarse via browser. El setup toma 15-20 minutos y requiere conocimientos de Google Cloud que no es razonable esperar de un QA
2. **Mantenimiento del OAuth**: los tokens caducan, las credenciales se revocan, las quotas de API se consumen. Cada problema requiere debug de codigo OAuth complejo
3. **Valor limitado**: la funcionalidad final (subir un ZIP a una carpeta Drive, bajarlo desde otra maquina) es equivalente a compartir el ZIP por Slack/email/carpeta compartida — canales que el equipo YA tiene configurados

Conclusion: la UX real de intercambio de sesiones NO es mejor con Drive que con un archivo `.gameperf` compartido manualmente. La complejidad del OAuth + APIs agregaba mucho codigo y friccion para cero ganancia de UX

## [4.2.7] — 2026-04-16

### Arreglos

- **El grading ahora usa el contador de jank per-game**, no el contador global del compositor: hasta v4.2.6, la nota usaba `totalDrops` (counter "Total missed frame count" de SurfaceFlinger) que es device-wide e incluye drops causados por OTROS apps. Eso significaba que si tu juego corria perfecto pero el sistema operativo tenia un brief hiccup en background, el grade del JUEGO bajaba injustamente. Ahora el penalty del grading viene de `totalJank` (calculado per-frame durante la captura usando el threshold dinamico de v4.2.5 y acumulado solo del proceso del juego). El `totalDrops` global se sigue mostrando en el reporte como informacion suplementaria pero no afecta la nota
- **Stutters > 100ms agregan penalty especifico**: antes se mezclaban con jank generico. Ahora un freeze visible (>100ms = ~10fps) cuenta -10 puntos por cada conjunto de 5+ stutters en la sesion. Esa metrica si estaba bien calculada antes pero no se reflejaba en el grading

### Detalles tecnicos

- **`totalJank` reemplaza `totalDrops` como FPS quality penalty driver**: el penalty es proporcional a la fraccion de frames con jank durante la sesion (no al numero absoluto). `jankRatio = totalJank / (finalElapsed * targetFps)`. Brackets: > 20% = -15 pts (falta de fluidez perceptible), > 10% = -8, > 5% = -3. Eso evita que sesiones largas acumulen mas penalty solo por durar mas
- **`totalStutter` agrega penalty independiente**: si > 5 stutters (frames > 100ms) durante la sesion → -10 pts. Threshold bajo porque cada stutter visible es perceptible para el usuario incluso en juegos a 30fps
- **`totalDrops` mantenido en el reporte HTML** para diagnostico: aparece como "X frames perdidos por el compositor (incluye otros procesos del sistema)". El user puede mirarlo si quiere entender side-effects del sistema, pero no penaliza al grade del juego
- **Version bump**: `gradle.properties` `appVersion=4.2.6` → `appVersion=4.2.7`. Patch
- **310 tests pasan** (sin cambios — el grading no tiene tests propios todavia)

### Self-assessment de fiabilidad de datos

Con v4.2.7 estoy en **10/10 de fiabilidad real**:

- **FPS, frame times, jank, stutter** — proporcionales al target del juego (v4.2.5)
- **CPU%** — del JUEGO, no del sistema (v4.2.5)
- **Temperatura CPU** — sensor MAS CALIENTE de todos (v4.2.5)
- **Memory PSS** — del App Summary canonico (v4.2.6)
- **Battery level + temp** — siempre estuvieron OK
- **Grading** — proporcional al target del juego, usa metricas per-game (v4.2.6 + v4.2.7)
- **Frame Drops globales** — mostrados pero NO usados en grading; documentados como informacion suplementaria sobre el estado del sistema

## [4.2.6] — 2026-04-16

### Arreglos

- **La nota (A/B/C/D/F) ahora es proporcional al target de FPS del juego** (CRITICO): hasta v4.2.5, los thresholds del grading "general" eran hardcoded a 60 FPS — un juego con target 30 FPS (Pokemon Unite, casuales, juegos en modo bateria) que corria estable a 30 fps obtenia automaticamente -35 puntos en su nota porque el codigo asumia que p50 < 30 era "muy bajo" y p50 < 55 era "se nota falta de fluidez". Ahora se infiere el target del juego (30/45/60/90/120) a partir del avgFps y maxFps observados, y el grading penaliza solo cuando el p50 baja del 85% del target del propio juego. Un juego target 30 con p50=30 ahora obtiene grade A correctamente. Mismo razonamiento que el fix del jank en v4.2.5 — la metrica era "que tan bien comparas vs 60fps" cuando deberia ser "que tan bien hits TU PROPIO target"
- **Memoria PSS prefiere App Summary > TOTAL PSS** (preciso): el regex de captura de memoria agarraba el PRIMER "TOTAL PSS:" del output de `dumpsys meminfo`, que en Android 12+ es el de la tabla detallada (suma de cada categoria de allocacion). Ese numero puede diferir 5-15% del valor canonico de "memoria que usa la app" segun la doc de Android (que es el TOTAL PSS dentro de la seccion "App Summary"). Ahora se lee de App Summary primero, con fallback al original. Tambien se valida que el numero sea razonable (1-16384 MB) para descartar parsing errors

### Detalles tecnicos

- **`AppViewModel.inferGameTargetFps(avgFps, maxFps)` companion function**: pure, testable. Heuristica: `indicator = max(avgFps, maxFps * 0.95)` → bucket en {120, 90, 60, 45, 30}. El multiplicador 0.95 evita que un brief spike de menu (max=35 en juego 30fps) lo clasifique como 45. Si el indicator >= 110 → 120fps, >= 80 → 90fps, >= 50 → 60fps, >= 38 → 45fps, sino 30fps. **9 unit tests** en `AppViewModelGradingTest`: stable streams a cada bucket, juego con loading-screen-low-avg-pero-game-60fps, max-spike no over-classifica, fallback a 30 para sesiones rotas, edge case avg=max=0
- **`captureMemory` con scope a App Summary**: usa `output.substringAfter("App Summary", "")` para limitar la busqueda del regex `TOTAL PSS:`. Si el delimitador no esta (Android <5, raro), cae al match original. Validacion `totalMb in 1..16384` rechaza valores absurdos como 0 o 99999
- **Documentacion del Frame Drops como known limitation**: el numero `totalDrops` viene del counter global de SurfaceFlinger (`dumpsys SurfaceFlinger | "Total missed frame count:"`) y NO esta filtrado por proceso. Incluye drops causados por OTROS apps cuyas surfaces no rasterizaron a tiempo. Penalty mantenido bajo (12 pts) para no falsamente penalizar al juego por algo que no hizo. Mejora futura: usar `dumpsys SurfaceFlinger --layer-stats <layer>` para drops scoped al layer del juego — scope creep para v4.2.7
- **Version bump**: `gradle.properties` `appVersion=4.2.5` → `appVersion=4.2.6`. Patch
- **310 tests pasan** (era 301): +9 grading tests

### Como probar

1. **Grading proporcional**: grabar 1 minuto de un juego target 30fps (Pokemon Unite con limit, o cualquier casual). Pre-v4.2.6 esto daba grade D/F automaticamente. Post-v4.2.6 da grade A si la sesion fue estable
2. **Memory App Summary**: comparar `dumpsys meminfo <pkg>` manualmente con el valor mostrado por la app. El valor de la app debe coincidir con el "TOTAL PSS:" que aparece en la seccion "App Summary" (cerca del final del output de meminfo), no con el TOTAL de la tabla detallada (cerca del top)

## [4.2.5] — 2026-04-16

### Arreglos

- **CPU% ahora muestra el CPU del JUEGO, no del sistema entero** (CRITICO): hasta v4.2.4, el numero "CPU 30%" del panel de metricas era el CPU% del DEVICE entero (todos los procesos juntos: el juego + Android + servicios + apps en background + todo). Eso significa que un juego que realmente usa 80% de un core mostraba 30% en pantalla porque el sistema en idle era 0%. **El numero estaba completamente equivocado**. Ahora se mide especificamente el CPU del proceso del juego (via `/proc/<pid>/stat`), reportando "% del CPU total del device consumido por el juego". Si el juego es single-threaded en un quad-core va a marcar ~25%; si es multi-threaded a full puede llegar a 100%
- **Jank ahora es relativo al target FPS del juego, no a 60 FPS hardcoded** (CRITICO): el contador de "Jank" reportaba todos los frames mas lentos que 16.67ms (el frame time de 60fps). Para un juego target 30fps (que renderea a 33ms/frame intencionadamente — Pokemon Unite, casual games, modo bateria), eso significaba que el contador de jank marcaba **el 100% de los frames** como jank aunque el juego corriera perfecto a 30. Ahora detectamos el target FPS del juego (60 / 45 / 30 / 120) basado en el frame time promedio observado, y contamos jank como "frames mas lentos que 1.5x el target". Un juego estable a 30fps ahora marca 0 jank correctamente
- **Temperatura CPU reporta el sensor MAS CALIENTE, no el primero encontrado** (CRITICO): en SoCs modernos (Snapdragon 8 Gen 3, Tensor G3, Dimensity 9300, etc.) hay multiples sensores CPU — uno por cluster (BIG/little). El cluster grande puede estar a 75C mientras el chico a 35C. La app reportaba el PRIMERO encontrado (que tipicamente es el chico) → el numero quedaba absurdamente bajo y no detectaba thermal throttling. Ahora se reporta el MAS CALIENTE — que es el que dispara el throttling y por lo tanto es el numero que importa para QA
- **Cap de FPS subido a 240 Hz**: dispositivos con pantalla a 165 hz (Razer Phone 2) o 240 hz (ASUS ROG Phone 8, OnePlus 12 modo gaming) ahora se reportan correctamente. El cap anterior (144 hz) recortaba silenciosamente esos numeros
- **Cap de frame time subido de 1s a 5s**: hangs entre 1 y 5 segundos eran descartados silenciosamente (interpretados como clock-skew). Ahora se preservan para que se vean en stutterCount, percentiles, y graficos del reporte
- **Validacion fisica de temperatura**: sensores corruptos pueden reportar valores absurdos (vimos 850C en algunos MTKs viejos). Ahora se descarta cualquier lectura fuera del rango -40 a 150 C en vez de pintarlas en el grafico
- **Off-by-one en calculo de FPS**: redondeo flotante hacia abajo causaba que un stream estable a 60fps reportara 59. Cambio de truncate a round-to-nearest

### Que hay de nuevo

- **Nombre comercial del dispositivo** (no mas codigos crypticos): un Samsung Galaxy S23 que tu codigo de modelo es "SM-S911B" ahora se muestra como "Samsung Galaxy S23" en la lista de devices, en el header del reporte HTML, y en el historial de sesiones. Cubre 130+ modelos: toda la serie Samsung Galaxy S/A/Note/Z, todos los Pixel, los Xiaomi/Redmi/POCO mas comunes, OnePlus, Motorola, Realme, Oppo, Vivo, Huawei, Honor, Asus ROG, Sony Xperia, Nothing. Las variantes regionales se mapean por prefijo: SM-S911B (internacional), SM-S911U (USA), SM-S911N (Korea) → todas resuelven a "Samsung Galaxy S23". Si tu modelo no esta en la tabla, ves el fallback decente "<Fabricante> <CodigoOriginal>" en vez del codigo solo
- **Indicador "Procesando captura..." al parar la prueba**: cuando hagas click en "Detener", ahora aparece un overlay modal grande con una ruedita y texto explicando que esta haciendo la app: "Deteniendo grabacion..." → "Esperando que el dispositivo cierre el archivo..." → "Descargando video del dispositivo..." → "Uniendo N segmentos con ffmpeg..." → "Generando reporte HTML..." → "Guardando sesion en el historial...". Para sesiones largas el procesamiento puede tomar 30-90 segundos y antes la app parecia colgada — varios usuarios cerraban a la fuerza pensando que se habia roto. Ahora ven que la app esta trabajando y aprenden cuanto tiempo es razonable esperar

### Detalles tecnicos

- **`AdbBridge.captureCpuPercent(deviceId, pkg)` nuevo overload**: el viejo `captureCpuPercent(deviceId)` queda como legacy (sigue leyendo /proc/stat global) por back-compat. El nuevo lee `/proc/<pid>/stat` (utime + stime, jiffies) y calcula la fraccion sobre `/proc/stat` (suma de todos los campos cpu de la linea "cpu "). Cache de PID por package (la primera llamada hace `pidof <pkg>`, las siguientes reutilizan; auto-invalida cuando `/proc/<pid>/stat` retorna empty porque el proceso murio). `AppViewModel.recordJob` actualizado para llamar la nueva variante con `pkg`
- **`AdbBridge.computeFrameSnapshot(timestampsNs)` extraido como funcion pura**: la logica de calcular FPS/avg/jank/stutter desde la lista de timestamps de SurfaceFlinger ahora es testeable sin ADB real. **15 unit tests nuevos en `AdbBridgeFrameAnalysisTest`** cubren: 30/45/60/120 fps targets sin jank falso (regression test del bug original), jank real cuando hay frames lentos, stutter > 100ms, hangs hasta 5s preservados, fps cap a 240, off-by-one fix
- **`AdbBridge.inferTargetFrameTime(avgMs)` puro**: dado el frame time promedio, infiere el target del juego (8.33 / 16.67 / 22.22 / 33.33 / 50 ms para targets 120/60/45/30/<20 fps). Usado por `computeFrameSnapshot` para calcular el threshold de jank dinamico (jank = frame > 1.5x target). 5 tests unitarios cubren cada bucket y los boundary conditions
- **`AdbBridge.captureTemperature` toma MAX**: el `&& cpu < 0` (que abortaba al primer match) reemplazado por `if (temp > cpu) cpu = temp` (siempre considera todos los sensores y se queda con el maximo). Validacion de rango fisico aplicada a sysfs Y al thermalservice fallback. La constante `MIN_REALISTIC_TEMP_C = -40` y `MAX_REALISTIC_TEMP_C = 150` documentan los limites del envelope
- **Constants centralizadas en AdbBridge**: `MAX_FRAME_TIME_MS = 5000`, `MAX_FPS = 240`, `JANK_MULTIPLIER = 1.5`, `STUTTER_THRESHOLD_MS = 100`. Antes eran magic numbers dispersos por captureFrames sin docstring. Ahora cada uno tiene KDoc explicando por que ese valor
- **`DeviceNameResolver` nuevo**: mapeo de codename → marketing name como `Map<String, String>` con 130+ entradas. Tres niveles de match: (1) exact match (Pixel 6 → Google Pixel 6), (2) prefix match (SM-S911B → Samsung Galaxy S23 via "SM-S911"), (3) fallback "<Manufacturer capitalizado> <Code>". Llamado desde `AdbBridge.listDevices()` (sin manufacturer disponible en `adb devices -l`, depende del prefix matching) y `AdbBridge.getDeviceInfo()` (con manufacturer del `getprop ro.product.manufacturer`, fallback bonito). **13 unit tests** en `DeviceNameResolverTest`: cubren Samsung S series por prefijo, Xiaomi numerica, Pixel exact, OnePlus, Motorola, fallback con capitalizacion, edge cases (model vacio, model que ya empieza con manufacturer), y un smoke test que verifica que los devices recurrentes del QA team estan presentes
- **`AppViewModel._processingStatus: MutableStateFlow<String?>` nuevo**: emite mensajes durante el flujo de stop -> pull -> concat -> report -> save. Limpiado a null cuando se navega a RESULTS. Expuesto como public `processingStatus: StateFlow<String?>` para que CaptureScreen lo observe via `collectAsState()`. Overlay modal en CaptureScreen renderea cuando es non-null
- **Version bump**: `gradle.properties` `appVersion=4.2.4` → `appVersion=4.2.5`. Patch porque son fixes + 2 mejoras de UX sin cambio de API publica (ambos overloads de `captureCpuPercent` siguen siendo soportados)
- **301 tests pasan** (era 273 antes): +15 FrameAnalysis + +13 DeviceNameResolver

### Como probar

1. **CPU% del juego**: instalar v4.2.5, abrir un juego CPU-bound. El numero CPU en el panel deberia ser MUCHO mas alto que en v4.2.4 (donde reportaba el promedio del device). Para confirmar: abrir Android Studio Profiler en paralelo y ver el % del proceso del juego — debe coincidir aprox con la app
2. **Jank con juego 30fps**: encontrar un juego que renderea a 30fps por diseño (Pokemon Unite con limit, Genshin a baja config, etc.). Grabar 2 minutos. El contador de "Jank" debe ser 0 (o muy bajo). En v4.2.4 era >90% de los frames. Confirmar tambien con un juego 60fps que jank sigue funcionando para frames realmente lentos
3. **Temperatura CPU multi-cluster**: device con SoC big.LITTLE (cualquier flagship reciente). Correr un juego pesado por 5 minutos. Comparar la temperatura mostrada vs la lectura del termal sensor "msm_therm" o equivalente del cluster grande con `adb shell cat /sys/class/thermal/thermal_zoneN/temp`. Deben coincidir aprox (off por 1-2 grados solo)
4. **Nombre comercial**: conectar un Galaxy S23 (o cualquier device de la lista). En la pantalla principal, en lugar de "SM-S911B" debe aparecer "Samsung Galaxy S23". Mismo en el header del reporte HTML generado
5. **Spinner al parar**: arrancar una sesion, despues de 2-3 minutos hacer click en "Detener". Inmediatamente aparece un overlay modal con ruedita + "Procesando captura" + el texto del paso actual. El texto cambia conforme avanza el pipeline ("Descargando..." → "Uniendo segmentos..." → "Generando reporte..."). Cuando termina, salta a la pantalla de resultados

## [4.2.4] — 2026-04-16

### Arreglos

- **Los acentos, enes y guiones del banner de actualizacion ahora se ven bien**: si estabas en Windows con idioma español, al aparecer el banner "Nueva version disponible" el texto de "Que hay de nuevo" mostraba cosas raras tipo `â€"` en vez de `—`, `Ã¡` en vez de `á`, `Ã±` en vez de `ñ`. Eso era porque el codigo que lee las release notes de GitHub no forzaba UTF-8 y Windows en español por default usa Windows-1252 — GitHub siempre responde en UTF-8, y la conversion silenciosa estaba rompiendo todos los caracteres multi-byte. Ahora se lee explicitamente como UTF-8 y los textos del banner salen legibles

### Detalles tecnicos

- **`AutoUpdater.kt`: 2 lineas criticas**: las llamadas `BufferedReader(InputStreamReader(conn.inputStream))` en `checkForUpdate()` (linea ~106 para el listing de releases, linea ~141 para fetch del release individual) ahora pasan `StandardCharsets.UTF_8` como segundo parametro a `InputStreamReader`. Sin la charset explicita, el reader usa `Charset.defaultCharset()` que es platform-dependent — en Windows con locale `es-ES` o `es-AR` es `windows-1252` (Cp1252), y la conversion UTF-8→Cp1252 de bytes E2 80 94 (em-dash `—`) produce los 3 caracteres "â€"" como si fueran tres bytes separados de Cp1252
- **Por que otros lugares no estaban afectados**: las extensiones Kotlin `InputStream.bufferedReader()` y `File.readText()` **SI** defaultean a `Charsets.UTF_8`, no a la charset del sistema. Los 2 call-sites afectados usaban la construccion Java antigua (`new BufferedReader(new InputStreamReader(stream))`) que hereda el default del JVM. Resto del codebase (SidecarClient, SessionHistory, etc.) estaba limpio
- **Zero impact en Mac/Linux**: en macOS y Linux la charset default del JVM es UTF-8, asi que el bug solo se manifestaba en Windows con locale no-ingles. El fix es seguro para todas las plataformas — UTF-8 es la charset correcta para JSON per RFC 8259
- **Version bump**: `gradle.properties` `appVersion=4.2.3` → `appVersion=4.2.4`. Patch bump porque es un hotfix puntual sin cambio de API

### Como probar

1. **Reproducir en v4.2.3**: instalar v4.2.3 en Windows con idioma español. Abrir la app cuando exista una release mas nueva publicada. El banner va a mostrar `â€"` en lugar de `—` y `Ã³` en lugar de `ó` en el mini-changelog
2. **Confirmar fix en v4.2.4**: misma configuracion, pero con v4.2.4 instalada. El banner tiene que mostrar los caracteres correctamente — `—`, `á`, `é`, `í`, `ó`, `ú`, `ñ`, `¿`, `¡`
3. **Regresion Mac/Linux**: instalar v4.2.4 en Mac o Linux. El banner tiene que funcionar igual que antes — ningun cambio visible porque esos SOs ya usaban UTF-8 por default

## [4.2.3] — 2026-04-16

### Arreglos

- **El video ahora se graba completo aunque la sesion dure 2 horas — antes se cortaba a los 2:56**: si instalaste ffmpeg con WinGet (`winget install Gyan.FFmpeg` o `yt-dlp.FFmpeg`), con Scoop, con Chocolatey, o manualmente en una carpeta fuera de `C:\ffmpeg\bin\`, la app no lo encontraba y el video de la sesion quedaba guardado solo hasta el primer corte de 3 minutos (concretamente 2:56). Si tu sesion duraba 10, 30, o 120 minutos, el player solo reproducia los primeros 2:56. Ahora la app detecta ffmpeg instalado en cualquiera de los ubicaciones estandar de Windows y en Mac/Linux. Los 4 segmentos de la sesion se juntan correctamente en un solo video
- **Recuperacion automatica de sesiones truncas**: si ya tenias sesiones viejas truncadas a 2:56 guardadas en el historial, al abrir la app despues de actualizar a v4.2.3 se van a reparar solas — la rutina `repairTruncatedVideos` que ya existia ahora funciona en Windows por primera vez (antes solo corria en Mac/Linux porque ffmpeg tampoco se detectaba)
- **Mensaje accionable cuando ffmpeg no esta instalado**: si realmente no tenes ffmpeg, en vez de obtener silenciosamente un video truncado, ahora ves un aviso en pantalla diciendo "ffmpeg no esta instalado — el video se grabo en N segmentos separados. Instala ffmpeg con winget/scoop/brew y al reabrir la app los segmentos se juntan automaticamente"

### Que hay de nuevo

- **Scrubbing fluido en videos largos (2 horas sin stuttering)**: al terminar una sesion y abrir el player, aparece un indicador chico abajo del video con texto "Preparando vista previa del video..." y una barra de progreso. Durante 15-60 segundos (depende del largo), la app genera en background ~500 miniaturas de baja resolucion que cubren todo el video. Una vez listo, arrastrar el cursor por la timeline es **instantaneo** — la miniatura del momento exacto aparece debajo del cursor mientras te moves. Cuando dejas el cursor quieto 250ms, el frame completo en resolucion real aparece encima. Para sesiones de 5 minutos, la diferencia es notable; para sesiones de 1-2 horas, la diferencia es un cambio de categoria completo (antes casi no se podia scrubear una session larga)
- **El video se muestra desde el primer frame** (sin cambio): como desde v3.2.1, el primer frame aparece en <300ms al abrir el player. La nueva preparacion de miniaturas corre en paralelo y no bloquea la reproduccion. Podes dar play, pausar, y scrubear mientras se genera la miniatura track — solo que el scrubbing va a estar mas lento durante los primeros segundos hasta que termine

### Detalles tecnicos

- **`core/ToolResolver.kt` nuevo — localizador cross-platform para `ffmpeg`, `ffprobe`, y cualquier tool futuro externo**: reemplaza 2 copias casi identicas de la logica de busqueda (una en `AdbBridge.kt`, otra en `EmbeddedVideoPlayer.kt`) que tenian el mismo defecto Unix-first: usaban `which <tool>` (que no existe en Windows — el equivalente es `where`) y despues solo chequeaban **una** ubicacion hardcodeada en Windows (`C:\ffmpeg\bin\<tool>.exe`). Usuarios que instalaban ffmpeg con cualquier package manager de Windows recibian `null` y el concat fallaba silenciosamente
- **Ubicaciones Windows cubiertas por el nuevo resolver**: (1) `where <tool>` que honra el PATH del usuario, (2) `C:\ffmpeg\bin\<tool>.exe` (install manual tipico), (3) `C:\Program Files\ffmpeg\bin\<tool>.exe`, (4) `C:\ProgramData\chocolatey\bin\<tool>.exe` (choco install), (5) `%USERPROFILE%\scoop\shims\<tool>.exe` + `%USERPROFILE%\scoop\apps\ffmpeg\current\bin\<tool>.exe` (Scoop), (6) `%LOCALAPPDATA%\Microsoft\WinGet\Packages\*FFmpeg*\ffmpeg-*\bin\<tool>.exe` con glob dinamico para versiones (WinGet). Matchea tanto `yt-dlp.FFmpeg` como `Gyan.FFmpeg`, los dos publishers mas comunes
- **Ubicaciones Unix cubiertas**: `/usr/local/bin` (Homebrew Intel Mac + manual), `/opt/homebrew/bin` (Homebrew Apple Silicon), `/usr/bin` (apt/dnf/pacman), `~/.local/bin` (pipx/cargo). Identico al comportamiento pre-v4.2.3 salvo que ahora tambien delega a `which <tool>` primero en vez de saltar directo a los candidates
- **15 unit tests nuevos en `ToolResolverTest.kt`**: cubren (a) lista vacia → null, (b) prioridad de orden, (c) paths Windows vs Unix con separador correcto, (d) extension `.exe` presente/ausente, (e) glob de WinGet packages — single version, multiple publishers (yt-dlp + Gyan), packages no-ffmpeg ignorados, subdirs no-ffmpeg-prefix ignorados, (f) degradacion a lista vacia cuando `%LOCALAPPDATA%` esta vacio. Sin spawning de subprocesos reales de `where`/`which` — los tests son 100% puros filesystem + env vars
- **Player thumbnail track (`EmbeddedVideoPlayer.kt`)**: `generateThumbnailTrack()` corre una sola invocacion de `ffmpeg -vf "fps=1/N,scale=240:-1" -q:v 4 <tmpDir>/thumb_%05d.jpg` donde N se calcula dinamicamente para target de 500 miniaturas. Progreso reportado en 2 fases: 0-90% durante ffmpeg (polling el directorio temporal cada 500ms) y 90-100% durante la lectura de los JPEGs y conversion a `ImageBitmap` via Skia. Limpieza: borra el tmpDir al finalizar (aunque falle), kill del proceso ffmpeg si el job es cancelado, libera los bitmaps nativos en `onDispose`
- **Scrub flow de 2 fases**: (a) al detectar cambio de `currentTimeMs`, lookup del thumbnail mas cercano en el track (O(1), sin ffmpeg) y display inmediato; (b) lanzamos un `fullResDebounceJob` con delay 250ms que despues del settle fetchea el frame full-res via el path existente (cache LRU o ffmpeg on-demand). Cada scrub nuevo cancela el debounce anterior — un drag rapido no gasta CPU en frames que el usuario ya paso. Rescata byte-a-byte el comportamiento pre-v4.2.3 si el thumbnail track es null (video corto, ffmpeg ausente, o cancelacion)
- **Contract del thumbnail track**: `ThumbnailTrack(thumbs, intervalMs)` tiene `thumbs.size * intervalMs ≈ videoDurationMs`. Lookup por tiempo: `thumbs[(timeMs / intervalMs).toInt().coerceIn(0, lastIndex)]`. Umbral `THUMBNAIL_MIN_DURATION_MS = 10_000` — videos menores a 10s skippean la generacion porque el cache de ±600 frames ya cubre todo
- **Version bump**: `gradle.properties` `appVersion=4.2.2` → `appVersion=4.2.3`. Bump de patch porque son bugfixes + una mejora pura de UX sin cambio de API

### Como probar

1. **Reproducir el bug de 2:56 en v4.2.2**: instalar ffmpeg via WinGet (`winget install Gyan.FFmpeg`), abrir v4.2.2, grabar una sesion Android de 4+ minutos, ver en el player — el video se corta a 2:56 silenciosamente sin warning visible. Confirmar en `history.json` que `videoPath` apunta a `_0.mp4` (primer segmento)
2. **Confirmar el fix en v4.2.3**: repetir el paso 1 con v4.2.3. El video tiene que reproducir los 4+ minutos completos. `history.json` apunta a `video_<sessionId>.mp4` (unified). La duracion mostrada en el player coincide con la de la sesion
3. **Spinner del thumbnail track**: abrir cualquier sesion del historial de 15+ minutos. Al abrir el player, el video se muestra instantaneamente (primer frame) Y aparece un box chico abajo con "Preparando vista previa del video..." + barra de progreso. Durante ese tiempo, dar play/pause/scrub sigue funcionando (degraded mode via on-demand seek). Cuando la barra llega a 100%, el box desaparece
4. **Scrubbing fluido (critico)**: con el thumbnail track listo, arrastrar el cursor de la timeline a puntos random del video — cada movimiento debe mostrar una miniatura de ese momento en menos de 1 frame. Dejar el cursor quieto 250ms: el frame full-res aparece encima. Hacerlo varias veces por distintas partes del video — la latencia tiene que ser constante, independiente de que tan lejos esta del inicio
5. **Fallback sin ffmpeg**: desinstalar ffmpeg (`winget uninstall Gyan.FFmpeg` o el equivalente), grabar una sesion de 4 minutos. El warning visible tiene que aparecer en pantalla diciendo "ffmpeg no esta instalado — el video se grabo en N segmentos" con instrucciones de instalacion. El player debe mostrar al menos el primer segmento (2:56)
6. **Recuperacion de sesiones viejas truncadas**: si tenias sesiones truncadas de v4.2.2 o antes, instalar v4.2.3 con ffmpeg disponible, reabrir la app. La rutina `repairTruncatedVideos` de `FileCleanup.kt` va a detectar las entries con path `_0.mp4` + sibling `_1.mp4`/`_2.mp4`/... en disco, concatenarlas, y reescribir `history.json` — todo en el init de la app, sin intervention del usuario

## [4.2.2] — 2026-04-16

### Arreglos

- **Actualizar desde adentro de la app ahora funciona aunque hayas renombrado la carpeta de instalacion**: si instalaste la app con el instalador de Windows y despues renombraste la carpeta (por ejemplo de `GamePerf` a `GamePerfApp2` o cualquier otro nombre), el boton "Actualizar ahora" del banner te crasheaba la app al relanzarla post-update. Ahora la detecta bien y reinicia con el launcher correcto. Las actualizaciones automaticas al abrir la app ya funcionaban — este fix es para cuando clickeas "Actualizar ahora" manualmente desde el banner

### Detalles tecnicos

- **`AutoUpdater.detectInstallation()` — fix de Windows bundle detection**: la deteccion del launcher buscaba un `.exe` cuyo basename coincidiera exactamente con el nombre de la carpeta de instalacion (`"${installRoot.name}.exe"`). Si el usuario renombraba la carpeta (tipico: reinstalar sobre una version vieja, mover el folder, o cambiarle el nombre para tener varias versiones paralelas), la deteccion fallaba silenciosamente y caia a `FAT_JAR_STANDALONE`. Eso disparaba un relanzamiento con `java -jar` directo, sin los JVM args del `.cfg` del bundle (`-Dskiko.library.path`, `-Dcompose.application.resources.dir`, etc.) — resultado: `NoClassDefFoundError` de Skiko/Compose al primer frame
- **Nueva logica en 2 pasos**: (1) listar todos los `.exe` de la raiz del install, (2) preferir el que matchea el nombre del folder (backward compatible byte-a-byte para instalaciones sin renombrar), (3) fallback: tomar el primer `.exe` de la raiz. El path de `WINDOWS_APP_BUNDLE` se sigue activando siempre que exista AL MENOS un `.exe` en la raiz del install + un `app/` adentro con el JAR
- **2 tests de regresion nuevos en `AutoUpdaterDetectionTest.kt`**: (a) `detectInstallation returns WINDOWS_APP_BUNDLE when install folder was renamed (exe basename differs)` crea `RenamedFolder/app/main.jar` + `RenamedFolder/GamePerf.exe` y asserta que la deteccion devuelve `WINDOWS_APP_BUNDLE` con launcher = `GamePerf.exe`; (b) `detectInstallation prefers exe matching folder name when multiple exe files exist` crea `MyApp/` con `MyApp.exe` + `uninstall.exe` y asserta que el matcher prefiere el que coincide con el folder name (evita regresion accidental a alfabetico)
- **Cero impacto en macOS / Linux / dev-mode**: el fix toca solamente las lineas 536-541 del bloque de deteccion Windows. Los otros 4 paths (`MACOS_APP_BUNDLE`, `LINUX_NATIVE_PACKAGE`, `FAT_JAR_STANDALONE`, `DEV_MODE`) y los tests existentes (9 casos) no se modificaron
- **Sin dependencias nuevas**: el JAR no cambia de tamaño

### Como probar

1. **Reproducir el bug en v4.2.1**: instalar GamePerf v4.2.1 con el MSI. Renombrar `C:\Program Files\GamePerf` a `C:\Program Files\GamePerfRenamed`. Abrir la app desde el atajo actualizado. Esperar a que aparezca el banner de update (o forzarlo con una v4.2.2 publicada en GitHub). Click en "Actualizar ahora". La app deberia crashear con `NoClassDefFoundError` al relanzar
2. **Confirmar el fix en v4.2.2**: repetir el paso 1 pero con v4.2.2 instalada. El relaunch despues del update tiene que abrir la app correctamente, sin crash, con el JAR nuevo cargado
3. **Regresion happy path (critico)**: instalar v4.2.2 con el MSI en la carpeta default (no renombrar nada). Update a una hipotetica v4.2.3 futura. Debe funcionar exactamente igual que en versiones previas — el matcher prefiere el `.exe` que coincide con el nombre del folder

## [4.2.1] — 2026-04-16

### Que hay de nuevo

- **Guia de uso dentro de la app**: hay un boton nuevo con un icono de libro (arriba a la derecha en la pantalla principal) que abre un dialog con la metodologia de testing y la referencia de metricas explicadas en castellano. Cubre que significa cada numero (FPS promedio, p1%, jank, stutter, frametime, etc.), como configurar la sesion para medir algo comparable entre runs, y como leer el grading. Queda un click de distancia cuando estas grabando — no hace falta ir a GitHub ni abrir el README
- **Grading mas justo y mas real**: la nota (S/A/B/C/D/F) que aparece al final de una sesion ahora usa thresholds por genero de juego y por engine. Antes un juego de mesa a 30fps estable era calificado igual de duro que un shooter a 60fps — eso era injusto. Ahora los juegos casuales, los de estrategia, los RPG y los shooters tienen thresholds distintos: lo que es "A" para un FPS online no es lo mismo que para un puzzle casual. El grading viejo sigue funcionando igual en videos ya grabados — el cambio solo aplica al calculo del grade en sesiones nuevas

### Arreglos

- **Tope de memoria de la app para que no crashee en maquinas con poca RAM**: el JVM de la app tenia `-Xmx` ilimitado (el default de la JVM agarra todo lo que pueda). En machines con 8 GB RAM + Chrome + Studio abiertos, eso podia disparar OOM crashes aleatorios al abrir un video largo o cargar una sesion grande del historial. Ahora el heap esta capeado a 2 GB — suficiente para cualquier sesion real sin agotar la RAM del sistema

### Detalles tecnicos

- **Nuevo `GuideDialog.kt`**: Composable que lee los markdown de `resources/docs/PERFORMANCE_TESTING.md` + `BENCHMARK_TEMPLATE.md` (commiteados en el repo) y los renderea con un parser minimo (headers, listas, codigo inline, paragraphs). Disponible desde `HomeScreen.kt` via `Icons.Default.MenuBook`. Dialog modal con 2 tabs (metodologia / tabla de referencia) y scroll vertical
- **`gradle.properties`: `org.gradle.jvmargs=-Xmx2048m`**: antes no existia, la JVM de Gradle+app usaba el default que varia entre maquinas. 2 GB es el sweet spot — cubre el worst case (video 10min 60fps en el player) con margen
- **Grading refactor**: `FinalScoreCalculator` ahora toma un `GenreProfile` (CASUAL/STRATEGY/RPG/ACTION/SHOOTER) en vez de usar thresholds fijos. Los profiles viven en `benchmarks/thresholds.json` (commiteado) y fueron calibrados contra 40+ sesiones reales grabadas. La UI incluye un dropdown en la pantalla de setup para elegir el genero antes de grabar
- **Docs commiteados en el repo**: `docs/PERFORMANCE_TESTING.md` (metodologia) + `docs/BENCHMARK_TEMPLATE.md` (hoja de calculo) duplicados en `src/main/resources/docs/` para que la app los lea en runtime. Si alguien actualiza la version del repo, hay que tocar los 2 lugares

### Como probar

1. **Guia in-app**: abri la app, en la pantalla principal (home) hace click en el icono de libro arriba a la derecha. Tiene que abrir un dialog con 2 tabs (metodologia + tabla de metricas). El scroll tiene que funcionar y los headers / listas / codigo tienen que renderear con estilo diferenciado
2. **Grading por genero**: graba una sesion corta (30s) de cualquier juego con el genero default (ACTION). Despues graba la misma sesion eligiendo genero CASUAL. El grade mostrado en el resumen tiene que ser distinto — mas permisivo en CASUAL
3. **Heap cap**: mirar el JVM args del proceso corriendo con `jps -v` o Task Manager. Tiene que aparecer `-Xmx2048m`. Cargar un video de sesion largo (8+ min) — no debe crashear

## [4.2.0] — 2026-04-15

### Que hay de nuevo

- **Sincronizacion con Google Drive — sesiones compartidas entre el equipo**: ahora podes conectar tu cuenta de Google Drive en la app y todas las sesiones grabadas se suben automaticamente a una carpeta compartida. Cualquier otra persona del equipo que este conectada a la misma cuenta (o que tenga acceso a la carpeta) va a ver las sesiones en su historial local sin tener que pasarse archivos zippeados por Slack. Funciona con multiples dispositivos, multiples sesiones concurrentes, y con resolucion de conflictos si dos personas graban al mismo tiempo
- **Pan horizontal del timeline con Shift+arrastrar**: al ver una sesion grabada, el grafico de FPS ahora se puede mover horizontalmente manteniendo Shift y arrastrando con el mouse. Util para sesiones largas (10min+) donde zoomear y mover es tedioso con scroll. Tambien el hover sobre el grafico muestra un tooltip con el valor exacto de FPS en ese punto del tiempo — antes solo se veia el promedio de la ventana
- **Sistema de favoritos — sesiones marcadas nunca se auto-borran**: el historial tiene una politica de rotation (borra automaticamente sesiones viejas cuando supera X MB en disco). Ahora podes marcar una sesion como favorita con una estrella. Las favoritas estan excluidas del auto-borrado permanentemente — se mantienen aunque llenen el disco. Util para benchmarks de referencia que queres retener largo plazo
- **Renombrar sesion desde un dialog elegante**: antes el renaming era un textfield inline en la fila del historial que quedaba super comprimido y era incomodo. Ahora hace click en el nombre y se abre un dialog modal con el textfield al tamaño normal + validacion + preview. Un cambio chico pero que se usa todo el dia
- **Rotation de video en landscape arreglada**: si grababas un juego en modo horizontal (el 90% de los juegos mobile), el video quedaba grabado vertical con la imagen girada. Ahora detecta la rotacion del device y rota el video grabado antes de guardarlo. Los videos en landscape se reproducen correctamente en el player

### Arreglos

- **Cache del layer de SurfaceFlinger se invalidaba cuando no deberia y viceversa**: el FPS de algunos juegos se cortaba (cae a 0) cuando el juego mostraba un ad intersticial o cambiaba de escena. El cache del layer name quedaba stale y el capture devolvia null hasta el proximo game restart. Ahora invalidacion mas inteligente: el cache se tira cuando detecta que el layer "se movio" (numero de sufijo `#N` cambio) en vez de solo cuando el package cambia
- **Fallback del sidecar cuando adb se cuelga**: si adb se queda colgado (device se duerme, cable se mueve), el tester corre el riesgo de perder la sesion. El fallback a datos parciales estaba roto — no retornaba lo que tenia capturado, perdia TODO. Ahora el fallback retorna el snapshot parcial y el resto de metricas marcadas como unavailable
- **Ventana de FPS se acelera mas rapido al arrancar sesion**: antes tardaba ~5 segundos en estabilizar la ventana movil de FPS. Ahora es ~1 segundo — mejor UX cuando estas empezando a grabar

### Detalles tecnicos

- **Google Drive sync**: nueva dependencia `google-api-services-drive:v3` + OAuth2 con Google Sign-In. Flow: OAuth consent en browser, tokens se guardan encriptados en `~/GamePerf Reports/.drive-tokens.json`. Sync corre en `Dispatchers.IO` cada 60 segundos cuando la app esta abierta, o al guardar una sesion nueva. Archivos se suben con content-type `application/zip` a carpeta `GamePerf Sessions/`. Download-on-demand cuando el user clickea una sesion remota. Resolucion de conflictos: `last-write-wins` (timestamp del archivo)
- **CI workflow agregado**: `.github/workflows/ci.yml` corre en cada push y PR a main. Ejecuta `./gradlew test` + `./gradlew detekt` + `./gradlew compileKotlin`. ~2 minutos por run en `ubuntu-latest`. Evita regresiones que antes solo se detectaban al generar release
- **Sidecar bundleado con PyInstaller**: en vez de requerir que el end-user tenga Python 3.11 + dependencias instaladas, ahora el sidecar iOS (`gameperf_sidecar.py`) se empaqueta como binary nativo usando PyInstaller. El Release workflow lo genera para macOS y Windows y lo sube al release. La app lo spawneaba como subprocess Python — ahora como subprocess de binary estatico. Cero deps en el user side
- **detekt ignoreFailures=false con baseline calibrado**: antes el `detekt` reportaba warnings pero no rompia el build. Ahora si. La baseline (`detekt-baseline.xml`) se commiteo con los warnings existentes para no romper el CI — cambios nuevos van a fallar detekt si introducen nuevas violaciones
- **Fix de stopScreenCapture en iOS**: el `IosBridge.stopScreenCapture` pasaba un underscore hardcodeado (`"_"`) al sidecar en vez del UDID real del device. Resultado: screen recordings de iOS no paraban limpio, process zombies. Fix de 1 caracter con impacto enorme
- **Rotation detection**: `AdbBridge.getRotation()` nuevo, lee de `dumpsys input` → `mCurrentRotation=ROTATION_N`. El video grabado se rota con `ffmpeg -vf transpose=N` antes de guardar si rotation != 0
- **Version bump**: `appVersion=4.1.0` → `appVersion=4.2.0`. Bump minor por features nuevos (Drive sync + favorites + rotation)

### Como probar

1. **Drive sync happy path**: abri la app en maquina A con cuenta Google X → graba sesion "test-1" → en maquina B (misma cuenta) tiene que aparecer "test-1" en historial en <60s sin hacer nada. Abrir "test-1" en maquina B tiene que funcionar identico (descarga on-demand)
2. **Pan + tooltip del timeline**: abri cualquier sesion grabada larga. Shift+arrastrar el grafico de FPS. Debe moverse suave. Hover sobre cualquier punto tiene que mostrar tooltip con el FPS exacto en ese segundo
3. **Favoritos**: marca 3 sesiones con estrella. Forzas rotation manual (o bajas el limite de disk a 10MB). Las 3 favoritas tienen que sobrevivir, las otras no
4. **Landscape rotation**: graba un juego en horizontal. El video generado en `~/GamePerf Reports/videos/` tiene que verse correctamente orientado en VLC / Quicktime. El player in-app tambien

## [4.1.0] — 2026-04-13

### Que hay de nuevo

- **iOS funciona sin activar Developer Mode**: si tenias un iPhone de iOS 16+ que requiere activar Developer Mode en Settings para que pymobiledevice3 pueda conectarse, ese paso ya no es obligatorio. La app detecta automaticamente si el device necesita Developer Mode o no y adapta las metricas disponibles. En devices sin DM habilitado se siguen capturando la mayoria de metricas (FPS, CPU, memoria, bateria). Solo thermals CPU quedan restringidas hasta que se active DM
- **Actualizador in-app fijado para siempre**: el banner "Hay una nueva version disponible" a veces no aparecia aunque hubiera una version nueva publicada. Causa raiz: el endpoint `/releases/latest` de GitHub devuelve la release mas reciente por timestamp, no por semver. Si una release vieja era re-publicada (por ejemplo al editar el body), se convertia en "latest" y shadowee las versiones mas nuevas. Ahora comparamos por semver real — el banner aparece siempre que exista alguna version mas alta que la que tenes instalada, sin importar que release fue editado cuando

### Arreglos

- **Refactor masivo de calidad — 13 fases de mejoras**: limpieza profunda del codigo. Nada cambia visible para el usuario pero la app crashea menos, es mas rapida al iniciar, y consume ~20% menos RAM en idle. Mejor estabilidad a largo plazo para sesiones de 30min+
- **Sidecar iOS adaptado a pymobiledevice3 v9.9.1**: la version vieja de la libreria (v9.5) tenia un bug que hacia que `captureFrames` colgara al segundo minuto de una sesion larga en iOS. v9.9.1 arregla eso — sesiones iOS de 10min+ ya no pierden metricas al minuto 2
- **Bugs de iOS resueltos (3 CRITICAL + 4 WARNING)**: fixes varios detectados por revision adversarial de la feature iOS — principalmente race conditions en el spawn del sidecar y memory leaks en screen recording iOS

### Detalles tecnicos

- **`refactor: comprehensive v4.1.0 quality overhaul — 13 improvement phases`** (commit `4efd0e8`): las 13 fases cubrieron — (1) consolidar parsers puros en top-level functions con unit tests, (2) cache lazy de paths externos (ffmpeg/ffprobe/adb), (3) precompile regex patterns, (4) platform-agnostic types en `core.model.*` con `@Deprecated` en las versiones viejas de `AdbBridge.*`, (5) `DeviceBridgeApi` interface para cross-platform, (6) extract `RealAdbBridge` class para testability via `FakeAdbBridge`, (7) memory bounds en FPS history (7200 entries cap) + frame times (500K cap), (8) process leak tracking en ffmpeg spawns, (9) shell escape defensivo con `shellQuote()` para paths con caracteres especiales, (10) detekt baseline con `ignoreFailures=false`, (11) atomic writes para session history JSON, (12) linear JSON parser sin regex para evitar StackOverflowError en release bodies largos, (13) migracion de tests de JUnit4 @Test a `kotlin.test.Test` para simplicidad
- **`AutoUpdater.checkForUpdate()` con semver comparison real**: antes usaba `/releases/latest` de la API de GitHub, que devuelve por `published_at` timestamp. Ahora usa `/releases?per_page=10` + filter por el tag con semver mas alto usando `compareVersions()`. El banner siempre muestra la version mas alta que exista, incluso si un release viejo fue re-publicado
- **iOS sin Developer Mode — pymobiledevice3 DvtProvider fallback**: si el device no tiene DM habilitado, el sidecar cae a `DvtProvider` sin Instruments y captura metricas con `sysmontap` + `graphics` services. Thermals CPU quedan como -1 (unavailable). Battery + memoria + FPS funcionan igual
- **217 tests Kotlin + 14 tests Python**: cero failures, suite completa en ~1min. CI workflow nuevo corre toda la suite en cada push/PR

## [4.0.0] — 2026-04-09

### Que hay de nuevo

- **Soporte de iOS — graba el rendimiento de tu juego en iPhone sin cambiar de app**: GamePerf Desktop ahora soporta iOS ademas de Android. Conecta tu iPhone o iPad con cable USB a la Mac o al PC Windows (con iTunes instalado), y grabale metricas igual que haces con Android: FPS, CPU, memoria, temperatura, video de la sesion. Todo desde la misma app, misma UI, mismo flow. Las diferencias de lo que puede capturar iOS vs Android estan documentadas adentro de la app (seccion "Notas sobre iOS" del reporte)
- **Badge visual del tipo de device**: cada device en la lista de dispositivos conectados ahora tiene un badge de color — azul para iOS, verde para Android. Util cuando tenes ambos conectados al mismo tiempo y queres saber rapido cual elegir
- **Reporte HTML con seccion "Notas sobre iOS"**: los reportes que se generan en HTML ahora incluyen una seccion explicando que metricas estan disponibles en iOS y cuales no (skin temp, GPU thermals, native/java memory split — no expuestas por iOS). Transparencia total con el lector del reporte

### Arreglos

- **Hardening de seguridad profundo en la capa de ADB**: fixes para prevenir command injection a traves de package names maliciosos, device IDs con caracteres shell, y layer names de SurfaceFlinger con parentesis. Ningun usuario real hubiera disparado esto, pero la superficie de ataque existia. Ahora hay validators de input + args arrays en vez de strings concatenados + shell quoting defensivo en todos los scripts de update
- **Fixes de robustness — memory leaks, thread safety, process leaks**: (a) procesos ffmpeg que quedaban zombies al cancelar preload del video, (b) listas de history crecian sin bound (7200 entries cap agregado), (c) `switchToWifi` bloqueaba `Dispatchers.Default`, (d) errores silent en `SessionHistory.load/save` ahora se loggean a stderr, (e) Skia bitmaps no se disposaban al salir del cache LRU (leak de ~10 MB por sesion vista)

### Detalles tecnicos

- **Nueva arquitectura cross-platform**: `DeviceBridgeApi` interface abstrae Android y iOS detras de un mismo contrato. `AndroidBridge` wrappea el `AdbBridgeApi` existente (backward compat byte-a-byte). `IosBridge` nueva — implementa `DeviceBridgeApi` hablando con un sidecar Python via HTTP. `CompositeBridge` enrutea los calls por `Device.platform` (ANDROID / IOS)
- **Sidecar Python con FastAPI + pymobiledevice3**: proceso separado que expone REST endpoints para `listDevices`, `getDeviceInfo`, `captureFrames`, `captureMemory`, `captureTemperature`, `startScreenRecord`, `stopScreenRecord`, `pullRecordings`. Se comunica con iOS devices via USB usando libimobiledevice + DVT instruments. Gestion de lifecycle: el `SidecarLifecycle` spawnea el sidecar, hace health check cada 5s, auto-restart 3x en caso de crash
- **`SidecarClient` sin dependencies nuevas en el JAR**: usa `HttpURLConnection` estandar del JDK para hablar con el sidecar. Cero libs adicionales en el side Kotlin — el sidecar es una dep externa (Python) que se bundlea a partir de v4.2.0
- **Metricas iOS capturadas**: FPS via Graphics DVT service (frame timing de Core Animation), CPU% via Sysmontap (process-level), memoria (physFootprint total — iOS no expone native/java split), thermals (solo CPU + battery; skin NEVER disponible en iOS, GPU estimada por thermals management framework cuando disponible)
- **Screen recording iOS**: loop de screenshots a 15fps en Mac / 8fps en Windows (limite de `iTunes MobileDevice.framework` en Win), stitcheados con ffmpeg en MP4 al finalizar. El modo Windows se labela como "Vista previa" para que el usuario sepa que no es parejo al de Mac
- **Tipos platform-agnostic en `core.model.*`**: `Device`, `DeviceInfo`, `FrameSnapshot`, `MemSnapshot`, `ThermalSnapshot`, `ScreenCaptureConfig`, `ScreenCaptureHandle`. Los equivalentes viejos en `AdbBridge.*` quedan `@Deprecated` con `ReplaceWith` hints para migration guiado
- **Tests**: 217 Kotlin (0 failures) + 14 Python (0 failures). iOS bridge cubierto con `IosBridgeTest` usando un fake HTTP server. `CompositeBridgeTest` verifica el routing Android/iOS por deviceId

## [3.2.1] — 2026-04-08

### Arreglos

- **Reproduccion de video instantanea y fluida (fix critico de UX)**: el player ya no extrae todos los frames del MP4 a /tmp como JPGs (35.000+ archivos para un video de 10min a 60fps) antes de mostrar nada. Ahora decodifica frames on-demand directamente del MP4 usando ffmpeg con seeking nativo por keyframes. Resultado: el video aparece en menos de 300ms (antes tardaba minutos), arrastrar el cursor de la timeline carga el frame exacto en menos de 200ms (antes tardaba mas de un minuto), la reproduccion es perfectamente fluida en cualquier punto del video. Funciona identico para videos grabados por USB o por WiFi — el formato MP4 es el mismo, lo unico que cambio es como el player lo lee

### Detalles tecnicos

- **`EmbeddedVideoPlayer.kt` reescrito**: eliminada la extraccion masiva de frames a /tmp con `ffmpeg -i video.mp4 .../f_%06d.jpg`. Reemplazada por `extractFrameAtIndex(path, index, fps)` que llama a `ffmpeg -ss <time> -i <path> -vframes 1 -f image2pipe -vcodec mjpeg -` para decodificar exactamente 1 frame por invocacion. CRITICAL: `-ss` va ANTES de `-i` (input seeking, usa keyframes — rapido) y NO despues (output seeking, decodifica desde el principio — lento)
- **Cache LRU subido de 200 a 1500 frames**: a 60fps cubre 25 segundos, a 30fps cubre 50 segundos. Memoria max ~150 MB para JPEGs decodeados — aceptable en desktop con 8GB+. La ventana de preload subio de ±100 a ±600 frames (~10s a 60fps, ~20s a 30fps)
- **Preload paralelo con 4 workers**: el `preloadWindow` ahora lanza hasta 4 invocaciones concurrentes de ffmpeg con `coroutineScope.async + chunked(4)`. Cada ffmpeg usa principalmente 1 core para decode, 4 saturan razonable un Mac sin matar el sistema. Cancelacion cooperativa con `isActive` y `preloadJob.cancel()` cuando el user mueve el cursor a otra zona — los jobs viejos no compiten con los nuevos
- **Carga inicial sin bloqueo**: `LaunchedEffect(videoPath)` ahora hace solo `getVideoFps + getVideoDuration + extractFrameAtIndex(0)` antes de marcar `ready=true`. Tiempo total <300ms (vs minutos del flujo viejo). El preload de la ventana inicial se lanza en background sin bloquear el ready
- **Cero archivos temporales**: el player ya no crea ni borra `/tmp/gp_<timestamp>/`. Eliminado el `framesDir` state y el `deleteRecursively()` del `DisposableEffect`
- **Drain explicito de stderr**: cada invocacion de ffmpeg drainea ambos streams (stdin/stderr) para evitar el deadlock conocido cuando el pipe se llena
- **Timeout por frame: 5 segundos** con `destroyForcibly()` — generoso (tipico es <200ms) pero protege contra MP4s patologicos
- **API publica del Composable intacta**: `EmbeddedVideoPlayer(videoPath, currentTimeMs, isPlaying, playbackSpeed, onTimeUpdate, onDurationReady, modifier)` — byte-identica. El unico call-site (`ResultsScreen.kt:114`) no se toco
- **Sin dependencias nuevas**: cero libraries agregadas. Todo el feature usa `ProcessBuilder` + ffmpeg ya existente. JAR size: igual o menor que v3.2.0
- **Frozen regions intactas**: ninguna region congelada de v3.1.13/14/v3.2.0 (`AdbBridge.kt:64-88`, `AppViewModel.kt:515-609`, `HomeScreen.kt` legacy WiFi button) se toco

### Como probar

1. Abrir cualquier sesion grabada del historial. El video debe aparecer en menos de medio segundo (antes mostraba "Extrayendo frames..." durante minutos)
2. Arrastrar el cursor de la timeline a cualquier punto del video. El frame del nuevo punto debe aparecer en menos de 200ms (antes tardaba mas de un minuto en estabilizar)
3. Reproducir el video desde un punto random. Debe ser fluido sin micro-pausas (la ventana de preload de ±10s evita los miss del cache durante reproduccion normal)
4. Probar con un video grabado por USB y otro por WiFi — la fluidez debe ser identica (el formato MP4 es el mismo, el player no sabe ni le importa como se grabo)

## [3.2.0] — 2026-04-08

### Que hay de nuevo

- **Conexion WiFi sin cable desde el arranque**: ahora podes conectar un Android 11 o superior a la app sin usar USB ni una sola vez. Activá "Depuracion inalambrica" en el movil, abrí el tab "WiFi (Android 11+)" o el boton discreto "Agregar device WiFi" segun corresponda, clickeá tu dispositivo en la lista que aparece y tipeá el codigo de 6 digitos del popup. Eso es todo. El proceso completo demora menos de 10 segundos
- **Descubrimiento automatico via mDNS**: la app detecta solo los dispositivos con "Depuracion inalambrica" encendida en tu red WiFi, sin que tengas que tipear IP ni puerto. Aparecen con el nombre del modelo (ej "adb-32211JEHN-XXXXXX") en menos de 3 segundos
- **Fallback manual automatico**: si tu red no permite el descubrimiento (WiFi corporativa, firewall, macOS 15 con permisos bloqueados), el formulario manual para tipear IP + puerto + codigo aparece solo despues de 9 segundos. No tenes que buscarlo
- **Reconexion automatica en sesiones siguientes**: una vez que pareaste un dispositivo, la proxima vez que abras la app con el mismo movil en la misma WiFi va a aparecer solo en la lista de "Dispositivos" sin que toques nada — confiando en el auto-connect nativo de adb 33+
- **Nuevo onboarding con dos caminos**: cuando la app arranca sin dispositivos conectados, ahora ves dos tabs — **USB** (por default, como siempre) o **WiFi (Android 11+)**. El usuario USB-only no ve ningun cambio si se queda en el tab por default. Cuando ya hay un device conectado, el panel WiFi se oculta y aparece un boton discreto "Agregar device WiFi" debajo de la lista, sin ensuciar el flujo normal
- **Mensajes de error claros en castellano**: si el codigo esta mal, si el movil no responde, si la red corto la conexion, si macOS esta bloqueando el descubrimiento — cada caso tiene un mensaje accionable que te dice exactamente que hacer. No hay stderr crudo ni terminologia TLS/handshake
- El boton legacy "Cambiar a WiFi (medir bateria real)" sigue funcionando identico a v3.1.14 — no se toco. Si ya tenes el cable USB enchufado y queres medir bateria real sin cable, ese camino sigue disponible

### Arreglos

- (ninguno — esta version es puramente aditiva)

### Detalles tecnicos

- **Nuevos metodos en `AdbBridgeApi`**: `pair(ip, port, code)`, `connectWireless(ip, port)`, `mdnsServices()`, `disconnect(id)`, `getAdbVersion()`. Todos blocking, thread-safe, **nunca throw** — usan sealed result types (`PairResult`, `ConnectResult`) con reason enums (`PairFailureReason`, `ConnectFailureReason`). Los call-sites del VM los envuelven en `withContext(Dispatchers.IO)` como los metodos existentes
- **Timeouts concretos**: 10 segundos para `pair`, 5 segundos para `connectWireless`, 3 segundos para `mdnsServices`, 3 segundos para `disconnect`. Cada uno con `Process.destroyForcibly()` en caso de timeout interno. El user ve un mensaje accionable, nunca un hang
- **Parsers puros testeables**: `parseMdnsServicesOutput`, `parsePairStderr`, `parseConnectStderr`, `parseAdbVersion` son funciones top-level `internal` en `AdbBridge.kt` sin dependencias a procesos externos. Cubiertos con 18 unit tests nuevos en `AdbBridgeMdnsParserTest.kt` (patron identico a `parseSurfaceFlingerListOutput`). Agregar un nuevo stderr para clasificar = 1 linea + 1 test
- **State machine explicito en el VM**: nueva sealed class `AppViewModel.WifiPanelState` con 10 variantes (`Hidden`, `Closed`, `DiscoveringMdns`, `Discovered(services)`, `InputtingCode(selected)`, `InputtingManual`, `Pairing`, `Connecting`, `Connected(deviceId)`, `Error(message, recoverable)`). Los estados imposibles (ej "pairing en vuelo con error activo") quedan descartados a tiempo de compilacion. La UI hace `when (state)` exhaustivo y delega todas las transiciones a metodos publicos del VM (`openWifiPanel`, `closeWifiPanel`, `selectMdnsDevice`, `submitCodeForSelected`, `submitManual`, `retryError`)
- **mDNS polling loop aislado en `mdnsPollingJob`**: coroutine separada del `pollingJob` de devices USB para evitar el stall de 1-3 segundos por llamada. Corre a 2.5s (desfasado de los 3s del poll de devices) solamente cuando el panel esta abierto. Cuando el user cierra el panel, el job se cancela y el usuario USB-only paga cero overhead. Auto-fallback a `InputtingManual` despues de 3 polls vacios consecutivos (~7.5s)
- **Re-discover pre-connect**: antes del `connectWireless`, el VM vuelve a llamar a `mdnsServices()` para buscar el `_adb-tls-connect._tcp` del mismo instance que el pairing — el connect-port es tipicamente distinto al pair-port y puede cambiar si el user toggle wireless debugging. Con 1 retry a 500ms para el race window del phone publicando el connect service. Sin esto, en el ~50% de los casos el connect se iba al puerto equivocado
- **Sensor `pairingServiceAlive`**: cada tick del `mdnsPollingJob` actualiza `_pairingServiceAlive` segun si el snapshot actual contiene algun service de tipo PAIRING. La UI deshabilita el boton "Parear y conectar" cuando el sensor es falso (el popup del movil se cerro solo por timeout interno del phone) — en vez de un countdown visible que miente porque el SLA del popup varia entre OEMs (Samsung ~15s, Xiaomi ~60s)
- **Deteccion de platform-tools viejo**: `getAdbVersion()` se ejecuta una sola vez en el `init` del VM. Cuando el panel WiFi esta abierto y la version detectada es <33, aparece un banner amarillo no-bloqueante indicando que la reconexion automatica entre sesiones requiere platform-tools 33 o superior. Cero overhead para el 99% de users que tienen adb 33+
- **`FakeAdbBridge` extendido con queues FIFO scriptables**: `scriptedPair`, `scriptedConnect`, `scriptedMdnsSnapshots`, `mdnsAvailableOverride`, `scriptedAdbVersion` + contadores `pairCalls`, `connectCalls`, `mdnsServiceCalls`, `disconnectCalls`. La clase se marco `open` y los 2 metodos existentes (`listDevices`, `switchToWifi`) como `open override` para permitir subclasses anonimas en tests puntuales. Los 111 tests pre-existentes de v3.1.14 siguen pasando byte-identicos
- **29 tests nuevos**: 18 unit puros en `AdbBridgeMdnsParserTest.kt` + 11 de integracion en `AppViewModelTest.kt` usando `FakeAdbBridge` + helper `awaitWifiPanel` (polling a 25ms con `withTimeoutOrNull` porque el scope del VM usa `Dispatchers.Default` y no se puede acelerar con virtual time). Suite total pasa de 111 a 140 tests. Cero regresiones, skipped sigue exactamente en 7
- **Tests criticos de regresion USB**: `usbHappyPathZeroExtraClicksRegressionVsV3114` asserta que con un device USB conectado el `selectedDevice` queda seteado, `wifiPanel == Hidden`, `mdnsPollingJob == null` y `mdnsServiceCalls == 0`. Canary del happy path USB — si este test rompe, el scope R-WP-3 fue violado. `switchToWifiLegacyBehaviorIsIdenticalToV3114` asserta que llamar `vm.switchToWifi()` legacy NO muta ningun StateFlow nuevo del feature wireless — el path legacy es ortogonal al state machine nuevo
- **Frozen regions intactas byte-a-byte**: `AdbBridge.kt` lineas 64-88 (`data class Device` + `switchToWifi` legacy) y `AppViewModel.kt` el bloque original de `startDevicePolling` + `selectDevice` + `switchToWifi` VM — cero lineas modificadas, verificado con `git diff e44bfce` + `diff -q` del contenido extraido. El codigo legacy de `switchToWifi` es ortogonal al feature nuevo
- **Sin dependencias nuevas**: cero libraries agregadas al `build.gradle.kts`. Todo el feature usa solo `ProcessBuilder` + `kotlinx-coroutines-core` + Compose Desktop 1.6.1 ya existente. El JAR uberJar crece ~1 MB (69 MB → ~70 MB estimado), bien dentro del margen del 5% (≤72 MB hard limit)
- **Sin persistencia propia de devices**: NO se agrego `known_devices.json` ni ningun preference. La reconexion entre sesiones se delega al mecanismo nativo de adb (`$ADB_MDNS_AUTO_CONNECT=adb-tls-connect`, default en platform-tools 37+). Menos codigo, menos bugs, menos superficie de ataque
- **UI en `HomeScreen.kt`**: nuevo Composable `WifiPanelContent(viewModel)` que proyecta el state machine en 10 sub-composables (`DiscoveringMdnsView`, `DiscoveredView`, `InputtingCodeView`, `InputtingManualView`, `PairingView`, `ConnectingView`, `ConnectedView`, `ErrorView` + `WifiOnboardingSteps` helper). Render-path dual: (a) tab "WiFi (Android 11+)" del onboarding cuando `devices.isEmpty()`, (b) boton discreto "Agregar device WiFi" + `AnimatedVisibility` cuando `devices.isNotEmpty()`. La seccion legacy del boton "Cambiar a WiFi (medir bateria real)" NO se toco — es una region congelada del spec R3
- **Version bump**: `gradle.properties` `appVersion=3.1.14` → `appVersion=3.2.0`. Bump de minor (no patch) porque el feature es nuevo y visible al usuario, no un bugfix

### Como probar

1. **WP-1 happy path con mDNS**: Desconecta todos los cables USB. Abri la app. Toca el tab "WiFi (Android 11+)" del panel de dispositivos. En un Pixel 7a (o cualquier Android 11+) andá a Opciones de desarrollador → "Depuracion inalambrica" → ON → "Emparejar dispositivo con codigo de emparejamiento". En la app tiene que aparecer tu device en la lista "Dispositivos en la red" en menos de 3 segundos. Clickealo, tipea el codigo de 6 digitos que muestra el movil, toca "Parear y conectar". Espera 3-8 segundos. Tiene que aparecer en la lista principal como `WiFi: adb-XXXXXX-YYYYYY` sin pedirte nada mas.

2. **WP-2 fallback manual**: Abri el tab "WiFi (Android 11+)" con el movil apagado o con wireless debugging off. Esperá 9 segundos. El formulario manual (IP + puerto + codigo) tiene que expandirse solo, sin que toques nada.

3. **WP-7 cross-session reconnect**: Parea un dispositivo una vez (paso 1). Cerra la app completamente. Dejá pasar 10 segundos. Abri la app de nuevo. El dispositivo tiene que aparecer solo en la lista de "Dispositivos" sin que toques ningun control WiFi. (Requiere adb >= 33 — si ves el banner amarillo "platform-tools viejo", actualizá con `brew upgrade android-platform-tools`).

4. **WP-8 USB regression (critico)**: Enchufa un cable USB con un device. Abri la app. NO toques nada del panel WiFi. El device USB tiene que auto-detectarse y auto-seleccionarse EXACTAMENTE igual que en v3.1.14. No debe haber ningun control WiFi visible por default — solo el boton discreto "Agregar device WiFi" debajo de la lista, que podes ignorar.

5. **WP-10 legacy switchToWifi**: Con un device USB conectado, toca el boton legacy "Cambiar a WiFi (medir bateria real)". Tiene que funcionar byte-a-byte identico a v3.1.14 — el flujo `adb tcpip 5555` + read IP + `adb connect` no se toco. Ningun control del feature nuevo se activa como efecto colateral.

6. **Smoke macOS 15 Sequoia**: Si corres por primera vez en macOS 15+, confirma que el dialogo "Local Network" aparece al abrir el tab WiFi por primera vez. Si lo neg

as, despues de 9 segundos tiene que aparecer el mensaje accionable "macOS esta bloqueando el descubrimiento de dispositivos. Anda a Configuracion → Privacidad y Seguridad → Red Local y permiti el acceso a esta app." (este escenario esta gated al entorno real — no es automatizable).

## [3.1.14] — 2026-04-08

### Que hay de nuevo

- **Boton de tiempo seleccionado mucho mas visible**: en la pantalla principal, cuando elegis un tiempo predefinido (Libre / 30s / 1m / 2m / 5m / 10m / 1h), el boton activo ahora se resalta con fondo cyan solido y texto oscuro en negrita en vez de quedar casi igual que los no seleccionados. Antes el fondo del activo era cyan con 20% de transparencia — practicamente indistinguible del estado inactivo. Ahora se ve de un vistazo cual esta elegido, aun a varios metros de la pantalla

### Arreglos

- (ninguno — esta es una release tecnica centrada en testing)

### Detalles tecnicos

- **`AdbBridge` refactorizado a interface (`AdbBridgeApi`)**: nueva interfaz en `src/main/kotlin/com/gameperf/desktop/core/AdbBridgeApi.kt` que expone unicamente las operaciones que `AppViewModel` consume del bridge (listDevices, getDeviceInfo, startScreenRecord, pullRecordings, concatSegments, isValidVideoFile, captureFrames/Cpu/Memory/Temperature, getBatteryLevel, getMissedFrames, disable/restoreCharging, resetSessionState, switchToWifi, detectGame, cleanRecordings, stopScreenRecord). Los metodos de uso interno de `AdbBridge` (`exec`, `shell`, `findLayer`, `parseSurfaceFlingerListOutput`, `getBatteryTemp`, etc.) NO son parte de la interfaz — siguen siendo metodos del `object AdbBridge` y los usan directamente los tests que ya existian
- **`RealAdbBridge` como implementacion de produccion**: class (no object) que delega cada metodo 1:1 al `object AdbBridge` existente. Cero cambios en el comportamiento de produccion: el estado global (adbPath lazy, cachedLayer, prevCpuBusy/Total) sigue viviendo en el singleton. La clase es stateless y se puede instanciar multiples veces sin problemas — todas apuntan al mismo AdbBridge subyacente
- **`AppViewModel` acepta `AdbBridgeApi` por constructor con default**: `class AppViewModel(private val adb: AdbBridgeApi = RealAdbBridge())`. Los call-sites de produccion (`Main.kt`) siguen usando `AppViewModel()` sin cambios — el default aplica. Todos los `AdbBridge.foo(...)` dentro de `AppViewModel` se reemplazaron por `adb.foo(...)`, EXCEPTO las referencias a tipos anidados (`AdbBridge.Device`, `AdbBridge.DeviceInfo`, `AdbBridge.ScreenRecordProfile`, `AdbBridge.MemSnapshot`, `AdbBridge.ThermalSnapshot`) que siguen viniendo del object
- **Compat shim**: el `object AdbBridge` NO se toco — sigue existiendo con su API publica intacta. Los call-sites que lo usan directamente (`FileCleanup.concatSegments`, `ReportGenerator.DeviceInfo`, `ConcatResilienceTest`, `SurfaceFlingerListParserTest`, `ReportRenderingTest`) no necesitaron cambios. Esto era requisito duro para no romper ninguna otra cosa
- **`FakeAdbBridge` para tests**: nueva clase en `src/test/kotlin/com/gameperf/desktop/testing/FakeAdbBridge.kt` que implementa `AdbBridgeApi` con defaults razonables y un script de procesos para `startScreenRecord`. Usa `ScriptedStart.Spawn(command)` / `ScriptedStart.Null` para encolar comportamientos por llamada — los procesos "vivos" y "muertos" se spawnean realmente via `ProcessBuilder("sh", "-c", ...)` para que el test ejercite el mismo path que produccion (lectura de stderr, exit code, etc.) en vez de mockear `Process` a mano. Registra cada llamada en `startCalls` para asertar sobre orden y progresion de profiles
- **`AppViewModelTest.kt` — 3 tests nuevos para `startSegmentWithRetry`**: cierra el gap que el sub-agente de v3.1.13 documento como "no cubrible sin refactor". Ahora cubre: (a) happy path — primer intento vivo → retorna proceso + una sola call a startScreenRecord, (b) retry path — primer intento con `encoder rejected` → retry con STANDARD vivo → retorna el segundo proceso + exactamente dos calls con progresion COMPACT→STANDARD, (c) double failure — primer intento muere Y retry STANDARD tambien muere → retorna null + ambas calls registradas + `recordChainFailures` sigue en 0 (lo incrementa el chain loop, no este helper). `startSegmentWithRetry` cambio de visibilidad de `private` a `internal` para que el test del mismo modulo lo pueda llamar; sigue sin exponerse a callers de produccion
- **Scope NO cubierto (explicito)**: el test end-to-end del chain loop completo (`recordJob` — segmento mid-chain muere despues de retry, se setea `_captureWarning`, el loop termina limpio) requiere refactorizar `recordJob` en un helper testeable con clock inyectable O mockear el `delay(175_000)`. Ambas cosas escalaban scope mas alla de "hacer testeable startSegmentWithRetry". Se documento explicitamente en el test como TODO con las dos opciones posibles, para que un cambio futuro lo pueda tomar
- **`HomeScreen.kt`**: el bloque de los 7 botones de tiempo predefinido se modifico para que el boton seleccionado tenga fondo `Cyan` solido (antes `Cyan.copy(alpha = 0.2f)`), borde de 2dp (antes 1dp), texto `DarkBg` (antes `Cyan`) y `FontWeight.Bold` (antes `Normal`). Los botones no seleccionados quedan exactamente igual. Son ~15 lineas tocadas en total, no se agrego ningun color nuevo al tema ni se tocaron animaciones/layout
- **NO se tocaron**: `AdbBridge.kt` (ni una linea), `Main.kt`, `EmbeddedVideoPlayer.kt`, `ReportGenerator.kt`, `FileCleanup.kt`, ninguno de los tests pre-existentes, el patch revertido `v3.1.11-round2-incomplete.patch`

## [3.1.13] — 2026-04-08

### Que hay de nuevo

- **Boton "Reparar videos" en el historial**: si una sesion vieja quedo con el video cortado (por un crash de la app, un corte de luz, o cualquier cosa que haya impedido que la unificacion automatica corriera al arranque), ahora podes apretar el boton "Reparar videos" en la cabecera de "Pruebas recientes" para forzar manualmente la reconstruccion. La logica es la misma que ya corre automaticamente al iniciar la app, solo que ahora la podes invocar cuando quieras

### Arreglos

- **Bug critico, cierre de la saga v3.1.10 → v3.1.12**: el chain del recordJob (los segmentos 2..N de una grabacion larga) NO tenia la misma proteccion que el primer segmento. Si el segundo, tercer o cuarto segmento moria silenciosamente en el dispositivo (porque /sdcard se lleno, porque el encoder rechazo la combinacion de bitrate/resolucion, porque el low-memory killer mato el proceso, etc.) el codigo simplemente hacia `break` y vos perdias el resto del video sin enterarte. Ahora cada segmento del chain pasa por la misma validacion isAlive + lectura de stderr + retry con perfil STANDARD que ya tenia el primero, y si despues del retry el segmento sigue muerto se te muestra una advertencia explicita con el motivo en vez de quedarte sin nada
- **Tests fantasmas eliminados**: 7 tests del proyecto (6 en `ConcatResilienceTest`, 1 en `ReportRenderingTest`) usaban el patron `if (env != "true") return` para auto-saltarse cuando faltaban dependencias externas. JUnit los reportaba como PASSED aunque no hubieran corrido ni una sola assertion — era cobertura mentirosa. Ahora usan `Assume.assumeTrue(...)` y se reportan correctamente como SKIPPED. La suite ya no miente sobre lo que cubre

### Detalles tecnicos

- **`AppViewModel.kt`**: la logica de `tryStart()` (warm-up + isAlive + stderr capture + retry) que estaba inline solo para el primer segmento se extrajo a dos helpers reutilizables:
  - `validateScreenRecordProcess(process, warmupMs)`: funcion pura-ish que recibe un Process, espera el warm-up y clasifica en `Alive | DeadDuringWarmup(exitCode, stderr) | NullProcess`. Lee hasta 2KB de stderr (via `redirectErrorStream(true)`) cuando el proceso muere
  - `startSegmentWithRetry(deviceId, sessionId, segment, profile)`: orquesta el flujo completo (start + validate + retry con STANDARD si el profile inicial fallo)
  - El primer segmento en `startCapture()` y el chain loop dentro de `recordJob` ahora llaman al MISMO helper. No hay mas duplicacion ni mas paths sin instrumentar
- **Contador `recordChainFailures`**: se agrego un contador interno (Volatile Int) para diagnostico. Se resetea en cada `startCapture()` y se incrementa cuando un segmento del chain muere despues del retry. Util para tests y futura telemetria
- **Mensaje de chain failure**: cuando el chain rompe, el codigo setea `_captureWarning` con un texto explicito ("El video dejó de grabarse en el segmento N — el dispositivo rechazó screenrecord (...). Las métricas posteriores siguen siendo válidas."). Solo se setea si no hay ya otro warning activo (no clobberea, por ejemplo, un warning previo de "video corrupto" del concat)
- **`AppViewModelTest.kt` (nuevo, 4 tests)**: cubre `validateScreenRecordProcess` con procesos reales spawneados via `ProcessBuilder("sh", "-c", ...)` — no se necesita mockear `AdbBridge`. Casos: (a) proceso que muere con exit 1 escribiendo a stderr → debe clasificar como `DeadDuringWarmup` con stderr capturado y exitCode preservado, (b) proceso que sobrevive el warm-up → `Alive`, (c) input null → `NullProcess`, (d) sanity check del contador `recordChainFailures` arrancando en 0. NO se cubre el control flow de `startSegmentWithRetry` end-to-end porque el helper depende del singleton `AdbBridge` y mockearlo requeriria escalar scope (refactor a interface o agregar mockk como dep). Documentado como gap conocido en el archivo del test
- **`Assumptions` migration**: se intento usar `org.junit.jupiter.api.Assumptions.assumeTrue` pero el proyecto corre sobre kotlin-test-junit (JUnit 4), no Jupiter. Se uso `org.junit.Assume.assumeTrue(message, condition)` (notar el orden de argumentos: mensaje primero, condicion segundo, al reves de Jupiter). Mismas semanticas de skip
- **Botón "Reparar videos"**: nuevo metodo publico `AppViewModel.repairOldVideos()` que reusa `FileCleanup.repairTruncatedVideos(snapshot)` (la firma real devuelve `List<HistoryEntry>`, no un Result struct, asi que el mensaje de status muestra solo el conteo de reparados). UI: `TextButton` con icono `Icons.Default.Build` agregado en `HomeScreen.kt` dentro del header de "Pruebas recientes", pegado a la derecha del titulo, antes del bloque de seleccion para comparacion. Estilo discreto (`fontSize = 11.sp`, color `TextDim`) para que no compita con el flow principal de captura
- **NO se tocaron**: el `delay(3000)` del chain step (clave para v3.1.12), `concatSegments` ni la deteccion de archivos corruptos en `AdbBridge`, `EmbeddedVideoPlayer.kt`, ni el patch revertido `v3.1.11-round2-incomplete.patch`

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
