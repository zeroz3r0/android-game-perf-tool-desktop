# GamePerf Desktop

[🇪🇸 Español](README.md) · [🇬🇧 English](README_EN.md)

**Mide el rendimiento de tus juegos móviles con una aplicación sencilla, sin scripts ni terminal.**

Conecta el teléfono (Android o iPhone), pulsa un botón, juega al nivel que te interesa — la aplicación te devuelve un informe con FPS, CPU, memoria, temperatura, vídeo del gameplay, gráfico interactivo y una nota (A/B/C/...) que indica si el rendimiento es bueno o si hay algo que mejorar.

Gratuita, código abierto, funciona en Mac, Windows y Linux.

---

## Índice

- [Para quién es](#para-quién-es)
- [Qué hace](#qué-hace)
- [Instalación](#instalación)
- [Primera sesión en 4 pasos](#primera-sesión-en-4-pasos)
- [Plataformas soportadas](#plataformas-soportadas)
- [Preguntas frecuentes](#preguntas-frecuentes)
- [Compilar desde el código fuente](#compilar-desde-el-código-fuente)
- [Estructura del proyecto](#estructura-del-proyecto-para-desarrolladores)
- [Contribuir](#contribuir)
- [Licencia](#licencia)

---

## Para quién es

| Perfil | Para qué lo usa |
|---|---|
| **QA / Testers** | Validar que el juego se ejecuta de forma estable antes de publicar una build. Detectar caídas de FPS, sobrecalentamiento y fugas de memoria durante sesiones largas |
| **Diseñadores de videojuegos** | Comprobar si un nivel o escena concreta baja el rendimiento. Comparar dos builds en paralelo |
| **Product / Producer** | Compartir informes con el equipo (PDF o archivo exportable). Comparar tu juego con la competencia |
| **Desarrolladores** | Integrar en el flujo de CI. Exportar datos para análisis. Depurar regresiones de rendimiento |

No hace falta saber ADB ni usar la terminal. Si alguna vez has conectado un teléfono al ordenador, ya sabes lo suficiente.

---

## Qué hace

### Captura en directo

- **Métricas en tiempo real** durante la sesión: FPS, frame time (p1% / p50% / p99%), CPU, memoria (total / native / java), temperatura (CPU / GPU / batería / piel del dispositivo) y nivel de batería
- **Grabación de vídeo** del gameplay en paralelo, mediante el codificador de hardware del teléfono (no afecta al FPS del juego que estás midiendo)
- **Marcadores durante la sesión**: pulsa un botón para marcar el instante exacto en el que ocurre algo (aparece un anuncio intersticial, cambia la escena, se carga algo pesado, etc.). Luego los puedes revisar en el informe

### Análisis posterior a la sesión

- **Informe HTML** con gráficos interactivos, tabla de FPS por segundo, percentiles y una sección de «problemas detectados» con explicaciones en castellano (por ejemplo: «La temperatura llegó a 48 °C en el minuto 3 — probable thermal throttling»)
- **Reproductor de vídeo integrado** con línea de tiempo sincronizada. Puedes arrastrar el cursor del gráfico de FPS y el vídeo salta al mismo instante, de modo que ves exactamente qué estaba ocurriendo cuando cayó el rendimiento
- **Nota automática (S / A / B / C / D / F)** con un sistema de puntuación justo por género de juego (los umbrales de un shooter no son los mismos que los de un puzle casual)
- **Comparación entre sesiones**: elige 2 sesiones del historial y míralas en paralelo para comparar «build antes / después», «tu juego / competencia» o «dispositivo A / dispositivo B»

### Colaboración en equipo

- **Exportación de sesión a archivo `.gameperf`** (ZIP autocontenido con informe HTML + todas las métricas). Lo puedes compartir por cualquier medio: correo, Slack, carpeta compartida, USB. El destinatario lo importa en su copia de la aplicación y lo ve como una sesión más del historial
- **Exportar a PDF** desde cualquier sesión o informe comparativo. El PDF queda fuera de la política de borrado automático del historial
- **Favoritos**: marca sesiones con una estrella para que no se borren nunca de forma automática

### Ayuda integrada

- **Guía dentro de la aplicación** (botón del libro arriba a la derecha): explica la metodología de testing y el significado de cada métrica. No hay que ir a GitHub ni abrir documentación externa — está a un clic mientras grabas

---

## Instalación

### Mac (Intel o Apple Silicon)

1. Descarga el archivo `.dmg` más reciente desde [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest)
2. Ábrelo y arrastra **GamePerf** a Aplicaciones
3. La primera vez macOS bloqueará la aplicación por motivos de seguridad. Solución:
   - Abre **Preferencias del Sistema > Seguridad y Privacidad**
   - Haz clic en **«Abrir igualmente»** al lado del mensaje de GamePerf
   - Alternativa por terminal: `xattr -cr /Applications/GamePerf.app`

### Windows 10 / 11

1. Descarga el archivo `.msi` más reciente desde [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest)
2. Ejecuta el instalador
3. Windows Defender / SmartScreen mostrará una alerta porque el instalador no está firmado (es un falso positivo, la aplicación es código abierto):
   - Haz clic en **«Más información»**
   - Haz clic en **«Ejecutar de todas formas»**
4. Si tu antivirus lo borra, añade la carpeta de instalación (`C:\Program Files\GamePerf\`) a las exclusiones

### Linux

1. Descarga el JAR (`GamePerf-linux-x64-X.Y.Z.jar` o `linux-aarch64`) desde [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest)
2. Comprueba que tienes Java 17 o superior instalado: `java -version` debe mostrar `17` o más
3. Ejecuta: `java -jar GamePerf-linux-x64-X.Y.Z.jar`

---

## Primera sesión en 4 pasos

### Si tu teléfono es **Android**

1. **Activa la depuración USB**: en el teléfono, ve a *Ajustes > Información del teléfono > toca 7 veces sobre «Número de compilación»* para activar las Opciones de desarrollador. Después entra en *Ajustes > Opciones de desarrollador* y activa **«Depuración por USB»**
2. **Conecta el teléfono** al ordenador con un cable USB. Aparecerá una ventana emergente en el teléfono pidiendo autorización — acepta «Permitir siempre desde este PC»
3. **Abre GamePerf**. Tu teléfono debe aparecer en la lista «Dispositivos» en la pantalla principal. Si no aparece, comprueba que tienes ADB instalado (ver [preguntas frecuentes](#preguntas-frecuentes))
4. **Abre el juego que quieres medir** en el teléfono y en GamePerf pulsa **«Iniciar prueba»**. Elige una duración (por ejemplo, 1 minuto), juega con normalidad y, cuando termine, la aplicación te muestra el informe automáticamente

También puedes conectar el teléfono **sin cable** por WiFi (Android 11+): ver la sección [«Conexión WiFi»](#conexión-wifi-android-11) más abajo.

### Si tu teléfono es **iPhone / iPad**

1. **En Mac**: conecta el iPhone con un cable USB. Tienes que autorizar la conexión con el código del iPhone la primera vez («¿Confiar en este ordenador?»)
2. **En Windows**: instala iTunes (o «Apple Devices» en Windows 11) antes de conectar el iPhone. El sistema lo necesita para comunicarse con el dispositivo
3. **Abre GamePerf**. El iPhone aparecerá con una etiqueta azul en la lista de «Dispositivos». Si tu iPhone tiene iOS 16 o superior, la aplicación detecta automáticamente si necesita el Modo Desarrollador activado — la mayoría de las métricas funcionan sin él
4. **Abre el juego y pulsa «Iniciar prueba»**. Las diferencias entre lo que iOS puede capturar y lo que captura Android están explicadas en el informe (en particular: iOS no expone la temperatura de la carcasa ni la separación de memoria native/java)

---

## Plataformas soportadas

### Dispositivos que se pueden medir

| Plataforma | Métricas | Vídeo | Conexión |
|---|---|---|---|
| Android 5+ | Todas: FPS, CPU, memoria (native+java+total), temperatura (CPU+GPU+batería+carcasa), batería | Resolución nativa a 30/60 fps | USB o WiFi (Android 11+) |
| iOS 14+ | FPS, CPU, memoria (solo total), temperatura (CPU + batería), batería | 15 fps (Mac) / 8 fps «vista previa» (Windows) | Solo USB |

### Ordenador desde el que se ejecuta GamePerf

| Sistema | Instalador | Notas |
|---|---|---|
| macOS (Intel / Apple Silicon) | `.dmg` | Recomendado para iOS — captura de pantalla más fluida |
| Windows 10 / 11 | `.msi` | Requiere iTunes o «Apple Devices» instalado para dar soporte a iOS |
| Linux (x64 / ARM64) | `.jar` + Java 17 | Sin soporte para iOS (requiere SDK de Apple) |

### Conexión WiFi (Android 11+)

Permite grabar sin cable, lo cual es útil para medir el **consumo real de batería** (el cable USB la carga y falsea la medición). Hay dos caminos:

**Opción recomendada desde v3.2.0 — Emparejamiento directo sin haber usado USB nunca**:

1. Abre GamePerf sin conectar el cable
2. Pestaña «WiFi (Android 11+)» en el panel de dispositivos, o botón «+ Añadir dispositivo WiFi»
3. En el teléfono: *Opciones de desarrollador > Depuración inalámbrica > ON > Emparejar dispositivo con código*
4. El dispositivo aparece en la lista de la aplicación en menos de 3 segundos. Haz clic en él y escribe el código de 6 dígitos que muestra el teléfono
5. Las siguientes veces se reconecta solo — no hay que hacer nada

**Opción clásica — Cambio desde USB**:

1. Conecta el cable primero, espera a que aparezca el dispositivo
2. Pulsa «Cambiar a WiFi (medir batería real)»
3. Desconecta el cable cuando te avise. La prueba se ejecuta por WiFi

---

## Preguntas frecuentes

### No aparece mi teléfono Android en la lista

1. Comprueba que la **Depuración USB** esté activada en las Opciones de desarrollador
2. Acepta la ventana emergente de autorización en el teléfono cuando aparezca «¿Permitir depuración USB desde este PC?»
3. Comprueba que tienes **ADB** instalado y en el PATH:
   - **Mac**: `brew install android-platform-tools`, después en la terminal `adb version` debe mostrar la versión
   - **Windows**: descarga [Platform-Tools](https://developer.android.com/studio/releases/platform-tools), extrae el contenido a `C:\platform-tools\`, añade esa carpeta al PATH del sistema
4. Prueba con otro cable. Algunos cables USB son solo para carga y no transmiten datos

### No aparece mi iPhone

- **En Mac**: autoriza la ventana emergente «Trust this computer?» en el iPhone la primera vez
- **En Windows**: debes tener iTunes instalado (o la nueva «Apple Devices» en Windows 11). Sin eso, Windows no puede comunicarse con el iPhone
- **iOS 16 o superior**: no hace falta activar el Modo Desarrollador para la mayoría de las métricas. La aplicación detecta si está activo y adapta lo que puede capturar

### Windows Defender borra el instalador

Es un falso positivo (el instalador no está firmado con un certificado de code signing porque cuesta dinero). Soluciones:

1. **Para uso personal**: añade la carpeta de instalación como exclusión del antivirus
2. **Para equipos corporativos**: ver la sección [«Firma de código»](#firma-de-código-entorno-corporativo) en la documentación interna

### ¿Dónde se guardan los informes?

En `~/GamePerf Reports/` (Mac/Linux) o `C:\Users\<tu-usuario>\GamePerf Reports\` (Windows). Dentro encontrarás:

- `informe_*.html` — los informes generados
- `video_*.mp4` — los vídeos del gameplay
- `history.json` — índice del historial (no editar a mano)

Las últimas 5 sesiones se conservan automáticamente. Si quieres conservar una de forma permanente, **márcala como favorita** con la estrella, o **expórtala a PDF o `.gameperf`** antes de grabar nuevas sesiones.

### ¿Cómo actualizar la aplicación?

La aplicación comprueba las Releases de GitHub cada vez que arranca. Si hay una versión nueva, aparece un banner en la parte superior con un botón «Actualizar ahora». Lo puedes ignorar — la aplicación se actualizará sola al arrancar si hay una versión nueva disponible. Nada se pierde al actualizar: el historial, los favoritos y la configuración se conservan entre versiones.

### ¿Cómo comparto una sesión con un compañero?

Desde el historial, pulsa el botón **«Exportar .gameperf»** en la fila de la sesión. Se abre un diálogo nativo para elegir dónde guardar el archivo. El `.gameperf` resultante es un ZIP autocontenido con el informe HTML + todas las métricas — lo puedes enviar por cualquier medio (correo, Slack, carpeta compartida, USB). Tu compañero lo importa desde el botón **«Importar .gameperf»** del mismo historial y aparece como una sesión más.

### ¿Qué hago si la nota me parece injusta?

Desde v4.2.1, la puntuación tiene en cuenta el género del juego (casual / estrategia / RPG / acción / shooter). En la pantalla de configuración hay un desplegable para elegir el género antes de grabar. Un FPS medio de 30 es «A» para un juego de mesa, pero «D» para un shooter — eso ya está contemplado.

Desde v4.2.6, además, la nota es proporcional al **FPS objetivo del propio juego**. Un juego limitado intencionadamente a 30 fps que corre estable a 30 fps obtiene una A, no una D como en versiones anteriores.

Si sigues creyendo que la nota está mal, abre un [issue](https://github.com/zeroz3r0/android-game-perf-tool-desktop/issues) con la sesión exportada y lo revisamos.

---

## Compilar desde el código fuente

Para desarrolladores que quieran contribuir o compilar el binario desde cero:

### Requisitos

- **Java 17 o superior** (recomendado: [Temurin](https://adoptium.net/))
- **ADB** (Android Debug Bridge) instalado y en el PATH — solo si vas a probar contra dispositivos Android reales
- **Python 3.11 o superior** con `pymobiledevice3` — solo si vas a probar contra dispositivos iOS reales (en builds de release, esto se empaqueta como binario con PyInstaller)

### Comandos

```bash
# Clonar el repositorio
git clone https://github.com/zeroz3r0/android-game-perf-tool-desktop.git
cd android-game-perf-tool-desktop

# Ejecutar directamente sin instalar
./gradlew run        # Mac/Linux
.\gradlew.bat run    # Windows

# Ejecutar la batería de tests (más de 300 tests, ~1 min)
./gradlew test

# Generar el instalador para el SO actual
./gradlew packageDmg    # macOS .dmg
./gradlew packageMsi    # Windows .msi
./gradlew packageDeb    # Linux .deb

# Generar el JAR independiente (se ejecuta con `java -jar`)
./gradlew packageUberJarForCurrentOS
```

El proyecto incluye Gradle Wrapper — **no hace falta instalar Gradle aparte**.

### Pipeline de CI

El repositorio tiene 2 workflows de GitHub Actions:

- **CI** (`.github/workflows/ci.yml`): se ejecuta en cada push y PR a `main`. Lanza `detekt` + `test` + `compileKotlin`. Unos 2 minutos por ejecución en `ubuntu-latest`. Evita regresiones que antes solo se detectaban al generar una release
- **Release** (`.github/workflows/release.yml`): se activa al empujar un tag `v*`. Compila en `ubuntu`, `macos` y `windows` en paralelo, empaqueta el sidecar iOS con PyInstaller y publica una release en GitHub con todos los artefactos

Para publicar una release nueva:

1. Actualiza `appVersion` en `gradle.properties`
2. Añade una entrada en `CHANGELOG.md` con las 3 secciones (Qué hay de nuevo / Arreglos / Detalles técnicos)
3. Commit + push a `main`
4. `gh release create vX.Y.Z --title "vX.Y.Z" --notes-file <notas.md>` — esto dispara el workflow de release y publica los artefactos automáticamente

---

## Estructura del proyecto (para desarrolladores)

```
src/main/kotlin/com/gameperf/desktop/
├── Main.kt                         # Punto de entrada, ventana principal
├── core/
│   ├── AdbBridge.kt                # Comunicación ADB (capa low-level)
│   ├── AdbBridgeApi.kt             # Interfaz + RealAdbBridge (testable)
│   ├── AutoUpdater.kt              # Actualizador automático vía GitHub Releases
│   ├── DeviceNameResolver.kt       # SM-S911B -> «Samsung Galaxy S23» (v4.2.5)
│   ├── HardwareScoring.kt          # Clasificación de hardware + grading por dispositivo
│   ├── SessionHistory.kt           # Persistencia + retención + favoritos
│   ├── ToolResolver.kt             # Localización de ffmpeg/ffprobe multi-plataforma
│   ├── bridge/                     # Patrón bridge: Android + iOS + Composite
│   ├── ios/                        # Cliente del sidecar Python (iOS)
│   └── model/                      # Tipos platform-agnostic (Device, DeviceInfo, etc.)
├── viewmodel/
│   └── AppViewModel.kt             # Estado global, loop de captura, grading
├── report/
│   └── ReportGenerator.kt          # Generación HTML + PDF vía Playwright
├── cloud/
│   └── SessionPack.kt              # Formato .gameperf para compartir entre QA
└── ui/
    ├── theme/                      # Colores, tipografía
    ├── components/                 # Composables reutilizables (reproductor, línea de tiempo, diálogos)
    └── screens/                    # Pantallas principales (Home, Capture, Results, Comparison)

sidecar/                            # Sidecar Python para iOS (se empaqueta como binario)
├── pyproject.toml                  # Configuración del paquete Python
├── requirements.txt                # Dependencias directas
├── requirements-lock.txt           # Dependencias fijadas
├── gameperf_sidecar.spec           # Configuración de PyInstaller
├── gameperf_sidecar/               # Paquete principal
│   ├── __init__.py
│   ├── __main__.py                 # Permite ejecutar con `python -m gameperf_sidecar`
│   ├── main.py                     # FastAPI app + CLI entry point
│   ├── devices.py                  # /devices, /device/{udid}/info|apps|foreground-app
│   ├── metrics.py                  # /device/{udid}/metrics (DVT + Diagnostics)
│   └── screen_capture.py           # /device/{udid}/screenshot|screen-record/*
└── tests/
    ├── __init__.py
    └── test_contract.py            # 13 tests con FastAPI TestClient

src/test/                           # Más de 300 tests — kotlin.test + JUnit 4 (mixto)
.github/workflows/                  # Pipelines de CI + Release
docs/                               # PERFORMANCE_TESTING.md, BENCHMARK_TEMPLATE.md, tests manuales
```

### Arquitectura multi-plataforma

```
AppViewModel (fuente de verdad del estado de UI)
       |
       v
DeviceBridgeApi (interfaz multi-plataforma)
       |
       +--> CompositeBridge (enruta por Device.platform)
                |
                +--> AndroidBridge -> AdbBridgeApi -> AdbBridge (singleton, envuelve el subproceso adb)
                |
                +--> IosBridge -> SidecarClient (HTTP) -> sidecar Python -> pymobiledevice3
```

Toda la UI + el ViewModel hablan **únicamente con tipos de `core.model.*`** (multi-plataforma). Los bridges traducen a las APIs nativas de cada plataforma. Esto permite hacer tests con `FakeAdbBridge` / `FakeDeviceBridge` sin tocar ADB real.

---

## Contribuir

Las pull requests son bienvenidas. Para cambios grandes, abre primero un issue para discutirlo.

Convenciones:

- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`)
- **Tests**: el código nuevo viene con tests si tiene lógica. Los parsers puros son especialmente fáciles de testear sin mocks
- **CHANGELOG**: todo cambio con impacto visible para el usuario va al CHANGELOG en la sección correspondiente (Qué hay de nuevo / Arreglos). Los cambios puramente técnicos van en Detalles técnicos
- **Detekt**: la línea base está calibrada — las nuevas infracciones hacen fallar el CI. Si tu cambio requiere suprimir una regla, justifícalo con un comentario en el XML de la baseline

---

## Firma de código (entorno corporativo)

Para evitar que los antivirus y SmartScreen bloqueen el MSI en despliegues corporativos de Windows:

```bash
# Firmar con signtool (Windows SDK)
signtool sign /f certificado.pfx /p password /tr http://timestamp.digicert.com /td sha256 GamePerf-X.Y.Z.msi
```

Proveedores de certificados: DigiCert (~300 $/año), Sectigo / SSL.com (~70 $/año).

Alternativas sin certificado:

- **Exclusión en el antivirus**: añadir `C:\Program Files\GamePerf\` a las exclusiones
- **GPO corporativa**: lista blanca del MSI por hash

---

## Licencia

MIT — puedes usarlo, modificarlo y distribuirlo libremente. Ver [LICENSE](LICENSE) para más detalles.

---

## Créditos

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — framework de UI
- [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) — comunicación con iOS
- [FFmpeg](https://ffmpeg.org/) — codificación/decodificación de vídeo
- [Chart.js](https://www.chartjs.org/) — gráficos del informe HTML
- [Playwright](https://playwright.dev/) — generación de PDFs sin interfaz gráfica
