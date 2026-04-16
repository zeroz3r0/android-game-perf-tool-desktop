# Performance Testing — Metodología y Métricas

Documento de referencia para QA de rendimiento con **GamePerf Desktop**.
Cubre las 6 funciones del plan de testing de performance.

---

## 1. Investigación ✅

Se evaluaron las opciones de performance testing para Android:

| Herramienta | Pros | Contras |
|-------------|------|---------|
| **Android Studio Profiler** | Oficial, integrado con IDE | Requiere build debuggable, overhead alto, no funciona con builds release |
| **adb shell dumpsys gfxinfo** | Estándar Android, info por app | Solo funciona con apps que renderizan vía framework (NO Unity release) |
| **adb shell dumpsys SurfaceFlinger** | Funciona con cualquier app (Unity, Vulkan, OpenGL) | Nombres de layer complejos, requiere parseo |
| **GamePerf Desktop** (in-house) | Funciona con release builds, captura FPS + CPU + RAM + temp + video sincronizado | Requiere dispositivo con ADB habilitado |

**Decisión**: herramienta propia basada en SurfaceFlinger `--latency` + `dumpsys meminfo` + `cat /proc/stat`. Única opción que mide builds de producción sin modificar la app.

---

## 2. Métricas medidas

### 2.1 FPS / Frame Time (core)

| Métrica | Definición | Fuente |
|---------|-----------|--------|
| `avgFps` | Promedio de FPS durante la sesión | SurfaceFlinger `--latency` por layer |
| `p50Fps` | Mediana — FPS que tuvo el 50% del tiempo | Percentil sobre ventana 1s |
| `p5Fps` | FPS en el 5% peor de los momentos | Percentil — detecta caídas sostenidas |
| `p1Fps` | FPS en el 1% peor | Percentil — detecta stutters puntuales |
| `avgFrameTime` | Tiempo medio por frame (ms) | Diferencias entre timestamps consecutivos |
| `p99FrameTime` | Peor 1% de frame times (ms) | Detecta frames largos |
| `jank` | Frames > 16.67 ms | Frame times sobre 60fps objetivo |
| `stutter` | Frames > 100 ms | Congelamientos visibles |
| `frameDrops` | Frames perdidos por el compositor | `dumpsys SurfaceFlinger` delta inicio/fin |

### 2.2 Recursos del sistema

| Métrica | Unidad | Threshold preocupante |
|---------|--------|----------------------|
| `avgCpu` | % | > 80 % |
| `maxCpu` | % | > 95 % |
| `peakMemMb` | MB (PSS total) | > 1500 MB mid-range · > 2000 MB alarma |
| `nativeMb` | MB heap nativo | informativo |
| `javaMb` | MB heap JVM | informativo |
| `maxTempCpu` | °C | > 45 °C (thermal throttling) |
| `maxTempGpu` | °C | > 50 °C |
| `batteryDrain` | % | > 15 %/10 min = consumo alto |

### 2.3 Contexto

- `gamePackage` — com.ejemplo.juego
- `deviceModel` + `deviceGrade` — score del hardware en sí (independiente del juego)
- `markers` — eventos manuales del tester (Intersticial, Video Reward, Carga, etc.)

---

## 3. Sistema de calificación

### Puntaje por sesión (A–F)

Arranca en **100 puntos**. Penalizaciones:

| Condición | –puntos | Razón |
|-----------|---------|-------|
| `p50Fps < 30` | –35 | Mediana muy baja = mayoría del tiempo con lag |
| `p50Fps < 45` | –20 | Falta de fluidez perceptible |
| `p50Fps < 55` | –8 | Cerca pero no llega a 60 fps |
| `p5Fps < 20` | –15 | Caídas sostenidas visibles |
| `p5Fps < 30` | –6 | Caídas moderadas |
| `frameDrops > 30` | –12 | Problema del compositor |
| `peakMem > 2000 MB` | –12 | Riesgo de cierre forzado |
| `peakMem > 1500 MB` | –6 | Memoria alta |
| `maxTempCpu > 45 °C` | –12 | Thermal throttling activo |
| `avgCpu > 85 %` | –12 | CPU saturada |

| Grado | Puntaje | Interpretación |
|-------|---------|----------------|
| **A** | ≥ 85 | Fluido, listo para release |
| **B** | ≥ 70 | Jugable, mejoras menores |
| **C** | ≥ 55 | Aceptable en gama media-alta |
| **D** | ≥ 40 | Necesita optimización |
| **F** | < 40 | No listo para producción |

**Nota** (v4.2.0): se usa `p50`/`p5` en vez de `avg`/`p1`. Razón: las loading screens generan 1-2 segundos de FPS bajo que **no** deben tanquear la nota si el resto de la sesión es estable.

---

## 4. Metodología de pruebas

### 4.1 Condiciones controladas (obligatorias)

- **Dispositivo**: brillo 50 %, modo avión **OFF**, WiFi conectado
- **Batería**: > 40 % al empezar — evita throttling por batería baja
- **Temperatura**: dispositivo a temperatura ambiente antes de capturar (no recién usado)
- **Apps en background**: matar todas antes (clear recent apps)
- **Notificaciones**: silenciar (pausan el juego en algunos dispositivos)

### 4.2 Duración por sesión

- **Mínimo**: 2 minutos — suficiente para percentiles estables
- **Recomendado**: 5 minutos por escenario
- **Máximo útil**: 10 minutos — más no añade información

### 4.3 Escenarios a capturar por juego

1. **Gameplay core** — lo que más juega el usuario
2. **Escena pesada** — la que más estresa (combate, muchas entidades)
3. **Menú / lobby** — baseline en reposo
4. **Transiciones** — load → gameplay → load
5. **Sesión larga** — 10+ min para detectar memory leaks y thermal throttling

### 4.4 Uso de marcadores

Durante la captura, marcar con el botón correspondiente cuando ocurra:

- **Carga** — inicio de una loading screen
- **Intersticial** — anuncio a pantalla completa
- **Video Reward** — anuncio de video
- **Cambio escena** — transición entre zonas
- **Nota** — texto libre para cualquier evento

Los marcadores aparecen en la timeline y en el reporte.

---

## 5. Benchmarks — establecer referencia

### 5.1 Baseline propio

Primera iteración: capturar **3 sesiones** de cada escenario del juego propio. Usar los resultados como baseline.

```
Juego propio · Escena gameplay · Pixel 7
  Sesión 1: avg 58 fps, p5 42, peakMem 890 MB — B
  Sesión 2: avg 57 fps, p5 40, peakMem 905 MB — B
  Sesión 3: avg 59 fps, p5 45, peakMem 880 MB — B
  → Baseline aceptado: grado B, avg ~58, p5 ~42
```

### 5.2 Benchmark competencia

Capturar **1 sesión por escenario** de 2-3 juegos competidores del mismo género. Ejemplo:

| Juego | Escena | avg | p5 | peakMem | Grado |
|-------|--------|-----|----|---------| ------|
| **Nuestro** | Gameplay | 58 | 42 | 890 MB | B |
| Competidor A | Gameplay | 61 | 54 | 620 MB | A |
| Competidor B | Gameplay | 55 | 38 | 1100 MB | C |

En la app, marcar las sesiones con la tag **"COMPETENCIA"** y usar el botón "Comparar sesiones" para generar un reporte comparativo.

### 5.3 Device tiers

Usar dispositivos de gama baja, media y alta. Cada uno con su propio baseline. El `deviceGrade` de la herramienta ajusta las expectativas por hardware.

---

## 6. Fiabilidad de datos

### 6.1 Test de reproducibilidad

Hacer el **mismo test 3 veces** con las mismas condiciones. Calcular desviación estándar de cada métrica:

| Métrica | σ aceptable | σ = problema |
|---------|------------|--------------|
| `avgFps` | ± 2 fps | > ± 5 fps |
| `p5Fps` | ± 3 fps | > ± 6 fps |
| `peakMemMb` | ± 50 MB | > ± 150 MB |
| `avgCpu` | ± 5 % | > ± 10 % |

Si la desviación está fuera del rango, las condiciones no son controladas (temperatura, background apps, etc.) o hay un bug en la medición.

### 6.2 Cross-validation

- **FPS en timeline de la app** vs **contador FPS visible del juego** (si existe) → deben coincidir ± 2 fps
- **peakMem** vs **Android Settings → Storage → Apps → Memory** → deben coincidir
- **maxTempCpu** en sesión con juego demandante debe ser > baseline sin jugar

### 6.3 Sanity checks automáticos

La app ya detecta problemas:
- `p50Fps` anómalamente alto (> 144 fps) → probablemente layer incorrecto
- Frame times < 1 ms o > 1000 ms → descartados automáticamente
- Si `captureFrames` falla 3 veces seguidas → avisa "Dispositivo desconectado"

---

## 7. Testeo de la herramienta

### 7.1 Checklist de edge cases

- [ ] Captura de juego **Unity release build** (BLAST layer)
- [ ] Captura de juego **Unreal release build**
- [ ] Captura de juego **nativo Android** (Java/Kotlin)
- [ ] Juego en **portrait** (vertical)
- [ ] Juego en **landscape** (horizontal) — video debe grabar horizontal
- [ ] **Rotación mid-session** (portrait → landscape)
- [ ] **Anuncio interstitial** — FPS debe recuperarse al cerrarlo
- [ ] **Video reward** — no debe tanquear la nota
- [ ] **Loading screen** — segundo 0-5 no debe dominar el score (fix v4.2.0)
- [ ] **Sesión larga** (10+ min) — sin OOM, sin crash del player
- [ ] **Scrub del video** — fluido, sin lag al mover la timeline
- [ ] **Re-abrir sesión vieja** desde historial — timeline FPS debe aparecer
- [ ] **Comparar 2 sesiones** — reporte comparativo correcto
- [ ] Dispositivo **USB**
- [ ] Dispositivo **WiFi ADB**
- [ ] **Disconnect mid-session** — manejado sin crash
- [ ] **Batería < 20 %** — thermal throttling detectado en el reporte
- [ ] Export **PDF** — grafos se ven bien, no hay overflow

### 7.2 Tests automatizados

La app tiene **256 tests unitarios** (0 failures). Ejecutar con:
```bash
./gradlew test
```

Cubre: regex parsers, serialización JSON, session history, FileCleanup, capture loop, video concat resilience.

### 7.3 Bugs conocidos resueltos

| Bug | Versión fix |
|-----|-------------|
| FPS "--" por `(BLAST)` en layer name → syntax error en sh | v4.2.0 |
| Cache de layer stale tras anuncio / scene change | v4.2.0 |
| Video grabado en portrait cuando juego era landscape | v4.2.0 |
| Player crashea con videos largos (OOM 5 GB heap) | v4.2.0 |
| Nota injusta por loading screen (p1 penaliza demasiado) | v4.2.0 |

---

## 8. Flujo de trabajo recomendado

1. **Preparar dispositivo** (condiciones 4.1)
2. **Conectar** por USB o WiFi
3. **Abrir el juego** — ir al escenario a testear
4. **Capturar** 2-5 minutos desde la app
5. **Marcar eventos** (cargas, anuncios) con los botones de la app
6. **Parar** → se genera reporte HTML + video
7. **Revisar métricas** en la pantalla de resultados
8. **Repetir** 3× para fiabilidad (§6.1)
9. **Comparar** con competencia o versión anterior (tag COMPETENCIA)
10. **Exportar PDF** para compartir con el equipo

---

## Versión

- **Documento**: 1.0 (abril 2026)
- **GamePerf Desktop**: v4.2.0
- **Autor**: equipo QA
