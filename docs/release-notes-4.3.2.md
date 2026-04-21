## Arreglos

- **Fin de «el video va lentísimo al arrastrar el timeline hacia la derecha»** (reportado múltiples veces en sesiones anteriores; cada intento anterior falló porque solo miraba uno de los dos bugs a la vez): eran dos problemas superpuestos que se amplificaban entre sí.
  - El cache de frames del reproductor se instanciaba con tamaño 1500 cuando la documentación de la propia clase decía que el sweet spot son 600. A ~1.5MB por frame decodificado, 1500 frames × 1.5MB ≈ 2.25GB, por encima del heap cap (`-Xmx2048m`). Una vez lleno, el GC entraba en stop-the-world de 200ms-1s cada pocos segundos.
  - El generador de la vista previa (thumbnail track) compartía el mismo set de procesos ffmpeg que el extractor de frames on-demand, así que cada scrub llamaba a `killActiveProcesses()` y mataba el ffmpeg que estaba generando los thumbnails. El thumbnail track nunca se completaba si tocabas el timeline durante su generación (~15-60s), y entonces cada scrub caía al ffmpeg on-demand que es 10-20x más lento.

## Detalles tecnicos

- `EmbeddedVideoPlayer.kt:384`: `FrameCache(1500)` → `FrameCache()` para usar el default documentado (600 frames, ~900MB peak).
- Separado el set `activeProcesses` en dos: `activeFrameProcesses` (efímero, killed on scrub) y `activeThumbnailProcesses` (long-running, killed **solo** on dispose). El thumbnail track ya sobrevive a scrubs consecutivos.
- `CLAUDE.md` actualizado con una sección dedicada: tanto el diagnóstico como la lección meta de "cuando un bug persiste misteriosamente tras varios fixes, asumir que hay más de una causa y buscar la combinación".
- Version bump: `4.3.1` → `4.3.2`. Patch.
