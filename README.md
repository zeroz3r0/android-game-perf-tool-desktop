# Game Performance Tool - Desktop App

Aplicacion de escritorio para medir rendimiento de juegos en dispositivos Android en tiempo real. Genera informes HTML con graficos, percentiles, notas y diagnostico de problemas.

Graba video del gameplay durante la sesion para contrastar datos con lo que ocurre en pantalla. Incluye reproductor integrado con timeline interactivo, marcadores, comparacion de competencia y auto-actualizador.

---

## Requisitos

- **Java 17** o superior
- **ADB** (Android Debug Bridge) en el PATH
- Dispositivo Android con **depuracion USB activada**

---

## Instalacion

### macOS

#### Opcion 1: Instalar el DMG (recomendado)

1. Descargar `GamePerf-3.1.0.dmg` desde Releases
2. Abrir el DMG y arrastrar **GamePerf** a la carpeta Aplicaciones
3. **Primera vez**: macOS bloqueara la app por no estar firmada:
   - Ve a **Preferencias del Sistema > Seguridad y Privacidad**
   - Haz clic en **"Abrir igualmente"** junto al mensaje de GamePerf
   - Alternativamente: `xattr -cr /Applications/GamePerf.app` en Terminal

#### Opcion 2: Compilar desde fuente

```bash
# Requisitos previos
brew install openjdk@17
brew install android-platform-tools   # instala ADB

# Clonar y compilar
git clone https://github.com/zeroz3r0/android-game-perf-tool-desktop.git
cd android-game-perf-tool-desktop
./gradlew run                          # ejecutar directamente
./gradlew packageDmg                   # crear instalador DMG
```

#### Instalar ADB en macOS

```bash
brew install android-platform-tools
```

Verifica que funciona:
```bash
adb version
```

---

### Windows

#### Opcion 1: Instalar el MSI

1. Descargar `GamePerf-3.1.0.msi` desde Releases
2. Ejecutar el instalador
3. **Windows Defender / SmartScreen**: Al no estar firmado, Windows lo bloqueara:
   - Clic en **"Mas informacion"** en el dialogo de SmartScreen
   - Clic en **"Ejecutar de todas formas"**
   - Si el antivirus lo borra: anade una **exclusion** para la carpeta de GamePerf

> **Nota sobre antivirus**: Las apps Java empaquetadas como MSI sin certificado de code signing son frecuentemente marcadas como sospechosas por Windows Defender y antivirus de terceros. Esto es un falso positivo. Para evitarlo en un entorno corporativo, se recomienda firmar el MSI con un certificado de code signing (ver seccion "Firma de codigo" mas abajo).

#### Opcion 2: Compilar desde fuente

```powershell
# Requisitos previos
# 1. Instalar Java 17: https://adoptium.net/temurin/releases/?version=17
# 2. Instalar Android SDK Platform-Tools: https://developer.android.com/studio/releases/platform-tools

# Clonar y compilar
git clone https://github.com/zeroz3r0/android-game-perf-tool-desktop.git
cd android-game-perf-tool-desktop
.\gradlew.bat run                      # ejecutar directamente
.\gradlew.bat packageMsi               # crear instalador MSI
```

#### Instalar ADB en Windows

1. Descargar [Android SDK Platform-Tools](https://developer.android.com/studio/releases/platform-tools)
2. Extraer el ZIP a `C:\platform-tools`
3. Anadir `C:\platform-tools` al **PATH** del sistema:
   - Inicio > "Variables de entorno" > PATH > Nuevo > `C:\platform-tools`
4. Reiniciar terminal y verificar: `adb version`

---

## Compilar desde fuente

```bash
# Clonar
git clone https://github.com/zeroz3r0/android-game-perf-tool-desktop.git
cd android-game-perf-tool-desktop

# Ejecutar sin instalar
./gradlew run

# Crear instaladores nativos
./gradlew packageDmg    # macOS: genera .dmg
./gradlew packageMsi    # Windows: genera .msi

# Crear JAR ejecutable
./gradlew jar
java -jar build/libs/android-game-perf-tool-desktop-3.1.0.jar

# Ejecutar tests
./gradlew test
```

No necesitas instalar Gradle - el proyecto incluye Gradle Wrapper (`./gradlew` / `gradlew.bat`).

---

## Uso rapido

1. **Conecta** tu dispositivo Android por USB
2. **Activa** Depuracion USB en Opciones de desarrollador
3. **Abre** un juego en el dispositivo
4. **Ejecuta** GamePerf y pulsa "Iniciar prueba"
5. El informe HTML + video se guardan en `~/GamePerf Reports/`

### Duraciones disponibles

| Opcion | Uso recomendado |
|--------|-----------------|
| **Libre** | Sesion manual, paras cuando quieras |
| **30s** | Test rapido de una escena |
| **1m** | Prueba estandar |
| **2m** | Carga + gameplay |
| **5m** | Sesion completa |
| **10m** | Sesion extendida con thermal throttling |
| **1h** | Test de estabilidad / soak test |

### Modo WiFi

Permite medir el **consumo real de bateria** sin que el cable USB cargue el dispositivo:
1. Pulsa "Cambiar a WiFi"
2. Desconecta el cable USB cuando se indique
3. La prueba corre via WiFi ADB

---

## Funcionalidades

### Metricas en tiempo real
- FPS, Frame Times, CPU, Memoria, Temperatura, Bateria
- Graficos en vivo durante la captura
- Indicador pulsante de grabacion

### Reproductor de video integrado
- Reproduce el video del gameplay directamente en la app
- Controles de reproduccion: play/pause, avanzar/retroceder 5s, velocidad variable (0.5x-2x)
- Atajos de teclado: Espacio (play/pause), Flechas izq/der (seek ±5s), Escape (detener captura)
- Usa extraccion de frames batch con ffmpeg y decodificacion directa JPEG via Skia

### Timeline interactivo con overlay de FPS
- Timeline arrastrabe con playhead sincronizado al video
- Zonas de color por rendimiento FPS (verde >30, amarillo 20-30, rojo <20)
- Pausado automatico al scrubear manualmente

### Marcadores de sesion
- Tipos predefinidos: Intersticial, Video Reward, Carga, Cambio de escena
- Notas personalizadas con texto libre
- Selector de color (10 colores predefinidos)
- Se pueden agregar durante la captura en vivo o desde el timeline en resultados
- Edicion y eliminacion de marcadores existentes

### Comparacion de competencia
- Etiqueta sesiones como "Nuestro juego" o "Competencia"
- Campo para nombre del juego competidor
- ComparisonScreen con metricas lado a lado y graficos radar
- Generacion de informe comparativo HTML

### Grabacion de video
- Se graba automaticamente el gameplay al iniciar la prueba
- El video se guarda junto al informe para contrastar en que momentos se producen caidas
- Usa el encoder de hardware del SoC (no consume GPU del juego)
- Soporta sesiones largas encadenando segmentos de 3 minutos
- Archivos unicos por sesion: `video_yyyyMMdd_HHmmss_0.mp4`, `video_yyyyMMdd_HHmmss_1.mp4`, etc.

### Doble nota de rendimiento
- **Nota General**: puntuacion objetiva (A-F) basada en metricas absolutas
- **Nota por Dispositivo**: ajustada al hardware del movil. Un juego a 45 FPS en un Snapdragon 450 es nota A (impresionante para ese hardware), pero en un SD 8 Gen 3 seria nota D (pobre para ese hardware)

### Informe HTML
- Graficos interactivos con Chart.js 4
- Tema oscuro rediseñado
- Percentiles FPS y Frame Times
- Timeline de temperaturas
- Problemas detectados con explicacion y solucion
- Desglose de la nota
- Soporte para impresion/PDF
- **Chart.js embebido inline**: el HTML funciona 100% offline despues de generarse, se puede mover/copiar/enviar por mail sin perder los graficos

### Exportacion a PDF
- Boton "Exportar PDF" en pantalla principal (por sesion del historial), pantalla de resultados y pantalla de comparacion
- Selector nativo de archivo (NSSavePanel en macOS, IFileDialog en Windows, GTK en Linux): el usuario decide donde guardar el PDF
- Formato A4 con backgrounds del tema oscuro preservados
- Renderiza identico al HTML del browser (todos los Chart.js incluidos)
- El PDF queda en el sistema de archivos del usuario y NO esta sujeto a la politica de retencion (no se borra automaticamente)
- Usa Playwright + Chromium headless por debajo. La primera ejecucion descarga Chromium (~180 MB) a `~/.cache/ms-playwright/` con un dialogo "Preparando motor PDF (primera vez solamente)..."; las ejecuciones siguientes funcionan offline

### Correlacion video-metricas
- Cada muestra de FPS se registra con su segundo exacto
- Tabla "FPS por segundo" en el informe HTML con:
  - Timestamp (segundo de la sesion)
  - Valor FPS
  - Estado visual (Critico/Bajo/Medio/Bueno/Excelente)
  - Barra visual de color
- Archivos de video con nombre unico por sesion (`video_yyyyMMdd_HHmmss_N.mp4`)

### Historial de sesiones
- Las ultimas **5** pruebas se guardan en `~/GamePerf Reports/history.json` (politica de retencion)
- Cuando se captura una nueva sesion estando ya en el limite, la mas vieja se borra silenciosamente junto con su informe HTML y todos los segmentos de video
- Una linea pasiva en el header del historial avisa cuando estas en 5/5 ("Historial: 5/5 - la proxima captura reemplazara la mas antigua")
- Para preservar una sesion permanentemente, exportala a PDF antes de la proxima captura
- Desde la pantalla principal podes:
  - Ver nombre, dispositivo, nota y duracion de pruebas anteriores
  - Renombrar sesiones
  - Exportar sesiones a PDF (preservacion permanente fuera de la politica de retencion)
  - Eliminar sesiones con confirmacion (borra entrada JSON + informe HTML + todos los segmentos de video)
  - Abrir informe o video de sesiones previas
  - Seleccionar sesiones para comparacion

### Limpieza automatica de archivos
- Al iniciar la app, se hace un barrido de `~/GamePerf Reports/` que:
  - Borra archivos huerfanos (HTMLs/videos que ya no estan referenciados en `history.json`)
  - Repara entradas del historial cuyos paths apuntan a archivos inexistentes (vacia el path, conserva las metricas)
- El barrido NUNCA toca:
  - El subdirectorio `updates/` (auto-actualizador protegido)
  - `history.json`
  - Archivos fuera de los prefijos `informe_`, `video_`, `recording_`, `comparativa_` (ej. tus notas o backups)
- Las comparativas se generan en `java.io.tmpdir` (no ensucian `~/GamePerf Reports/`) y se borran al cerrar la app o al siguiente inicio

### Auto-actualizador
- Chequea GitHub Releases al iniciar la app
- Muestra banner con nueva version disponible
- Descarga e instala la actualizacion con barra de progreso
- Soporta macOS, Linux y Windows

### CI/CD
- Pipeline de GitHub Actions para builds multi-plataforma (macOS, Linux, Windows)
- Genera artefactos descargables por cada release

### Icono personalizado
- Icono de app personalizado para macOS (.icns) y Windows (.ico)

---

## Firma de codigo (entorno corporativo)

Para evitar que antivirus y SmartScreen bloqueen la app en Windows:

### Opcion 1: Certificado de Code Signing

```bash
# Firmar el MSI con signtool (incluido en Windows SDK)
signtool sign /f certificado.pfx /p password /tr http://timestamp.digicert.com /td sha256 GamePerf-3.1.0.msi
```

Proveedores de certificados: DigiCert (~$300/ano), Sectigo (~$70/ano), SSL.com (~$70/ano).

### Opcion 2: Exclusion en antivirus (para QA interno)

En equipos del equipo de QA, anadir exclusiones:
- **Windows Defender**: Configuracion > Seguridad > Proteccion contra virus > Exclusiones > Carpeta
- **Otros antivirus**: Anadir la carpeta de instalacion de GamePerf a la lista blanca

### Opcion 3: GPO corporativa (Active Directory)

Para despliegue masivo, crear una GPO que permita la ejecucion del MSI sin firma.

---

## Estructura del proyecto

```
src/main/kotlin/com/gameperf/desktop/
├── Main.kt                         # Entry point, ventana principal
├── core/
│   ├── AdbBridge.kt                # Comunicacion con ADB
│   ├── AppVersion.kt               # Version de la app (single source of truth)
│   ├── AutoUpdater.kt              # Auto-actualizador via GitHub Releases
│   ├── HardwareScoring.kt          # Clasificacion de hardware y nota por dispositivo
│   └── SessionHistory.kt           # Persistencia del historial de sesiones
├── viewmodel/
│   └── AppViewModel.kt             # Estado de la app, loop de captura, grading
├── report/
│   └── ReportGenerator.kt          # Generacion de informe HTML y comparativo
└── ui/
    ├── theme/Theme.kt              # Colores y tema Material3
    ├── util/Formatting.kt          # Utilidades de formato (locale-safe)
    ├── components/
    │   ├── EmbeddedVideoPlayer.kt  # Reproductor de video integrado (ffmpeg + Skia)
    │   ├── InteractiveTimeline.kt  # Timeline interactivo con FPS overlay y marcadores
    │   ├── MarkerDialog.kt         # Dialog para crear/editar marcadores
    │   ├── MetricCard.kt           # Card de metrica reutilizable + StatRow
    │   └── MiniGraph.kt            # Grafico en tiempo real
    └── screens/
        ├── HomeScreen.kt           # Seleccion de dispositivo, juego e historial
        ├── CaptureScreen.kt        # Dashboard de captura en vivo con marcadores
        ├── ResultsScreen.kt        # Resultados, video player, timeline, metricas
        └── ComparisonScreen.kt     # Comparacion lado a lado entre sesiones

src/test/kotlin/com/gameperf/desktop/
├── AutoUpdaterTest.kt              # Tests de comparacion de versiones
├── FormattingTest.kt               # Tests de formateo de tiempo y locale
└── HardwareScoringTest.kt          # Tests de clasificacion de hardware y grading
```

---

## Licencia

MIT
