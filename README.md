# GamePerf Desktop

**Medi el rendimiento de tus juegos mobile con una app simple, sin scripts, sin terminal.**

Conecta tu telefono (Android o iPhone), apretá un boton, jugá el nivel que te interesa — la app te devuelve un informe con FPS, CPU, memoria, temperatura, video del gameplay, grafico interactivo y una nota (A/B/C/...) que te dice si el rendimiento es bueno o si hay algo para arreglar.

Gratis, open source, funciona en Mac, Windows y Linux.

---

## Indice

- [Para quien es](#para-quien-es)
- [Que hace](#que-hace)
- [Instalacion](#instalacion)
- [Primera sesion en 4 pasos](#primera-sesion-en-4-pasos)
- [Plataformas soportadas](#plataformas-soportadas)
- [Preguntas frecuentes](#preguntas-frecuentes)
- [Compilar desde fuente](#compilar-desde-fuente)
- [Estructura del proyecto](#estructura-del-proyecto-para-desarrolladores)
- [Contribuir](#contribuir)
- [Licencia](#licencia)

---

## Para quien es

| Perfil | Para que lo usa |
|---|---|
| **QA / Testers** | Validar que el juego corre estable antes de un build release. Detectar caidas de FPS, overheating, memory leaks durante sesiones largas |
| **Game designers** | Ver si un nivel o escena particular baja el rendimiento. Comparar dos builds lado a lado |
| **Product / Producer** | Compartir reportes con el equipo (PDF o link a Drive). Comparar tu juego con la competencia |
| **Developers** | Integrar en el flow de CI. Exportar datos para analisis. Debuggear regresiones de performance |

No hace falta saber ADB ni usar la terminal. Si alguna vez enchufaste un telefono a la compu, ya sabes lo suficiente.

---

## Que hace

### Captura en vivo

- **Metricas en tiempo real** durante la sesion: FPS, frame time (p1% / p50% / p99%), CPU, memoria (total / native / java), temperatura (CPU / GPU / bateria / skin), nivel de bateria
- **Grabacion de video** del gameplay en paralelo, usando el encoder de hardware del telefono (no afecta al FPS del juego que estas midiendo)
- **Marcadores durante la sesion**: apretar un boton para marcar el momento exacto cuando pasa algo (aparece un ad intersticial, cambia de escena, se carga algo pesado, etc.). Despues los podes revisar en el informe

### Analisis post-sesion

- **Informe HTML** con graficos interactivos, tabla de FPS por segundo, percentiles, y una seccion de "problemas detectados" con explicaciones en castellano (por ejemplo: "Temperatura llego a 48C en el minuto 3 — probable thermal throttling")
- **Reproductor de video integrado** con timeline sincronizado. Podes arrastrar el cursor del grafico de FPS y el video salta al mismo momento, para ver exactamente que estaba pasando cuando el FPS cayo
- **Nota automatica (S / A / B / C / D / F)** con grading justo por genero del juego (los thresholds para un shooter no son los mismos que para un puzzle casual)
- **Comparacion entre sesiones**: elegi 2 sesiones del historial y vealas lado a lado para comparar "build antes / despues", "tu juego / competencia", o "dispositivo A / dispositivo B"

### Colaboracion

- **Sincronizacion con Google Drive**: las sesiones grabadas se suben automaticamente a una carpeta compartida del equipo. Cualquiera con acceso las ve en su historial local sin mover archivos por Slack
- **Exportar a PDF** desde cualquier sesion o comparativa. El PDF queda fuera de la politica de auto-borrado del historial
- **Favoritos**: marca sesiones con estrella para que nunca se borren automaticamente

### Ayuda integrada

- **Guia in-app** (boton de libro arriba a la derecha): explica la metodologia de testing y que significa cada metrica. No hace falta ir a GitHub ni abrir documentacion externa — esta al alcance de 1 click mientras grabas

---

## Instalacion

### Mac (Intel o Apple Silicon)

1. Bajar el `.dmg` mas reciente de [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest)
2. Abrirlo y arrastrar **GamePerf** a Aplicaciones
3. La primera vez macOS te va a bloquear la app por seguridad. Solucion:
   - Ir a **Preferencias del Sistema > Seguridad y Privacidad**
   - Click en **"Abrir igualmente"** al lado del mensaje de GamePerf
   - Alternativa por terminal: `xattr -cr /Applications/GamePerf.app`

### Windows 10 / 11

1. Bajar el `.msi` mas reciente de [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest)
2. Ejecutar el instalador
3. Windows Defender / SmartScreen va a mostrar una alerta porque el instalador no esta firmado (es un falso positivo, la app es open source):
   - Click en **"Mas informacion"**
   - Click en **"Ejecutar de todas formas"**
4. Si tu antivirus lo borra, agrega la carpeta de instalacion (`C:\Program Files\GamePerf\`) a las exclusiones

### Linux

1. Bajar el JAR (`GamePerf-linux-x64-X.Y.Z.jar` o `linux-aarch64`) de [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest)
2. Asegurate de tener Java 17+ instalado: `java -version` deberia mostrar `17` o mas
3. Ejecutar: `java -jar GamePerf-linux-x64-X.Y.Z.jar`

---

## Primera sesion en 4 pasos

### Si tu telefono es **Android**

1. **Activa Depuracion USB**: en el telefono, andá a *Ajustes > Informacion del telefono > tocar 7 veces "Numero de compilacion"* para activar Opciones de desarrollador. Despues entrá a *Ajustes > Opciones de desarrollador* y activá **"Depuracion por USB"**
2. **Conecta el telefono** a la compu con un cable USB. Va a aparecer un popup en el telefono pidiendo autorizacion — acepta "Siempre permitir desde esta PC"
3. **Abri GamePerf**. Tu telefono tiene que aparecer en la lista "Dispositivos" en la pantalla principal. Si no aparece, asegurate de tener ADB instalado (ver [FAQ](#preguntas-frecuentes))
4. **Abri el juego que queres medir** en el telefono, y en GamePerf apretá **"Iniciar prueba"**. Elegi una duracion (ej. 1 minuto), jugá normalmente, y cuando termina la app te tira el informe automaticamente

Tambien podes conectar el telefono **sin cable** por WiFi (Android 11+): ver seccion ["Conexion WiFi"](#conexion-wifi-android-11) abajo.

### Si tu telefono es **iPhone / iPad**

1. **En Mac**: conecta el iPhone con cable USB. Tenes que autorizar la conexion con el codigo en el iPhone la primera vez ("Confiar en esta computadora?")
2. **En Windows**: instala iTunes (o "Apple Devices" en Windows 11) antes de conectar el iPhone. El sistema lo necesita para comunicarse con el device
3. **Abri GamePerf**. El iPhone va a aparecer con badge azul en la lista "Dispositivos". Si tu iPhone es iOS 16+, la app detecta automaticamente si necesita Developer Mode habilitado — la mayoria de las metricas funcionan sin DM
4. **Abri el juego y apretá "Iniciar prueba"**. Las diferencias de lo que iOS puede capturar vs Android estan explicadas en el informe (en particular: iOS no expone skin temp ni el split de memoria native/java)

---

## Plataformas soportadas

### Dispositivos que podes medir

| Plataforma | Metricas | Video | Conexion |
|---|---|---|---|
| Android 5+ | Todas: FPS, CPU, memoria (native+java+total), temperatura (CPU+GPU+bateria+skin), bateria | Resolucion nativa a 30/60 fps | USB o WiFi (Android 11+) |
| iOS 14+ | FPS, CPU, memoria (solo total), temperatura (CPU + bateria), bateria | 15 fps (Mac) / 8 fps "vista previa" (Windows) | Solo USB |

### Compu desde donde corre GamePerf

| Sistema | Instalador | Notas |
|---|---|---|
| macOS (Intel / Apple Silicon) | `.dmg` | Recomendado para iOS — captura de pantalla mas fluida |
| Windows 10 / 11 | `.msi` | Requiere iTunes o "Apple Devices" instalado para soportar iOS |
| Linux (x64 / ARM64) | `.jar` + Java 17 | Sin soporte iOS (requiere Apple SDK) |

### Conexion WiFi (Android 11+)

Permite grabar sin cable, lo cual es util para medir **consumo real de bateria** (el cable USB la carga y falsea la medicion). Dos caminos:

**Opcion recomendada desde v3.2.0 — Pareo directo sin haber usado USB nunca**:
1. Abri GamePerf sin conectar el cable
2. Tab "WiFi (Android 11+)" del panel de dispositivos, o boton "+ Agregar device WiFi"
3. En el telefono: *Opciones de desarrollador > Depuracion inalambrica > ON > Emparejar dispositivo con codigo*
4. El dispositivo aparece en la lista de la app en <3 segundos. Clickealo, tipea el codigo de 6 digitos del popup del telefono, listo
5. Las siguientes veces se reconecta solo — no hay que hacer nada

**Opcion clasica — Cambio desde USB**:
1. Conecta el cable primero, espera que aparezca el device
2. Click en "Cambiar a WiFi (medir bateria real)"
3. Desconecta el cable cuando te avise. La prueba corre via WiFi

---

## Preguntas frecuentes

### No aparece mi telefono Android en la lista

1. Verifica que **Depuracion USB** este activada en Opciones de desarrollador
2. Aceptá el popup de autorizacion en el telefono cuando aparece "Permitir depuracion USB desde esta PC?"
3. Verifica que tenes **ADB** instalado y en el PATH:
   - **Mac**: `brew install android-platform-tools`, despues en terminal `adb version` tiene que mostrar la version
   - **Windows**: bajar [Platform-Tools](https://developer.android.com/studio/releases/platform-tools), extraer a `C:\platform-tools\`, agregar esa carpeta al PATH del sistema
4. Probar con otro cable. Algunos cables USB son solo para carga y no transmiten datos

### No aparece mi iPhone

- **En Mac**: autoriza el popup "Trust this computer?" en el iPhone la primera vez
- **En Windows**: tenes que tener iTunes instalado (o la nueva "Apple Devices" en Windows 11). Sin eso, Windows no puede hablar con el iPhone
- **iOS 16+**: no hace falta activar Developer Mode para la mayoria de las metricas. La app detecta si esta activado y adapta lo que puede capturar

### Windows Defender borra el instalador

Es un falso positivo (el instalador no esta firmado con certificado de code signing porque cuesta plata). Soluciones:
1. **Para uso personal**: agrega la carpeta de instalacion como exclusion del antivirus
2. **Para equipos corporativos**: ver seccion ["Firma de codigo"](#firma-de-codigo-entorno-corporativo) en la documentacion interna

### Donde se guardan los reportes?

En `~/GamePerf Reports/` (Mac/Linux) o `C:\Users\<tu-user>\GamePerf Reports\` (Windows). Dentro vas a encontrar:
- `informe_*.html` — los reportes generados
- `video_*.mp4` — los videos del gameplay
- `history.json` — indice del historial (no editar a mano)

Las ultimas 5 sesiones se preservan automaticamente. Si queres retener una permanentemente, **marcala como favorita** con la estrella, o **exportala a PDF** antes de grabar nuevas sesiones.

### Como actualizar la app?

La app chequea GitHub Releases cada vez que arranca. Si hay version nueva, aparece un banner arriba con un boton "Actualizar ahora". Podes ignorarlo — las proximas veces la app se actualiza sola al iniciar si hay version nueva disponible. Nada se pierde al actualizar: el historial, favoritos, tokens de Drive, y configuracion se preservan entre versiones.

### Que hago si el grading me parece injusto?

Desde v4.2.1 el grading usa thresholds por genero del juego (casual / estrategia / RPG / action / shooter). En la pantalla de setup hay un dropdown para elegir el genero antes de grabar. Un FPS promedio de 30 es "A" para un juego de mesa, pero "D" para un shooter — eso ya esta contemplado.

Si seguis creyendo que el grading esta off, abri un [issue](https://github.com/zeroz3r0/android-game-perf-tool-desktop/issues) con la sesion exportada y te lo revisamos.

---

## Compilar desde fuente

Para desarrolladores que quieran contribuir o compilar el binario desde cero:

### Requisitos

- **Java 17+** (recomendado: [Temurin](https://adoptium.net/))
- **ADB** (Android Debug Bridge) instalado y en el PATH — solo si vas a testear contra devices Android reales
- **Python 3.11+** con `pymobiledevice3` — solo si vas a testear contra devices iOS reales (en release builds esto se bundlea como binary con PyInstaller)

### Comandos

```bash
# Clonar el repo
git clone https://github.com/zeroz3r0/android-game-perf-tool-desktop.git
cd android-game-perf-tool-desktop

# Correr directo sin instalar
./gradlew run        # Mac/Linux
.\gradlew.bat run    # Windows

# Correr la suite de tests (258 tests, ~1 min)
./gradlew test

# Generar el instalador para el SO actual
./gradlew packageDmg    # macOS .dmg
./gradlew packageMsi    # Windows .msi
./gradlew packageDeb    # Linux .deb

# Generar el JAR standalone (ejecuta con `java -jar`)
./gradlew packageUberJarForCurrentOS
```

El proyecto incluye Gradle Wrapper — **no hace falta instalar Gradle aparte**.

### Pipeline de CI

El repo tiene 2 workflows de GitHub Actions:
- **CI** (`.github/workflows/ci.yml`): corre en cada push y PR a `main`. Ejecuta `detekt` + `test` + `compileKotlin`. ~2 min por run
- **Release** (`.github/workflows/release.yml`): se dispara al pushear un tag `v*`. Buildea en `ubuntu`, `macos` y `windows` en paralelo, empaqueta el sidecar iOS con PyInstaller, y publica un release en GitHub con todos los artefactos

Para hacer un release nuevo:
1. Bumpear `appVersion` en `gradle.properties`
2. Agregar entrada en `CHANGELOG.md` con las 3 secciones (Que hay de nuevo / Arreglos / Detalles tecnicos)
3. Commit + push a `main`
4. `gh release create vX.Y.Z --title "vX.Y.Z" --notes-file <notes.md>` — eso dispara el workflow de release y publica los artefactos automaticamente

---

## Estructura del proyecto (para desarrolladores)

```
src/main/kotlin/com/gameperf/desktop/
├── Main.kt                         # Entry point, ventana principal
├── core/
│   ├── AdbBridge.kt                # Comunicacion ADB (layer low-level)
│   ├── AdbBridgeApi.kt             # Interface + RealAdbBridge (testable)
│   ├── AutoUpdater.kt              # Auto-actualizador via GitHub Releases
│   ├── HardwareScoring.kt          # Clasificacion de hardware + grading por device
│   ├── SessionHistory.kt           # Persistencia + retention + favoritos
│   ├── bridge/                     # Bridge pattern: Android + iOS + Composite
│   ├── ios/                        # Cliente del sidecar Python (iOS)
│   └── model/                      # Tipos platform-agnostic (Device, DeviceInfo, etc.)
├── viewmodel/
│   └── AppViewModel.kt             # Estado global, loop de captura, grading
├── report/
│   └── ReportGenerator.kt          # Generacion HTML + PDF via Playwright
├── cloud/
│   └── DriveSync.kt                # Sincronizacion con Google Drive
└── ui/
    ├── theme/                      # Colores, tipografia
    ├── components/                 # Composables reutilizables (video player, timeline, dialogs)
    └── screens/                    # Pantallas principales (Home, Capture, Results, Comparison)

sidecar/                            # Sidecar Python iOS (se bundlea como binary)
├── gameperf_sidecar.py             # Entry point FastAPI
├── ios_client.py                   # Wrapper de pymobiledevice3
└── gameperf_sidecar.spec           # PyInstaller config

src/test/                           # 258 tests — kotlin.test + JUnit 4 (mixed)
.github/workflows/                  # CI + Release pipelines
docs/                               # PERFORMANCE_TESTING.md, BENCHMARK_TEMPLATE.md, manual tests
```

### Arquitectura cross-platform

```
AppViewModel (single source of truth de UI state)
       |
       v
DeviceBridgeApi (interface platform-agnostic)
       |
       +--> CompositeBridge (routes by Device.platform)
                |
                +--> AndroidBridge -> AdbBridgeApi -> AdbBridge (singleton, wraps adb subprocess)
                |
                +--> IosBridge -> SidecarClient (HTTP) -> sidecar Python -> pymobiledevice3
```

Toda la UI + ViewModel habla **solo con tipos de `core.model.*`** (platform-agnostic). Los bridges traducen a las APIs nativas de cada plataforma. Eso permite testear con `FakeAdbBridge` / `FakeDeviceBridge` sin tocar adb real.

---

## Contribuir

Pull requests bienvenidas. Para cambios grandes abrí un issue primero para discutir.

Convenciones:
- **Commits**: [Conventional Commits](https://www.conventionalcommits.org/) (`feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `test:`)
- **Tests**: nuevo codigo viene con tests si tiene logica. Los parsers puros son especialmente faciles de testear sin mocks
- **CHANGELOG**: todo cambio con impacto al usuario va al CHANGELOG en la seccion correspondiente (Que hay de nuevo / Arreglos). Los cambios solo tecnicos van en Detalles tecnicos
- **Detekt**: el baseline esta calibrado — violaciones nuevas hacen fallar el CI. Si tu cambio requiere suprimir una regla, justificalo con comentario en el baseline XML

---

## Firma de codigo (entorno corporativo)

Para evitar que antivirus y SmartScreen bloqueen el MSI en despliegues corporativos de Windows:

```bash
# Firmar con signtool (Windows SDK)
signtool sign /f certificado.pfx /p password /tr http://timestamp.digicert.com /td sha256 GamePerf-X.Y.Z.msi
```

Proveedores de certificados: DigiCert (~$300/año), Sectigo / SSL.com (~$70/año).

Alternativas sin certificado:
- **Antivirus exclusion**: agregar `C:\Program Files\GamePerf\` a las exclusiones
- **GPO corporativa**: whitelist del MSI por hash

---

## Licencia

MIT — podes usar, modificar, distribuir libremente. Ver [LICENSE](LICENSE) para detalles.

---

## Creditos

- [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform) — framework de UI
- [pymobiledevice3](https://github.com/doronz88/pymobiledevice3) — comunicacion iOS
- [FFmpeg](https://ffmpeg.org/) — encoding/decoding de video
- [Chart.js](https://www.chartjs.org/) — graficos del reporte HTML
- [Playwright](https://playwright.dev/) — generacion de PDFs headless
