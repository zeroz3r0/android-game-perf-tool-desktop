# Proposal: auto event detection and clean metrics

## Intent

Reemplazar el flujo manual de marcadores ("acabo de ver un intersticial / vídeo recompensado / pantalla de carga") por **detección automática** de eventos publicitarios, IAP y carga, basada en `adb logcat` + `dumpsys activity` con firmas de los principales SDKs. Los rangos detectados alimentan dos consumidores: (1) un cálculo de métricas **filtrado** que excluye los intervalos contaminados de las medias FPS/CPU/memoria, y (2) un motor de reglas heurísticas determinista que emite **conclusiones cualitativas** legibles en el reporte HTML.

Hoy, cuando un anuncio AdMob/Unity/IronSource se renderiza por encima del juego, su superficie ligera dispara los FPS y ensucia tanto la media como la nota final. La QA tiene que recordar pulsar un botón en el momento exacto — frágil, fácil de olvidar, e inservible en sesiones desatendidas. Ver `explore.md` para el análisis técnico completo.

## Scope

### In Scope
- Subsistema `core/events/EventDetector` con `adb logcat` streaming + polling `dumpsys activity` 1 Hz
- Catálogo `SDKSignatures` para AdMob, Unity Ads, IronSource, AppLovin/MAX, Meta Audience, Google Play Billing
- Modelo `DetectedEvent(type, startMs, endMs, source, signature)` expuesto como `StateFlow` en el ViewModel
- Histories temporizadas `cpuTimed`, `memTimed`, `tempCpuTimed` (gemelas de `fpsTimed`)
- `core/metrics/FilteredMetricsCalculator` puro: produce agregados **filtered** (primario) + **raw** (secundario)
- Padding simétrico ±500 ms configurable alrededor de cada rango excluido
- `core/conclusions/ConclusionEngine` puro con catálogo inicial de 8 reglas (FPS estable bajo, throttling térmico, fuga de memoria, jank con FPS normal, cap de 30 fps, CPU saturada, comparativa filtered vs raw, recuperación térmica en loadings)
- Reporte HTML: nueva sección `#sec-conclusions`, tabla unificada `#sec-events` (manuales + auto), tarjetas de métricas dual-view (filtered grande + raw chico), bandas sombreadas en el chart de FPS
- iOS best-effort: detección de StoreKit IAP vía syslog + ventanas foreground-app-loss (sin Developer Mode)
- Marcadores manuales se mantienen como fallback para SDKs no cubiertos

### Out of Scope
- Edición post-hoc de eventos detectados (merge/split/dismiss en el reporte)
- Detección visual por computer-vision sobre el vídeo grabado
- Detección iOS completa con Developer Mode
- Configuración externa de reglas heurísticas (YAML/JSON) — quedan en Kotlin
- Resúmenes vía LLM (rechazado por no-determinismo, coste, dependencia de red)
- Más SDKs publicitarios fuera de los 6 iniciales (extensible vía PR de una línea)

## Approach

Tres pilares ortogonales, cada uno testeable y entregable de forma independiente (ver `explore.md` §"Proposed approach"):

1. **Detección**: `EventDetector` corre como coroutine hermana del loop de captura. Posee UN proceso `adb logcat` filtrado por tags + un poller `dumpsys activity` a 1 Hz. Emite `DetectedEvent` cuando una firma encaja temporalmente con el juego en foreground (ventana ≤2 s).

2. **Filtrado**: `FilteredMetricsCalculator.computeFiltered(timed, excludedRanges)` es función pura. Se invoca dos veces — con rangos y sin rangos — para producir vistas filtered y raw. Si el filtrado descarta >70% de la sesión, fallback a raw + warning prominente. `FinalScoreCalculator` recibe los agregados filtered como input primario (mismo contrato `GradingInput`).

3. **Conclusiones**: `ConclusionEngine` consume `ConclusionInput(filtered, raw, tier, thermalSeries, memSeries, jankRatio)` y ejecuta el catálogo de reglas. Cada `Rule` es un objeto Kotlin con `matches(input): Boolean` y `render(input): Conclusion(severity, headline, body, recommendation?)`. Salida ordenada por severidad → ID estable. Renderizado en `#sec-conclusions` como tarjetas con icono, métricas citadas inline y recomendación accionable.

Decisión clave: reglas en **código Kotlin**, no LLM ni YAML — testeables con fixtures puros, versionables en git diff, explicables (cada conclusión cita los valores que la dispararon), deterministas.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/events/EventDetector.kt` | New | Orquestador logcat + dumpsys, emite stream de `DetectedEvent` |
| `core/events/SDKSignatures.kt` | New | Tabla constante de activities + log tags + regex por SDK |
| `core/events/LogcatLineParser.kt` | New | Parser regex puro de líneas logcat |
| `core/events/DetectedEvent.kt` | New | Data class del modelo |
| `core/metrics/FilteredMetricsCalculator.kt` | New | Agregación pura con rangos excluidos opcionales |
| `core/metrics/MetricsAggregates.kt` | New | Data class dual-view (filtered + raw) |
| `core/conclusions/ConclusionEngine.kt` | New | Runner de reglas heurísticas |
| `core/conclusions/Rule.kt` | New | Interfaz `Rule` + data class `Conclusion` |
| `core/conclusions/rules/*.kt` | New | 8 archivos, uno por regla inicial |
| `core/AdbBridge.kt` (o `AdbLogcat.kt`) | Modified | `startLogcat(deviceId, tagFilter, lineSink)` con `Process` gestionado |
| `core/AdbBridgeApi.kt` | Modified | Método logcat en interfaz para `FakeAdbBridge` |
| `viewmodel/AppViewModel.kt` | Modified | Wire `EventDetector` en `startCapture`; expose `events` StateFlow; rutea filtered a `FinalScoreCalculator`; añade timed-history twins |
| `report/ReportGenerator.kt` | Modified | `#sec-conclusions`, `#sec-events`, dual-view cards, bandas sombreadas en chart FPS |
| `core/grading/FinalScoreCalculator.kt` | Modified | Sin cambio lógico; documentar "values are post-filter" |
| `sidecar/gameperf_sidecar/main.py` + `events.py` | New/Modified | Endpoint iOS: StoreKit syslog + foreground-loss windows |
| `core/ios/SidecarClient.kt` | Modified | Endpoint cliente para stream de eventos iOS |
| Tests `src/test/kotlin/...` | New | ≥80% line coverage en módulos puros, sin mocks |
| `src/test/resources/logcat-fixtures/` | New | Fixtures reales por SDK (admob, unity, ironsource, applovin, billing) |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Logcat ring buffer drops bajo carga (Unity DEBUG verbose) | Medium | Tag-filter agresivo (`AdActivity:D '*:S'`); detectar gaps >5 s y marcar ventana como low-confidence |
| Release builds strippean logs SDK vía ProGuard/R8 | Medium | `dumpsys activity` captura el activity launch — sobrevive sin logs |
| Falsos positivos por activities no-juego (home button) | Medium | Predicado requiere SDK conocido + juego en foreground ≤2 s antes |
| iOS sin Developer Mode no ve logs app-level | High | Downgrade explícito en UI: "Detección automática (Android completa, iOS parcial)" |
| Reglas heurísticas dan consejo erróneo en edge cases | Medium | Reglas consideran device tier; disclaimer "interpretar como hipótesis" en sección |
| Precisión de correlación temporal (FPS 500 ms vs logcat ms) | Low | Padding ±500 ms simétrico; reloj de referencia = recepción desktop |
| Eventos solapados (interstitial cierra mientras banner recarga) | Low | Unión de rangos antes de filtrar y dibujar |
| Filtrado descarta >70% de la sesión | Low | Fallback a raw + warning prominente en reporte |
| Sesiones largas con cientos de eventos rompen el reporte | Low | Cap a 500 eventos; switch a histograma agregado |
| Patrón duplicación tipo ToolResolver (CLAUDE.md) | Medium | Una sola fuente de verdad: `SDKSignatures` constante |

## Rollback Plan

1. Quitar invocación a `EventDetector.start()` en `AppViewModel.startCapture` — capture loop sigue funcionando sin detección
2. `FilteredMetricsCalculator.computeFiltered(..., excludedRanges = emptyList())` ya equivale al comportamiento actual; basta con pasar lista vacía
3. Comentar las nuevas secciones `#sec-conclusions` y `#sec-events` en `ReportGenerator` (las secciones existentes `#sec-markers`, `#sec-summary`, `#sec-dashboard` permanecen intactas)
4. Marcadores manuales nunca se removieron — siguen funcionando idénticos
5. iOS: degradar `SidecarClient` para no consultar el endpoint `/events` (no rompe nada existente)

## Dependencies

- `core.HardwareScoring.detectTier(gpu)` (ya existe) — input al `ConclusionEngine`
- `chartjs-plugin-annotation` (ya cargado en el reporte) — para las bandas sombreadas
- `pymobiledevice3.OsTraceService` (ya usado en `devices.py:218-234`) — para syslog iOS
- `captureStartTime` de `AppViewModel` (ya existe) — reloj de referencia para timestamps relativos
- Sin nuevas dependencias externas, sin cambios de Gradle, sin tooling adicional

## Success Criteria

- [ ] Detector reconoce AdMob `AdActivity` vía dumpsys + logcat con ≥90% precisión sobre fixture de 5 juegos reales (mix casual + mid-core)
- [ ] Detector identifica Google Play Billing IAP en <2 s del activity llegando al top of stack
- [ ] `filtered.avgFps != raw.avgFps` para toda sesión con al menos un evento detectado (filtrado hace algo)
- [ ] Filtered y raw coinciden ±0.1 fps en sesiones sin eventos detectados (filtrado es no-op cuando nada dispara)
- [ ] `ConclusionEngine` dispara ≥1 regla en >80% de sesiones de prueba
- [ ] 0 conclusiones CRITICAL falsas sobre fixture de "sesiones de referencia bien-comportadas"
- [ ] `#sec-conclusions` renderiza en HTML con ≥1 conclusión en castellano formal/tuteo, distinta de `#sec-problems`
- [ ] Módulos puros (`EventDetector` helpers, `FilteredMetricsCalculator`, `ConclusionEngine`, `SDKSignatures`) con ≥80% line coverage en tests sin mocks
- [ ] Sesión iOS sin eventos detectados produce reporte válido con conclusión "iOS detección parcial"
- [ ] `./gradlew check` pasa (detekt + tests)
