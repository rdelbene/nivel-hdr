# Nivel HDR

App Android (Motorola Edge 50 Ultra, Android 14+) para elegir cuánto brillo extra agrega el **Ultra HDR** de las fotos de la cámara stock, sin tocar la imagen.

- **Automático:** elegís el nivel una vez; cada foto nueva de la cámara se guarda sola con ese nivel, segundos después de sacarla. Necesita el permiso "Acceso a todos los archivos".
- **Manual:** elegís fotos ya sacadas (o las compartís desde la galería) y las reemplazás o guardás una copia con otro nivel.
- Solo se reescriben los ~20 bytes que fijan la intensidad (XMP `hdrgm` y bloque ISO 21496-1): misma imagen, mismo EXIF, mismo tamaño, reversible. La stock usa ×4.93.

Cada push compila el APK con GitHub Actions y lo publica como release `build-N` (firma fija: se instala encima).
