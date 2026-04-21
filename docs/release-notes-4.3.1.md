## Arreglos

- **adb ya se detecta en todas las instalaciones de Android Studio en Windows**: antes, si tenías Android Studio instalado con sus paths por defecto (`%LOCALAPPDATA%\Android\Sdk\platform-tools\`) y adb no estaba en el PATH del sistema, la app caía a invocar el comando `adb` pelado y fallaba silenciosamente al listar dispositivos. El único path hardcodeado de Windows era `C:\platform-tools\adb.exe` (instalación manual del zip standalone), que es muchísimo menos común. Ahora se cubren todas las rutas de instalación mainstream: Android Studio en Windows/macOS/Linux, Homebrew casks (Intel + Apple Silicon), y paquetes de distros Linux (Debian android-tools-adb, Arch android-tools).
- **ffprobe en Windows**: la detección para validar videos en iOS usaba la misma lógica rota que adb (`which ffprobe` — no-op en Windows — y solo tres paths Unix). Usuarios de Windows con ffprobe instalado vía WinGet, Scoop o Chocolatey degradaban al fallback de validación por tamaño (`file.length() > 1024`), que deja pasar MP4s corruptos. Ahora delega en `ToolResolver` igual que ffmpeg.

## Detalles tecnicos

- `ToolResolver.kt` extendido con `toolSpecificCandidates(tool, exeName, isWindows)` — función pura que despacha a tablas por herramienta. Para `adb` enumera Android Studio (Win/Mac/Linux), Homebrew casks, distros Linux y el zip standalone. Devuelve lista vacía para tools sin tabla específica, así ffmpeg/ffprobe siguen usando el `candidatesFor` genérico.
- `AdbBridge.adbPath` reemplazado por `ToolResolver.find("adb")`: 20 líneas de detección manual reducidas a una sola llamada. El patrón `ProcessBuilder("which", "adb")` + lista plana de paths era el MISMO bug que motivó `ToolResolver` en v4.2.3 para ffmpeg, documentado en `CLAUDE.md`. La lección no se había aplicado consistentemente.
- `IosBridge.findFfprobe` reemplazado por `ToolResolver.find("ffprobe")`: tercera copia del mismo patrón roto, eliminada. Ahora Windows users con ffprobe via WinGet/Scoop/Chocolatey obtienen validación real en lugar del fallback por tamaño.
- 9 tests nuevos en `ToolResolverTest` cubriendo `adbCandidates` (Windows/Unix), `toolSpecificCandidates` (dispatch), y smoke test de `find("adb")`. Todos puros — no spawnean `where`/`which`, solo validan las listas de candidatos en memoria, así que corren idénticos en CI Linux y en dev Windows/Mac.
- `CLAUDE.md` actualizado con una regla operativa explícita: cualquier nueva herramienta externa debe pasar por `ToolResolver.find` desde el primer commit. No hand-roll. Esta fue la segunda vez que el mismo patrón se escapó.
- Tests totales: 319 → **328**.
- Version bump: `4.3.0` → `4.3.1`. Patch.
