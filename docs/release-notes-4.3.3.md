## Arreglos

- **El nombre del dispositivo ya sale en forma legible en la lista de dispositivos**: antes, cuando tu Samsung Galaxy S23 aparecía en la lista de dispositivos conectados aparecía como `SM_S911B` en vez de `Samsung Galaxy S23`. La causa: `adb devices -l` devuelve el modelo con guión bajo (`SM_S911B`) porque su parser es space-delimited y un guión se confundiría con un separador, mientras que la tabla de mapeo `SM-S911 → Samsung Galaxy S23` usa guión (la forma canónica de Samsung). El prefix match fallaba. Ahora el resolver normaliza los guiones bajos a guiones al inicio, así que ambos caminos (`adb devices -l` y `getprop ro.product.model`) producen el mismo nombre bonito.

## Detalles tecnicos

- `DeviceNameResolver.resolve()` normaliza `_` → `-` al inicio con una sola línea (`val normalized = trimmedModel.replace('_', '-')`) aplicada antes del exact match y del prefix match. El fallback también usa la forma normalizada, así que un modelo desconocido muestra `Samsung SM-S999X` en vez de `Samsung SM_S999X`.
- 3 tests nuevos en `DeviceNameResolverTest` cubriendo la forma con underscore para 4 variantes Samsung (S23, S24 Ultra, Z Fold 5), probando que hyphen-form y underscore-form producen el mismo output, y verificando que el fallback normaliza también.
- Version bump: `4.3.2` → `4.3.3`. Patch.
