# MH-Plantillas (Jasper ZIP Processor) — versión 2.1

## Novedades 2.1 — Procesamiento masivo
- En la pantalla se pueden seleccionar **varios ZIP a la vez** (uno por cliente), o subir **un ZIP que
  contiene el ZIP de cada cliente** (también por `/zip/process`, se detecta solo).
- Endpoint nuevo: `POST /zip/masivo` (parámetros `files` y `modo`).
- Cada cliente se procesa con las mismas reglas de la v2.0; si uno falla, los demás siguen.
- Descarga: `procesado_masivo.zip` con `procesados/<cliente>.zip` + `RESUMEN_MASIVO.csv` (Excel, separador ";")
  + `RESUMEN_MASIVO.txt`.
- La pantalla muestra una tabla por cliente (errores en rojo, "para revisar" en amarillo).
- Nombres con ñ/tildes: se leen ZIPs en UTF-8 y en la codificación de Windows (Cp437); el Dockerfile fija UTF-8.
- Límites de carga: 300 MB por archivo, 1 GB por envío.

## Versión anterior estable
La v2.0 (aprobada) está guardada en `versiones/v2.0/` y `versiones/MH-Plantillas-v2.0.zip`.

---

## Base: versión 2.0

Fecha: 13 de octubre de 2026
Estado: estable (aprobada). Punto de restauración (rollback) en `versiones/v2.0`.

## Qué hace

Recibe un ZIP con plantillas JasperReports (.jrxml), las adapta a los esquemas v2.0 del MH,
las recompila (.jasper) y devuelve el ZIP procesado con un resumen de lo detectado y lo hecho.

### Modos
- **Automático (por defecto):** detecta cómo viene cada plantilla, por emisor y receptor.
  - Dirección unida en un solo texto → orden complemento, distrito, municipio, departamento.
    El distrito es condicional: si no viene en el XML no aparece "null" ni una coma de más.
  - Dirección / Municipio / Departamento separados → dibuja la fila "Distrito:" entre Dirección y Municipio.
  - Solo Dirección → dibuja la fila "Distrito:" debajo de la Dirección.
  - Ya muestra Distrito → no se toca.
- **Solo actualizar QR.**
- **Opciones avanzadas:** forzar dirección unida, emisor y receptor separado, emisor unido + receptor separado.

### Reglas en todos los modos
- QR: `https://webapp.dtes.mh.gob.sv` → `https://admin.factura.gob.sv`.
- Observaciones: el campo lee `/dte/resumen/observaciones` (v2) y, si viene vacío,
  `observaciones_v1` (`/dte/extension/observaciones`, v1).
- Validación final: si una plantilla queda vacía o con XML inválido se restaura la original
  y se avisa (también avisa si ya venía dañada en el ZIP).

### Fila "Distrito:" (dibujada)
- Siempre visible y fija: con XML v1 el valor sale vacío, con XML v2 sale el dato.
- Acomodo fijo: baja solo lo que choca, aprovecha espacios libres, mantiene alineaciones;
  los recuadros que envuelven crecen y empujan lo de abajo conservando la separación;
  los recuadros nunca se suben; si la banda tiene que crecer se avisa en "Revisar".
- No se agregan `positionType="Float"` ni `stretchType` (se descartó el acomodo dinámico).

### Textos largos
- Sucursal / Taller (`$F{e_nombre_comercial}`): no pasa por debajo de la etiqueta de al lado,
  usa hasta 2 líneas si hay espacio; si no cabe se corta con "...".
- Nombre del receptor: se deja como lo diseñó la plantilla.

### Compatibilidad
- iReport (formato clásico): no se usa `textAdjust`; solo atributos y propiedades clásicas.
- JasperReports 6.21.3, Java 17, Spring Boot 3.2.5.

## Archivos
- `src/main/java/com/app/service/ZipService.java` — flujo de procesamiento y modos.
- `src/main/java/com/app/service/DistritoDrawer.java` — detección y dibujo en el diseño.
- `src/main/java/com/app/controller/ZipController.java` — endpoint `/zip/process`.
- `frontend/` — pantalla.

## Cómo volver a esta versión (rollback)
Copiar el contenido de `versiones/v2.0/` sobre `MH-Plantillas-main/` (o descomprimir
`versiones/MH-Plantillas-v2.0.zip`), luego `mvn clean package` y desplegar.
