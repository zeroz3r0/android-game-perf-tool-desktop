# Proposal: in-app dependency bootstrap

## Intent

Eliminar la dependencia externa de que el usuario instale `adb` y `ffmpeg` manualmente vía scoop/chocolatey/Homebrew. La app debe ofrecer instalación automática dentro del directorio de datos del usuario, con fallback manual si falla la descarga.

## Scope

### In Scope
- Bundle `adb` (~10 MB) en MSI/DMG/Deb — primer día offline funcional para Android
- Descarga on-demand de `ffmpeg` al primer intento de grabación (no al startup)
- Componente `UserToolsDir` con rutas por-OS: `%LOCALAPPDATA%\GamePerf\tools\`, `~/Library/Application Support/GamePerf/tools/`, `~/.local/share/GamePerf/tools/`
- Extender `ToolResolver.find` con candidate user-writable como step 0
- `DependencyBootstrap` que corre en `AppViewModel.init` junto al check de adb
- `ToolInstaller` con extracción ZIP, verificación SHA256 (best-effort), chmod Unix
- Banner Compose estilo UpdateBanner ("Descargar dependencias" CTA + "Después" secondary)
- Tests puros: extender `ToolResolverTest`, nuevos `DependencyBootstrapTest`, `ToolInstallerTest`

### Out of Scope
- iOS sidecar Python (ya viene bundleado vía PyInstaller)
- Actualización automática de tools bundled (manual bump por ahora)
- Linux packages (scope limitado a Windows + macOS primer slice)

## Approach

Seguir **Opción C + D** de la exploración:

1. **Bundled adb**: CI descarga platform-tools en build-time, se incluye en jpackage resources. `ToolResolver` busca primero en `<installDir>/tools/` (step 0 nuevo).

2. **Download ffmpeg on-demand**: cuando user intenta grabar video y `ToolResolver.find("ffmpeg") == null`, mostrar banner. Botón "Descargar dependencias" invoca `ToolInstaller.download(url, targetDir, sha256?)`. Fallback: "Abrir en navegador" con URL oficial.

3. **Reutilizar patrones existentes**: extraer `Downloader` de `AutoUpdater` (buffer 8192, progress callback, temp file). Clonar `UpdateDelegate` → `BootstrapDelegate`.

4. **SHA256 estrategia**: best-effort. Gyan publica hash para ffmpeg → verificar. Google no publica para platform-tools → verificar solo tamaño mínimo y confiar en HTTPS.

## Affected Areas

| Area | Impact | Description |
|------|--------|-------------|
| `core/ToolResolver.kt` | Modified | Agregar user-writable candidate como step 0 |
| `core/AdbBridge.kt` | Modified | Sin cambios (ya usa ToolResolver) |
| `core/DependencyBootstrap.kt` | New | Check agregado + estado + download orchestrator |
| `core/ToolInstaller.kt` | New | Download, extract, verify, chmod |
| `core/UserToolsDir.kt` | New | Rutas por-OS para tools |
| `viewmodel/AppViewModel.kt` | Modified | StateFlows missingDeps, bootstrapProgress, bootstrapError; invoke en init() |
| `ui/screens/HomeScreen.kt` | Modified | DepsBootstrapBanner Composable |
| `core/AutoUpdater.kt` | Modified | Extraer Downloader reutilizable |
| `test/core/ToolResolverTest.kt` | Extended | Tests user-writable candidate |
| `test/core/DependencyBootstrapTest.kt` | New | Tests comportamiento bootstrap |
| `test/core/ToolInstallerTest.kt` | New | Tests extracción y verificación |

## Risks

| Risk | Likelihood | Mitigation |
|------|------------|------------|
| Corporate proxies bloquean downloads | Medium | Fallback D: mostrar URL + "Copiar" + "Abrir navegador" |
| MITM en platform-tools (sin hash oficial) | Low | Bundled hash pinned-at-build-time en CI |
| Gatekeeper cuarantine binaries macOS | Low | Post-download: `xattr -d com.apple.quarantine` |
| Disk quota en machines corporativas (512 MB) | Low | Detectar espacio < 100 MB → fallback manual |

## Rollback Plan

1. Revertir cambio en `AppViewModel.init` — no invocar `DependencyBootstrap.check()`
2. Eliminar `UserToolsDir` de candidatos en `ToolResolver` (step 0 retorna null si no existe)
3. Eliminar banner de `HomeScreen`
4. Eliminar MSI resources de platform-tools ( revert CI workflow)

## Dependencies

- `ToolResolver.find` como único punto de verdad (regla operativa existente)
- SHA256 de ffmpeg desde https://www.gyan.dev/ffmpeg/builds/ (disponible)
- URL estable adb: https://dl.google.com/android/repository/platform-tools-latest-{os}.zip

## Success Criteria

- [ ] App detecta adb ausente al inicio y ofrece instalación bundled
- [ ] App detecta ffmpeg ausente al intentar grabar y ofrece download
- [ ] Download + install funciona en Windows sin admin
- [ ] Fallback manual funciona si download falla
- [ ] Tests puros cubren ToolResolver + ToolInstaller + DependencyBootstrap
- [ ] `./gradlew check` pasa (detekt + tests)
