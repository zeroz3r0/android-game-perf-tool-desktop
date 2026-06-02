# Guía rápida de uso

> Para empezar a medir el rendimiento de tu juego en 5 minutos. Sin scripts, sin terminal.

Esta guía es para QA, diseñadores de juego y product managers. Si buscas detalle técnico de arquitectura o quieres compilar el código, lee [README.md](README.md).

---

## Índice

- [Antes de empezar](#antes-de-empezar)
- [Tu primera captura](#tu-primera-captura)
- [Cómo leer el informe](#cómo-leer-el-informe)
- [Casos de uso comunes](#casos-de-uso-comunes)
- [Problemas comunes](#problemas-comunes)
- [Consejos](#consejos)

---

## Antes de empezar

### Requisitos

| Si vas a medir | Necesitas en el ordenador |
|---|---|
| Android | ADB (Android Platform-Tools) en el PATH |
| iPhone / iPad en Mac | Nada extra — el sistema ya lleva lo necesario |
| iPhone / iPad en Windows | iTunes o «Apple Devices» (Microsoft Store) |
| iPhone / iPad en Linux | No soportado |

**Comprueba que tienes ADB**:

```powershell
adb version
```

Si te dice «no se reconoce el comando», instálalo:

- **Windows**: descarga [Platform-Tools](https://developer.android.com/studio/releases/platform-tools), extrae en `C:\platform-tools\` y añade esa carpeta al PATH del sistema.
- **macOS**: `brew install android-platform-tools`.
- **Linux**: `sudo apt install android-tools-adb` (o equivalente de tu distro).

### Instalación

Descarga el instalador para tu sistema desde [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest):

- **Windows**: archivo `.msi`. Windows SmartScreen mostrará un aviso porque el instalador no está firmado — pulsa «Más información» > «Ejecutar de todas formas».
- **macOS**: archivo `.dmg`. La primera vez macOS bloqueará la app — ve a *Preferencias del Sistema > Seguridad y Privacidad > «Abrir igualmente»*.
- **Linux**: el `.jar` con Java 17+ (`java -jar GamePerf-linux-x64-X.Y.Z.jar`).

Al arrancar la primera vez, GamePerf crea la carpeta `~/GamePerf Reports/` (o `C:\Users\<tu-usuario>\GamePerf Reports\` en Windows) donde guarda informes, vídeos y el historial.

---

## Tu primera captura

### 1. Conecta el dispositivo

**Android (USB)**:

1. En el teléfono: *Ajustes > Información del teléfono > toca 7 veces «Número de compilación»*. Esto activa las Opciones de desarrollador.
2. *Ajustes > Opciones de desarrollador > Depuración por USB*: activar.
3. Conecta el cable USB. Acepta el diálogo «¿Permitir depuración USB desde este PC?» en el móvil — marca «Permitir siempre».

**Android (WiFi, sin cable, requiere Android 11+)**:

Solo tiene sentido si quieres medir el consumo real de batería. El cable USB la carga y falsea esa métrica.

1. En GamePerf, en el panel «Dispositivo», pulsa la pestaña **WiFi (Android 11+)**.
2. En el móvil: *Opciones de desarrollador > Depuración inalámbrica > Emparejar dispositivo con código*.
3. Selecciona tu dispositivo en la lista de GamePerf y escribe el código de 6 dígitos que muestra el móvil.

**iPhone / iPad**:

- En Mac: conecta por USB. Autoriza «¿Confiar en este ordenador?» con el código del iPhone la primera vez.
- En Windows: instala iTunes o «Apple Devices» **antes** de conectar el iPhone.
- iOS 16+: la mayoría de las métricas funcionan sin activar el Modo Desarrollador.

### 2. Comprueba que aparece el dispositivo

En la pantalla principal, panel **Dispositivo** (arriba a la izquierda). Tu móvil debe aparecer en la lista. Si no aparece, ve a [«El dispositivo no aparece»](#el-dispositivo-no-aparece).

### 3. Abre el juego en el móvil

Lánzalo a mano desde el dispositivo. GamePerf detecta automáticamente el juego que está en primer plano y lo muestra en el panel **Juego** (arriba a la derecha).

Si dice «No se detectó juego», pulsa el **icono circular de refrescar (⟳)** que aparece al lado del título «Juego», después de abrir el juego en el móvil.

### 4. Elige la duración y arranca

En el panel central:

1. Escribe la **Duración de la prueba** en segundos. Recomendado: empieza con `60` (1 minuto).
2. Elige el **Tipo de sesión**:
   - **Nuestro juego** (por defecto): para tu propio juego.
   - **Competencia**: para benchmarks contra otros juegos. Pide el nombre del competidor.
3. Pulsa el botón azul **Iniciar prueba** (o naranja **Capturar competencia** si elegiste competencia).

### 5. Juega normal

Durante la captura verás la pantalla **CAPTURANDO** en rojo con métricas en directo: FPS, frame time, CPU%, memoria, batería, temperatura.

Mientras juegas puedes pulsar **marcadores** para señalar momentos clave:

- **Intersticial** — cuando aparece un anuncio a pantalla completa.
- **Video Reward** — cuando ves un anuncio de vídeo recompensado.
- **Carga** — cuando hay una pantalla de carga larga.
- **Cambio escena** — cuando el juego cambia de nivel o escenario.
- **Nota +** — añade un comentario libre.

> Los marcadores son un respaldo. Desde v4.4.0 GamePerf detecta automáticamente anuncios (AdMob, Unity Ads, IronSource, AppLovin/MAX, Meta), IAPs (Google Play Billing) y pantallas de carga sin que tengas que tocar nada.

### 6. Para y mira el informe

Cuando se acabe la duración, GamePerf se detiene solo y abre el informe automáticamente. Si quieres parar antes, pulsa **Detener** (botón rojo arriba). Te pedirá confirmación.

Después de unos segundos (procesa vídeo + métricas), te lleva a la pantalla de **Resultados**.

---

## Cómo leer el informe

La pantalla de Resultados tiene tres partes:

### Reproductor de vídeo con línea de tiempo

A la izquierda. Arrastra el cursor sobre el gráfico de FPS y el vídeo salta al mismo instante. Así ves exactamente qué ocurría cuando cayó el rendimiento.

Botones: **Reproducir / Pausar**, **Retroceder 5s**, **Avanzar 5s**, **Abrir externo** (en VLC u otro reproductor).

### Métricas principales

A la derecha. Cada tarjeta lleva una **banda de color**: verde (Bien) / ámbar (Atención) / rojo (Mal), con forma (●/▲/■) además del color — para que sea legible también en blanco y negro o si tienes daltonismo.

| Métrica | Qué mide |
|---|---|
| **FPS** | Frames por segundo. Banner verde debajo te dice el objetivo detectado del juego (30, 45, 60, 90 o 120) y por qué |
| **Frame Time** | Tiempo medio por frame en ms. Mejor cuanto más bajo |
| **Jank** | Frames que tardan más de 1,5 × el objetivo. Mide los tirones que el jugador siente |
| **CPU %** | Uso de CPU **del proceso del juego** (no del sistema entero) |
| **Memoria** | Pico de RAM en MB |
| **Temperatura** | CPU, GPU, batería y piel del dispositivo. Si pasa de 45 °C el juego sufre thermal throttling |
| **Batería** | Drenaje durante la captura. Solo es fiable midiendo por WiFi sin cable |
| **FPower** (Android) | mW por frame — eficiencia energética. Detecta regresiones que el FPS solo no muestra |
| **GPU %** (Android) | En chipsets Mali (ARM) y Adreno (Qualcomm). Banner explicativo si no se pudo medir |
| **Red** (Android) | Bytes RX/TX consumidos durante la captura |
| **Wake locks** (Android) | Tiempo con CPU activa y pantalla apagada. Si pasa de 2 h, Google Play Vitals te penaliza |

Arriba a la derecha verás la **Nota** automática (S / A / B / C / D / F). La nota tiene en cuenta el género del juego y el FPS objetivo real del propio juego — un juego limitado intencionadamente a 30 fps que rinde estable obtiene A, no D.

### Eventos detectados y conclusiones

Debajo del bloque de métricas, dos secciones:

- **Eventos detectados** — tabla con anuncios, IAPs y cargas que GamePerf identificó automáticamente. Los rangos de anuncios y cinemáticas se **excluyen** de las medias del juego, así que las métricas reflejan el juego real, no la mezcla.
- **Conclusiones** — recomendaciones cualitativas accionables: distingue cuello de botella en código vs. thermal throttling vs. fuga de memoria, por ejemplo.

### Problemas detectados

Tarjeta con lista de issues (caídas de FPS, picos térmicos, fugas de memoria). Cada uno con una explicación en castellano.

---

## Casos de uso comunes

### Comparar dos builds del mismo juego

1. Captura la **build A**.
2. Captura la **build B**.
3. En el historial (panel **Sesiones**), marca la **casilla morada (checkbox)** que aparece a la izquierda de cada sesión que quieras comparar. Verás «2 seleccionadas».
4. Pulsa **Comparar sesiones**.

> No confundas la casilla con la **estrella** que hay justo al lado: la estrella marca la sesión como favorita (no se borra por retención), la casilla la añade a la comparativa.

Te abre la pantalla de comparativa con métricas lado a lado y el delta entre build A y B.

### Definir objetivos por juego (Game Targets)

Útil cuando quieres que cada juego del catálogo tenga sus propios umbrales (un casual a 30 fps no es lo mismo que un shooter a 60).

1. En la cabecera de la pantalla principal, pulsa el icono de la rueda dentada (**Editar objetivos del juego**).
2. Se abre un editor con tabla. Añade entradas con el `packageId` del juego (por ejemplo `com.tu.estudio.tujuego`) y rellena los KPIs que te importen (FPS medio, p1, frame time, temperaturas, RAM, CPU, FPower, drenaje). Todos son opcionales.
3. Pulsa **Guardar**.

A partir de la siguiente captura, el informe muestra una sección **Objetivos del juego** con tarjetas comparando lo medido contra el objetivo (verde / ámbar / rojo).

Desde el editor también puedes **Exportar a HTML** para compartir el catálogo con el equipo o imprimirlo a PDF desde el navegador (Ctrl+P).

### Compartir una sesión con un compañero

Dos opciones, según lo que necesite el receptor:

| Quieres compartir | Botón |
|---|---|
| Sesión completa (importable en su GamePerf) | **Exportar .gameperf** en la fila del historial |
| Solo el informe HTML | **Compartir reporte** (abre la carpeta + copia descripción al portapapeles) |
| Pegar HTML en chat o correo, sin archivo | Icono **Copiar HTML como data URL** (límite 5 MB) |

El `.gameperf` es un ZIP autocontenido con HTML + métricas + vídeo. El receptor importa desde **Importar .gameperf** en su historial y aparece como una sesión más.

### Guardar sesiones permanentemente

GamePerf conserva las últimas 5 sesiones automáticamente y borra las viejas. Para conservar una de forma permanente:

- Marca la sesión como **favorita** (estrella). Las favoritas no se borran nunca.
- O **expórtala** a `.gameperf` o PDF antes de capturar nuevas.

---

## Problemas comunes

### El dispositivo no aparece

1. Comprueba que la depuración USB está activada (Android).
2. Acepta el diálogo de autorización en el móvil.
3. Comprueba ADB:
   ```powershell
   adb devices
   ```
   Si tu dispositivo aparece como `unauthorized`, vuelve a aceptar el diálogo en el móvil. Si aparece como `offline`, desconecta y vuelve a conectar.
4. Prueba con otro cable USB. Hay cables solo-carga que no transmiten datos.
5. En Windows, si compraste un móvil chino o poco común, instala los **drivers OEM** del fabricante.

### El vídeo solo se grabó parcialmente

Si paraste la sesión antes de los primeros ~3 minutos, es normal — el primer segmento aún no se había transferido del móvil. El informe te avisa con un banner rojo arriba.

Otras causas: cable USB movido, móvil en suspensión, el sistema rechazó `screenrecord`. Repite la captura con el móvil estable y la pantalla siempre activa.

### «No hay JAR disponible para tu plataforma» al actualizar

Desde v4.2.10 esto no debería pasar — el AutoUpdater filtra releases sin binarios listos. Si te aparece igualmente, espera unos minutos (puede ser una release recién publicada con los binarios todavía compilando) y vuelve a pulsar Actualizar. Si persiste, descarga manualmente desde [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases/latest).

### Caracteres raros (`â€"`, `Ã±`) en el banner o el informe

Bug arreglado en v4.2.4. Actualiza a la versión más reciente desde el banner de actualización.

### Windows Defender borra el instalador

Falso positivo. El MSI no está firmado con certificado de code signing (cuesta dinero). Soluciones:

- **Uso personal**: añade `C:\Program Files\GamePerf\` a las exclusiones del antivirus.
- **Equipo corporativo**: pide al admin de IT que firme el MSI con vuestro certificado interno o que lo añada a la lista blanca por hash.

### La nota me parece injusta

Desde v4.2.1 la puntuación tiene en cuenta el género del juego (elígelo en el panel principal antes de capturar). Desde v4.2.6, además, la nota es proporcional al **FPS objetivo del propio juego**. Un casual a 30 fps estables saca A, no D.

Si sigues creyendo que la nota está mal, abre un [issue](https://github.com/zeroz3r0/android-game-perf-tool-desktop/issues) adjuntando el `.gameperf` exportado.

---

## Consejos

- **Sesiones cortas para iterar, largas para validar**. 1 minuto te basta para detectar regresiones obvias. Para validar antes de publicar, mide 10-15 minutos seguidos para que aflore el thermal throttling.
- **WiFi solo cuando midas batería**. Para todo lo demás, USB es más estable.
- **Marca como favorita tu sesión baseline** antes de cualquier optimización. Así puedes comparar build optimizada vs. baseline sin que se borre por la retención automática.
- **El reporte HTML es autónomo**. Lo puedes abrir en cualquier navegador sin la app, e imprimirlo a PDF con Ctrl+P (los colores se ajustan automáticamente para imprimir).
- **Guía dentro de la app**: pulsa el botón cian **«Guía de testing»** (icono de libro 📖) en la esquina superior izquierda de la pantalla principal para ver explicaciones de la metodología y de cada métrica, sin salir de la app.

---

## Más información

- [README.md](README.md) — visión general del proyecto y plataformas soportadas.
- [README_EN.md](README_EN.md) — same content in English.
- [CHANGELOG.md](CHANGELOG.md) — qué cambió en cada versión.
- [Releases](https://github.com/zeroz3r0/android-game-perf-tool-desktop/releases) — descargas y notas de versión.
- [Issues](https://github.com/zeroz3r0/android-game-perf-tool-desktop/issues) — reporta bugs o sugiere mejoras.
