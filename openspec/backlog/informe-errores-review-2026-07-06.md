# Informe de revisión de errores — 2026-07-06

**Alcance:** revisión de `main` (HEAD) + working tree sin commitear del Sprint 0 de `logcat-event-stream` (buffer + dual-emit + PID resolver, ver `openspec/changes/logcat-event-stream/tasks.md`).
**Metodología:** auditoría dirigida por los patrones de bug documentados en `CLAUDE.md` (paths hardcodeados, charset, métricas no-scoped a PID, colecciones compartidas entre subsistemas, normalización de datos con dos fuentes, regex inline) + revisión general de excepciones, recursos sin cerrar y consistencia entre `tasks.md` y el código real. Cada hallazgo listado abajo fue verificado leyendo el archivo y línea citados — no son inferencias.

---

## Alta prioridad

### 1. `AppViewModel` importa `AdbBridge` directamente (viola regla arquitectónica explícita)

- **Dónde:** `core/AdbBridgeApi.kt:194`, `viewmodel/AppViewModel.kt:3,865,885,888,960,1044`
- **Problema:** `AdbBridgeApi.startScreenRecord` tipa su parámetro como `AdbBridge.ScreenRecordProfile` — un tipo anidado del objeto concreto, no de la interfaz. Esto obliga a todo consumidor de la interfaz, incluido `AppViewModel`, a importar `com.gameperf.desktop.core.AdbBridge` directamente.
- **Por qué importa:** `CLAUDE.md` dice explícitamente: *"`AppViewModel` solo debe importar `core.model.*`, nunca `core.AdbBridge` directamente."* La fuga no es un descuido puntual del ViewModel: nace en la propia interfaz de abstracción, que delega su contrato en un tipo del singleton concreto en vez de definirlo en un lugar neutral (`core.model`).
- **Fix sugerido:** mover `ScreenRecordProfile` a `core.model`, o definirlo como parte de `AdbBridgeApi` en vez de `AdbBridge`.

### 2. Tarea `T0.8` marcada completa en `tasks.md`, pero el refactor descrito no se hizo

- **Dónde:** `openspec/changes/logcat-event-stream/tasks.md:44` vs. `core/AdbBridge.kt:631-636` (`resolveGamePids`) y `:713-721` (`captureProcessCpuPercent`)
- **Problema:** T0.8 dice *"Refactor `captureProcessCpuPercent` to call the new function via `.firstOrNull()` (zero behaviour change)"* y está marcada `[x]`. Verificado en el código: **no ocurrió**. `captureProcessCpuPercent` mantiene su propia resolución de PID (`cachedPidByPkg`, su propio `shell(deviceId, "pidof $pkg")`, su propio parseo `split(" ")`), totalmente desacoplada de `resolveGamePids()` (que usa `split(Regex("\\s+"))`).
- **Impacto concreto:**
  - Inconsistencia de parseo: `split(" ")` (espacio literal) vs. regex robusto. Un output de `pidof` con espacios múltiples o líder puede fallar en un lado y no en el otro.
  - Dos fuentes de verdad no coordinadas sobre "cuál es el PID del juego": cuando `PidWatchdog` (que usa `resolveGamePids` sin caché) se conecte a producción, el logcat filtrado por `--pid` puede seguir un PID distinto del que usa la métrica de CPU hasta que la caché de esta última se invalide por su cuenta.
- **Acción recomendada:** confirmar con quien cerró el Sprint 0 si el checklist se marcó por error, y si hace falta reabrir T0.8 antes de dar el sprint por terminado.

---

## Prioridad media (código nuevo, aún no conectado a producción)

### 3. `PidWatchdog` compara listas de PIDs sensible al orden

- **Dónde:** `core/events/PidWatchdog.kt:28-31`
- **Problema:** `if (currentPids != lastPids)` compara `List<Int>` posicionalmente. `pidof` puede devolver múltiples PIDs (procesos hijos: webview sandboxed, proceso GPU, etc.) sin garantía de orden estable entre invocaciones sucesivas.
- **Impacto:** un reordenamiento benigno dispara `onPidChange` de forma espuria, reiniciando innecesariamente la captura de logcat filtrada.
- **Fix sugerido:** comparar como `Set<Int>` o lista ordenada.

### 4. `PidWatchdog` sin try/catch alrededor de `resolveGamePids`

- **Dónde:** `core/events/PidWatchdog.kt:25-34`
- **Problema:** el loop de polling no envuelve la llamada en try/catch. Si `resolveGamePids` lanza (p. ej. `require()` interno de `shell()`), la corrutina muere sin manejo.
- **Impacto:** hoy `PidWatchdog` no está wireado a ningún caller de producción (riesgo latente), pero si en el futuro comparte `CoroutineScope` sin `SupervisorJob` con `LogcatCapture` u otro subsistema, una excepción acá cancela en cascada a los hermanos de esa scope. `LogcatCapture` ya envuelve su loop en `catch (_: Exception)`; `PidWatchdog` debería hacer lo mismo antes de conectarse a `AppViewModel` (Sprint 2).

### 5. `SidecarClient` recompila ~13 regex por tick de métricas iOS

- **Dónde:** `core/ios/SidecarClient.kt:180,218-246`
- **Problema:** `extractString/extractInt/extractLong/extractDouble/extractBool/extractArray` compilan un `Regex(pattern)` nuevo en cada invocación. `parseMetrics` los llama ~13 veces por snapshot, en la ruta de polling de una sesión de captura que puede durar minutos u horas.
- **Impacto:** recompila ~13 regexes por tick durante toda la sesión — mismo espíritu que la regla de "regex de hot paths deben ser precompilados", con la salvedad de que el patrón depende de `key` en runtime (no puede ser un simple `private val`).
- **Fix sugerido:** cachear por `key` (`ConcurrentHashMap<String, Regex>`) o parseo manual de substrings.

---

## Baja prioridad

### 6. `GpuUsageParser.parseAdrenoGpuBusy` compila regex inline

- **Dónde:** `core/GpuUsageParser.kt:75`
- Mismo patrón que #5 pero de bajo impacto real (regex trivial, ~1Hz de muestreo). Mover a `private val` top-level, como ya se hizo en `AdbBridge.kt` (`RE_DEVICE_LINE`, `RE_MDNS_SPLIT`).

### 7. `AdbBridge.exec()` no cierra explícitamente el stream del proceso

- **Dónde:** `core/AdbBridge.kt:134-152`
- `process.inputStream.bufferedReader().readText()` sin `.use {}`. `exec()` es el método más caliente del bridge (se llama por cada comando adb). No es un leak garantizado (el finalizer/cleaner de la JVM eventualmente cierra los descriptores), pero bajo uso sostenido podría acumular file descriptors antes de que actúe el GC. Preexistente, no parte del diff de Sprint 0.

---

## Patrones del catálogo verificados como NO recurrentes

- **Paths hardcodeados:** todo el código nuevo/existente relevante pasa por `ToolResolver.find()`. `BrowserDetector.kt:180` usa `ProcessBuilder` directo pero es detección de navegador con fallback multiplataforma explícito, no adb/ffmpeg/ffprobe.
- **Charset:** el único `InputStreamReader` nuevo (`LogcatCapture.kt:95`) ya pasa `StandardCharsets.UTF_8` explícito.
- **Colecciones compartidas Process/Job:** `activeFrameProcesses`/`activeThumbnailProcesses` siguen separados (fix v4.3.2 se mantiene). Todos los `Job?` del resto del código son campos dedicados por clase.
- **Normalización underscore/hyphen:** ambos call sites de `DeviceNameResolver` siguen pasando por `resolve()`.
- **Detección de eventos / conclusiones fuera de sus paquetes:** no se encontró lógica fuera de `core/events/` o `core/conclusions/`.
- **TODOs/FIXMEs, tests `@Disabled`/`@Ignore`:** ninguno encontrado.
- **Usos de `!!`:** todos guardados por chequeo `null` previo en el mismo scope.

---

## Resumen para priorizar

| # | Severidad | Bloquea Sprint 1-3 de logcat-event-stream? |
|---|---|---|
| 1 | Alta | No, pero es deuda arquitectónica que crece con cada nuevo caller |
| 2 | Alta | Sí — afecta directamente la coherencia de PID entre CPU% y logcat que Sprint 2 va a wirear |
| 3 | Media | Sí — antes de conectar `PidWatchdog` a `AppViewModel` en Sprint 2 |
| 4 | Media | Sí — mismo punto que #3 |
| 5 | Media | No (subsistema iOS, no relacionado a logcat-event-stream) |
| 6, 7 | Baja | No |
