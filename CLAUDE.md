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

**Fix definitivo (implementado en v4.2.10):**

En `AutoUpdater.kt::checkForUpdate()`, después de obtener la lista de tags ordenada por semver descendente, iterar hasta encontrar una release que tenga un JAR asset matching la plataforma del usuario. Si la más alta todavía está compilando, caer a la siguiente más alta. Si ninguna matchea, retornar `null` (sin banner) — es mejor "no hay update" que "update que no funciona".

Código clave (pseudocódigo):

```kotlin
for (tag in tagsDescending) {
    if (compareVersions(tag, AppVersion.NAME) <= 0) break // ya estamos al día
    val releaseJson = fetchRelease(tag)
    val jarUrl = extractJarAssetUrl(releaseJson)
    if (jarUrl != null) return ReleaseInfo(tag, ..., jarUrl, ...)
    // else: todavía compilando, probar con la siguiente
}
return null
```

**Mejoras complementarias posibles (no implementadas, pero documentadas):**

- Marcar la release como `draft: true` al crearla con `gh release create --draft`, y que el workflow Release haga `gh release edit --draft=false` al final tras subir los assets. Eso elimina el gap temporal a nivel GitHub.
- Invertir la responsabilidad del workflow: que sea `release.yml` el que cree la release con `softprops/action-gh-release` en lugar de que el workflow dispare tras un release ya creado. Así la release solo existe cuando tiene assets.

**Tests que previenen regresión:**

- `AutoUpdaterDetectionTest` cubre la detección del bundle Windows pero no cubre este caso (release sin binarios). Si se hace v4.3 o similar, agregar test `checkForUpdate skips release without JAR assets`.

---

## Patrón de bug recurrente: detector de ffmpeg falla en Windows no estándar

**Síntoma:** "El video solo graba los primeros 2:56 segundos" (el primer segmento de 3 min). Reportado en v4.2.2.

**Causa raíz:** `AdbBridge.findFfmpegImpl` usaba `which ffmpeg` (Unix-only, no-op en Windows) + un solo path hardcoded `C:\ffmpeg\bin\ffmpeg.exe`. Usuarios con ffmpeg via WinGet, Scoop, Chocolatey o carpeta custom tenían `null` y el concat silenciosamente no corría.

**Fix definitivo (implementado en v4.2.3):** `core/ToolResolver.kt` unifica la lógica. En Windows usa `where`, en Unix `which`. Candidates Windows: `C:\ffmpeg\bin\`, `C:\Program Files\ffmpeg\bin\`, `%ProgramData%\chocolatey\bin\`, `%USERPROFILE%\scoop\shims\`, `%USERPROFILE%\scoop\apps\ffmpeg\current\bin\`, y glob de `%LOCALAPPDATA%\Microsoft\WinGet\Packages\*FFmpeg*\ffmpeg-*\bin\`. Tests: `ToolResolverTest` (15 tests).

**Lección general:** **nunca hardcodear UN solo path de una herramienta externa en un SO**. Usar siempre la búsqueda del OS (`where`/`which`) primero + una lista amplia de candidates por package manager conocido. Factorizar en un helper puro testeable.

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
