
package com.app.service;

import net.sf.jasperreports.engine.JasperCompileManager;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.*;

@Service
public class ZipService {

    private static final String OLD_URL = "https://webapp.dtes.mh.gob.sv";
    private static final String NEW_URL = "https://admin.factura.gob.sv";

    /**
     * Modos de procesamiento que puede elegir el agente:
     *  - QR       : solo actualiza la URL del QR (webapp.dtes.mh.gob.sv -> admin.factura.gob.sv)
     *  - COMPLETO : actualiza la URL del QR y además agrega el distrito en emisor y receptor
     *               (dirección unida en un solo texto)
     *  - SEPARADO : actualiza la URL del QR y dibuja la fila "Distrito:" en emisor y receptor
     *               (plantillas con Dirección / Municipio / Departamento en campos separados)
     *  - AUTO     : detecta sola cómo viene cada lado de cada plantilla y aplica lo que corresponde
     *               (dirección unida → ordenar; Municipio separado → dibujar Distrito entre
     *               Dirección y Municipio; solo Dirección → dibujar Distrito debajo; ya tiene
     *               Distrito → no tocar). Es el modo por defecto.
     *  - MIXTO    : emisor con la dirección unida (complemento, distrito, municipio, departamento)
     *               y receptor separado: dibuja "Distrito:" solo en el receptor, tanto si trae
     *               Dirección + Municipio + Departamento como si trae solo Dirección
     */
    public enum Modo {
        QR, COMPLETO, SEPARADO, MIXTO, AUTO;

        public static Modo from(String valor) {
            if (valor == null || valor.isBlank()) {
                return AUTO;
            }
            switch (valor.trim().toLowerCase()) {
                case "qr":
                    return QR;
                case "completo":
                case "qr_distrito":
                    return COMPLETO;
                case "separado":
                    return SEPARADO;
                case "mixto":
                    return MIXTO;
                case "auto":
                case "automatico":
                    return AUTO;
                default:
                    throw new IllegalArgumentException(
                            "Modo no válido: '" + valor + "'. Use 'auto', 'qr', 'completo', 'separado' o 'mixto'.");
            }
        }
    }

    /** Resultado del procesamiento: el ZIP y cuántas plantillas cambió cada etapa. */
    public static class Resultado {
        public final byte[] zip;
        public final Modo modo;
        public final int plantillas;
        public final int qrActualizados;
        public final int distritoAgregados;
        public final int distritoDibujados;
        public final int espacioAjustado;
        /** Plantillas que conviene abrir en Jaspersoft para revisar (con el motivo). */
        public final List<String> revisar;
        public int observacionesActualizadas;
        /** Por plantilla: qué se detectó y qué se hizo. */
        public final List<String> detalle = new ArrayList<>();

        Resultado(byte[] zip, Modo modo, int plantillas, int qrActualizados, int distritoAgregados,
                  int distritoDibujados, int espacioAjustado, List<String> revisar) {
            this.zip = zip;
            this.modo = modo;
            this.plantillas = plantillas;
            this.qrActualizados = qrActualizados;
            this.distritoAgregados = distritoAgregados;
            this.distritoDibujados = distritoDibujados;
            this.espacioAjustado = espacioAjustado;
            this.revisar = revisar;
        }
    }

    /** Compatibilidad: sin modo, se comporta como antes (QR + distrito). */
    public byte[] processZip(MultipartFile file) throws Exception {
        return processZip(file, Modo.COMPLETO).zip;
    }

    public Resultado processZip(MultipartFile file, Modo modo) throws Exception {

        if (file.getOriginalFilename() == null ||
                !file.getOriginalFilename().toLowerCase().endsWith(".zip")) {

            throw new RuntimeException("Solo ZIP permitido");
        }

        Path entrada = Files.createTempFile("entrada", ".zip");
        try {
            file.transferTo(entrada.toFile());
            return procesar(Files.readAllBytes(entrada), modo);
        } finally {
            Files.deleteIfExists(entrada);
        }
    }

    /** Procesa un ZIP (en memoria) de un proyecto/cliente. */
    public Resultado procesar(byte[] datosZip, Modo modo) throws Exception {

        Path tempDir = Files.createTempDirectory("jasper");

        try {

            File zip = tempDir.resolve("input.zip").toFile();

            Files.write(zip.toPath(), datosZip);

            unzip(zip, tempDir.toFile());

            List<Path> jrxmls;
            try (Stream<Path> s = Files.walk(tempDir)) {
                jrxmls = s.filter(p -> p.toString().endsWith(".jrxml")).collect(Collectors.toList());
            }

            System.out.println("MODO DE PROCESAMIENTO: " + modo + " (" + jrxmls.size() + " plantillas)");

            // Copia de seguridad de cada plantilla original, por si algo saliera mal
            Map<Path, byte[]> originales = new LinkedHashMap<>();
            for (Path p : jrxmls) originales.put(p, Files.readAllBytes(p));

            Map<Path, Set<String>> notas = new LinkedHashMap<>();
            for (Path p : jrxmls) notas.put(p, new LinkedHashSet<>());

            // ETAPA 1 (siempre): ACTUALIZAR URL DEL QR y RUTA DE OBSERVACIONES (esquema v2 del MH)
            int qrActualizados = 0, observaciones = 0;
            for (Path p : jrxmls) {
                if (replaceUrl(p)) {
                    qrActualizados++;
                    notas.get(p).add("QR actualizado a admin.factura.gob.sv");
                }
                if (actualizarObservaciones(p)) {
                    observaciones++;
                    notas.get(p).add("Observaciones: se lee " + OBS_NUEVA + " (v2) y, si no viene, " + OBS_VIEJA + " (v1)");
                }
            }

            // ETAPA 2 (modos COMPLETO, SEPARADO, MIXTO y AUTO): AGREGAR CAMPOS DE DISTRITO (e_direccion_distrito / r_direccion_distrito)
            // y ordenar la dirección unida: complemento, distrito, municipio, departamento
            int distritoAgregados = 0;
            if (modo == Modo.COMPLETO || modo == Modo.SEPARADO || modo == Modo.MIXTO || modo == Modo.AUTO) {
                for (Path p : jrxmls) {
                    if (addDireccionDistritoFields(p, notas.get(p))) distritoAgregados++;
                }
            }

            // ETAPA 3 (solo modo SEPARADO): DIBUJAR "Distrito:" ENTRE DIRECCIÓN Y MUNICIPIO
            int[] contadores = new int[2];
            List<String> revisar = new ArrayList<>();
            if (modo == Modo.SEPARADO || modo == Modo.MIXTO || modo == Modo.AUTO) {
                Set<String> lados = modo == Modo.MIXTO
                        ? Collections.singleton("r")                       // emisor va unido
                        : new HashSet<>(Arrays.asList("e", "r"));
                for (Path p : jrxmls) {
                    notas.get(p).addAll(dibujarDistrito(p, tempDir, revisar, contadores, lados));
                }
            }


            // VALIDAR: ninguna plantilla puede quedar vacía ni con XML dañado; si pasa, se restaura la original
            for (Path p : jrxmls) {
                String problema = validarXml(Files.readAllBytes(p));
                if (problema != null) {
                    String nombre = tempDir.relativize(p).toString().replace('\\', '/');
                    String problemaOriginal = validarXml(originales.get(p));
                    Files.write(p, originales.get(p));
                    notas.get(p).clear();
                    if (problemaOriginal != null) {
                        revisar.add(nombre + ": la plantilla YA VENÍA así en el ZIP que subiste (" + problemaOriginal
                                + "); revisa ese archivo en tu proyecto");
                        notas.get(p).add("ATENCIÓN: el archivo ya venía dañado en el ZIP (" + problemaOriginal + ")");
                    } else {
                        revisar.add(nombre + ": se dejó la plantilla ORIGINAL sin cambios porque el resultado " + problema);
                        notas.get(p).add("ATENCIÓN: se dejó la plantilla original (" + problema + ")");
                    }
                    System.out.println("RESTAURADA ORIGINAL " + p + ": " + problema);
                }
            }

            // ELIMINAR JASPER VIEJOS
             Files.walk(tempDir) .filter(p -> p.toString().endsWith(".jasper")) .forEach(p -> { try { Files.delete(p); System.out.println("JASPER ELIMINADO: " + p); } catch (Exception e) { e.printStackTrace(); } });

            // COMPILAR JRXML
            Files.walk(tempDir)
                    .filter(p -> p.toString().endsWith(".jrxml"))
                    .forEach(this::compile);

            // CREAR ZIP FINAL
            File outputZip = tempDir.resolve("output.zip").toFile();

            zipFolder(tempDir.toFile(), outputZip);

            Resultado res = new Resultado(Files.readAllBytes(outputZip.toPath()),
                    modo, jrxmls.size(), qrActualizados, distritoAgregados, contadores[0], contadores[1], revisar);
            res.observacionesActualizadas = observaciones;
            for (Map.Entry<Path, Set<String>> en : notas.entrySet()) {
                if (en.getValue().isEmpty()) continue;
                String nombre = tempDir.relativize(en.getKey()).toString().replace('\\', '/');
                for (String n : en.getValue()) res.detalle.add(nombre + " — " + n);
            }
            res.detalle.forEach(d -> System.out.println("DETALLE " + d));
            return res;

        } finally {

            FileSystemUtils.deleteRecursively(tempDir);
        }
    }


    // ================================================================== MASIVO

    /** Un proyecto (cliente) dentro de un procesamiento masivo. */
    public static class FilaMasivo {
        public String archivo;
        public String estado = "OK";
        public int plantillas, qr, distritoOrdenado, distritoDibujado, observaciones;
        public List<String> revisar = new ArrayList<>();
        public List<String> detalle = new ArrayList<>();
    }

    public static class ResultadoMasivo {
        public byte[] zip;
        public Modo modo;
        public List<FilaMasivo> filas = new ArrayList<>();

        public long conError() { return filas.stream().filter(f -> !f.estado.equals("OK")).count(); }
        public long conRevisar() { return filas.stream().filter(f -> !f.revisar.isEmpty()).count(); }
    }

    /** UTF-8 primero; si falla, la codificación que usa el "Comprimir" de Windows para ñ y tildes. */
    private static final java.nio.charset.Charset[] CODIFICACIONES_ZIP = {
            java.nio.charset.StandardCharsets.UTF_8, java.nio.charset.Charset.forName("Cp437")};

    /** Nombre del archivo sin carpetas (sin usar rutas del sistema: soporta ñ y tildes en cualquier servidor). */
    private static String soloNombre(String ruta) {
        String r = ruta == null ? "archivo.zip" : ruta.replace('\\', '/');
        String n = r.substring(r.lastIndexOf('/') + 1);
        return n.isBlank() ? "archivo.zip" : n;
    }

    /** ¿Este ZIP es un "ZIP de ZIPs" (sin plantillas sueltas, con otros .zip adentro)? */
    public static boolean esZipDeZips(byte[] datos) {
        boolean hayZip = false;
        for (java.nio.charset.Charset cs : CODIFICACIONES_ZIP) {
            hayZip = false;
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(datos), cs)) {
                ZipEntry e;
                while ((e = zis.getNextEntry()) != null) {
                    String n = e.getName().toLowerCase();
                    if (n.endsWith(".jrxml")) return false;
                    if (n.endsWith(".zip") && !e.isDirectory()) hayZip = true;
                }
                return hayZip;
            } catch (Exception ex) {
                // probar con la siguiente codificación
            }
        }
        return false;
    }

    /**
     * Procesa muchos proyectos de una sola vez. Cada archivo recibido puede ser:
     *  - el ZIP de un cliente (con sus .jrxml), o
     *  - un "ZIP de ZIPs" con el ZIP de cada cliente adentro.
     * Devuelve un ZIP con el ZIP procesado de cada cliente y un resumen (CSV para Excel y TXT).
     * Si un cliente falla, se informa y se sigue con los demás.
     */
    public ResultadoMasivo procesarMasivo(List<MultipartFile> archivos, Modo modo) throws Exception {
        List<String> n = new ArrayList<>();
        List<byte[]> d = new ArrayList<>();
        for (MultipartFile f : archivos) {
            n.add(f.getOriginalFilename() == null ? "archivo.zip" : f.getOriginalFilename());
            d.add(f.getBytes());
        }
        return procesarMasivo(n, d, modo);
    }

    public ResultadoMasivo procesarMasivo(List<String> nombresEntrada, List<byte[]> datosEntrada, Modo modo) throws Exception {

        // 1) Lista de proyectos (nombre, bytes)
        List<String> nombres = new ArrayList<>();
        List<byte[]> datos = new ArrayList<>();
        ResultadoMasivo rm = new ResultadoMasivo();
        rm.modo = modo;

        for (int i = 0; i < nombresEntrada.size(); i++) {
            String nombre = soloNombre(nombresEntrada.get(i));
            byte[] b = datosEntrada.get(i);
            if (!nombre.toLowerCase().endsWith(".zip")) {
                FilaMasivo fila = new FilaMasivo();
                fila.archivo = nombre;
                fila.estado = "ERROR: no es un ZIP";
                rm.filas.add(fila);
                continue;
            }
            if (esZipDeZips(b)) {
                List<String> n2 = new ArrayList<>();
                List<byte[]> d2 = new ArrayList<>();
                for (java.nio.charset.Charset cs : CODIFICACIONES_ZIP) {
                    n2.clear();
                    d2.clear();
                    try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(b), cs)) {
                        ZipEntry e;
                        while ((e = zis.getNextEntry()) != null) {
                            if (e.isDirectory() || !e.getName().toLowerCase().endsWith(".zip")) continue;
                            if (e.getName().startsWith("__MACOSX")) continue;
                            n2.add(soloNombre(e.getName()));
                            d2.add(zis.readAllBytes());
                        }
                        break;
                    } catch (Exception ex) {
                        // probar con la siguiente codificación
                    }
                }
                nombres.addAll(n2);
                datos.addAll(d2);
            } else {
                nombres.add(nombre);
                datos.add(b);
            }
        }

        // 2) Procesar cada proyecto
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        Set<String> usados = new HashSet<>();
        try (ZipOutputStream zos = new ZipOutputStream(salida)) {

            for (int i = 0; i < nombres.size(); i++) {
                FilaMasivo fila = new FilaMasivo();
                fila.archivo = nombres.get(i);
                rm.filas.add(fila);
                System.out.println("MASIVO [" + (i + 1) + "/" + nombres.size() + "] " + fila.archivo);
                try {
                    Resultado r = procesar(datos.get(i), modo);
                    fila.plantillas = r.plantillas;
                    fila.qr = r.qrActualizados;
                    fila.distritoOrdenado = r.distritoAgregados;
                    fila.distritoDibujado = r.distritoDibujados;
                    fila.observaciones = r.observacionesActualizadas;
                    fila.revisar.addAll(r.revisar);
                    fila.detalle.addAll(r.detalle);
                    if (r.plantillas == 0) fila.estado = "SIN PLANTILLAS (.jrxml)";

                    String base = fila.archivo.replaceAll("(?i)\\.zip$", "");
                    String entrada = base + ".zip";
                    for (int k = 2; usados.contains(entrada.toLowerCase()); k++) entrada = base + "_" + k + ".zip";
                    usados.add(entrada.toLowerCase());
                    zos.putNextEntry(new ZipEntry("procesados/" + entrada));
                    zos.write(r.zip);
                    zos.closeEntry();
                } catch (Exception ex) {
                    fila.estado = "ERROR: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
                    System.out.println("MASIVO ERROR " + fila.archivo + ": " + fila.estado);
                }
            }

            // 3) Resúmenes
            zos.putNextEntry(new ZipEntry("RESUMEN_MASIVO.csv"));
            zos.write(resumenCsv(rm).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("RESUMEN_MASIVO.txt"));
            zos.write(resumenTxt(rm).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        rm.zip = salida.toByteArray();
        return rm;
    }

    private static String csv(String v) {
        String t = v == null ? "" : v.replace("\r", " ").replace("\n", " | ");
        return "\"" + t.replace("\"", "\"\"") + "\"";
    }

    /** CSV con BOM y ";" para que Excel (configuración regional de El Salvador) lo abra directo. */
    private static String resumenCsv(ResultadoMasivo rm) {
        StringBuilder sb = new StringBuilder("\uFEFF");
        sb.append("Archivo;Estado;Plantillas;QR actualizados;Dirección unida ordenada;Distrito dibujado;Observaciones v1/v2;Revisar;Detalle\r\n");
        for (FilaMasivo f : rm.filas) {
            sb.append(csv(f.archivo)).append(';').append(csv(f.estado)).append(';')
              .append(f.plantillas).append(';').append(f.qr).append(';')
              .append(f.distritoOrdenado).append(';').append(f.distritoDibujado).append(';')
              .append(f.observaciones).append(';')
              .append(csv(String.join(" | ", f.revisar))).append(';')
              .append(csv(String.join(" | ", f.detalle))).append("\r\n");
        }
        return sb.toString();
    }

    private static String resumenTxt(ResultadoMasivo rm) {
        StringBuilder sb = new StringBuilder();
        sb.append("PROCESAMIENTO MASIVO - MH-Plantillas v2.0\r\n");
        sb.append("Modo: ").append(rm.modo.name().toLowerCase()).append("\r\n");
        sb.append("Clientes: ").append(rm.filas.size()).append("  |  con error: ").append(rm.conError())
          .append("  |  para revisar: ").append(rm.conRevisar()).append("\r\n\r\n");
        for (FilaMasivo f : rm.filas) {
            sb.append("== ").append(f.archivo).append(" — ").append(f.estado).append("\r\n");
            if (f.estado.equals("OK")) {
                sb.append("   Plantillas: ").append(f.plantillas).append(", QR: ").append(f.qr)
                  .append(", dirección ordenada: ").append(f.distritoOrdenado)
                  .append(", distrito dibujado: ").append(f.distritoDibujado)
                  .append(", observaciones: ").append(f.observaciones).append("\r\n");
            }
            for (String r : f.revisar) sb.append("   ⚠ REVISAR: ").append(r).append("\r\n");
            for (String d : f.detalle) sb.append("   · ").append(d).append("\r\n");
            sb.append("\r\n");
        }
        return sb.toString();
    }

    private static final String OBS_VIEJA = "/dte/extension/observaciones";
    private static final String OBS_NUEVA = "/dte/resumen/observaciones";

    /**
     * Observaciones compatibles con v1 y v2 del MH:
     *  - el campo que lee observaciones pasa a /dte/resumen/observaciones (v2);
     *  - se agrega un campo de respaldo <nombre>_v1 que lee /dte/extension/observaciones (v1);
     *  - donde la plantilla usa $F{<nombre>} se muestra el de v2 y, si viene vacío, el de v1.
     * Así se ven siempre, venga el XML en la versión que venga. No depende del motor XPath.
     * Idempotente.
     */
    private boolean actualizarObservaciones(Path file) {
        try {
            String content = Files.readString(file);
            if (!content.contains("observaciones")) return false;
            String original = content;

            Pattern campo = Pattern.compile("<field\\s+name=\"([^\"]+)\"\\s+class=\"([^\"]+)\"\\s*>([\\s\\S]*?)</field>");
            Matcher m = campo.matcher(content);
            List<String[]> encontrados = new ArrayList<>();   // {nombre, clase, bloque}
            while (m.find()) {
                String cuerpo = m.group(3);
                if (cuerpo.contains(OBS_VIEJA) || cuerpo.contains(OBS_NUEVA)) {
                    if (!m.group(1).endsWith("_v1")) encontrados.add(new String[]{m.group(1), m.group(2), m.group(0)});
                }
            }

            for (String[] f : encontrados) {
                String nombre = f[0], clase = f[1], bloque = f[2];
                String respaldo = nombre + "_v1";

                // 1) el campo principal lee la ruta v2 (propiedad XPath y descripción)
                String nuevoBloque = bloque.replace(OBS_VIEJA, OBS_NUEVA);
                content = content.replace(bloque, nuevoBloque);

                // 2) campo de respaldo v1
                if (!content.contains("<field name=\"" + respaldo + "\"")) {
                    int lineStart = content.lastIndexOf('\n', content.indexOf(nuevoBloque)) + 1;
                    String indent = content.substring(lineStart, content.indexOf(nuevoBloque));
                    if (!indent.isBlank()) indent = "\t";
                    String def = "\n" + indent + "<field name=\"" + respaldo + "\" class=\"" + clase + "\">\n"
                            + indent + "\t<property name=\"net.sf.jasperreports.xpath.field.expression\" value=\"" + OBS_VIEJA + "\"/>\n"
                            + indent + "\t<fieldDescription><![CDATA[" + OBS_VIEJA + "]]></fieldDescription>\n"
                            + indent + "</field>";
                    int fin = content.indexOf(nuevoBloque) + nuevoBloque.length();
                    content = content.substring(0, fin) + def + content.substring(fin);
                }

                // 3) en las expresiones: v2 y, si viene vacío, v1
                String ref = "$F{" + nombre + "}";
                String ref1 = "$F{" + respaldo + "}";
                String combinado = clase.equals("java.lang.String")
                        ? "(" + ref + " != null && !" + ref + ".trim().isEmpty() ? " + ref + " : " + ref1 + ")"
                        : "(" + ref + " != null ? " + ref + " : " + ref1 + ")";
                Matcher cd = Pattern.compile("<!\\[CDATA\\[([\\s\\S]*?)]]>").matcher(content);
                StringBuilder sb = new StringBuilder();
                while (cd.find()) {
                    String expr = cd.group(1);
                    if (expr.contains(ref) && !expr.contains(ref1)) expr = expr.replace(ref, combinado);
                    cd.appendReplacement(sb, Matcher.quoteReplacement("<![CDATA[" + expr + "]]>"));
                }
                cd.appendTail(sb);
                content = sb.toString();
            }

            if (content.equals(original)) return false;
            Files.writeString(file, content);
            System.out.println("OBSERVACIONES v1/v2: " + file);
            return true;
        } catch (Exception e) {
            System.out.println("ERROR ACTUALIZANDO OBSERVACIONES: " + file);
            e.printStackTrace();
            return false;
        }
    }

    /** null si el contenido es un XML válido y no vacío; si no, la descripción del problema. */
    private static String validarXml(byte[] datos) {
        try {
            if (datos == null || datos.length == 0 || new String(datos, java.nio.charset.StandardCharsets.UTF_8).isBlank()) {
                return "está vacío";
            }
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(datos));
            return null;
        } catch (Exception e) {
            return "no es un XML válido (" + e.getMessage() + ")";
        }
    }

    private static String nombreLado(String l) {
        return l.equals("e") ? "Emisor" : "Receptor";
    }

    /** Devuelve true si la plantilla tenía la URL vieja y se reemplazó. */
    private boolean replaceUrl(Path file) {

        try {

            String content = Files.readString(file);

            if (!content.contains(OLD_URL)) {
                System.out.println("URL QR sin cambios (no tiene la URL vieja): " + file);
                return false;
            }

            content = content.replace(OLD_URL, NEW_URL);

            Files.writeString(file, content);

            System.out.println("URL QR modificada OK: " + file);
            return true;

        } catch (Exception e) {

            System.out.println("ERROR MODIFICANDO URL:");
            System.out.println(file);

            e.printStackTrace();
            return false;
        }
    }

    /**
     * Dibuja la fila "Distrito:" en el diseño (emisor y receptor) y da más espacio a los textos
     * largos (Sucursal y nombre del receptor). contadores[0]++ si dibujó distrito,
     * contadores[1]++ si ajustó espacio.
     */
    private List<String> dibujarDistrito(Path file, Path base, List<String> revisar, int[] contadores, Set<String> lados) {

        List<String> notasArchivo = new ArrayList<>();

        try {

            String original = Files.readString(file);

            DistritoDrawer drawer = new DistritoDrawer();
            DistritoDrawer.Resultado r = drawer.dibujar(original, lados);

            // La fila "Distrito:" queda siempre visible y fija: en v1 el valor sale vacío, en v2 con el dato.
            // (Ya no se oculta la etiqueta ni se usa el acomodo dinámico, que movía otros campos.)

            // Más espacio para textos largos (Sucursal y nombre del receptor)
            DistritoDrawer.Resultado esp = drawer.darEspacio(r.contenido);

            List<String> avisos = new ArrayList<>(r.avisos);
            avisos.addAll(esp.avisos);
            for (String aviso : avisos) {
                revisar.add(base.relativize(file) + ": " + aviso);
                System.out.println("REVISAR " + file + ": " + aviso);
            }

            notasArchivo.addAll(r.notas);
            if (r.filasDibujadas > 0) {
                contadores[0]++;
                System.out.println("DISTRITO DIBUJADO (" + r.filasDibujadas + " filas): " + file);
            }
            if (esp.filasDibujadas > 0 && !esp.contenido.equals(r.contenido)) {
                notasArchivo.addAll(esp.notas);
                contadores[1]++;
                System.out.println("ESPACIO PARA TEXTOS LARGOS (" + esp.filasDibujadas + " campos): " + file);
            }
            if (!esp.contenido.equals(original)) {
                Files.writeString(file, esp.contenido);
            }

        } catch (Exception e) {

            System.out.println("ERROR DIBUJANDO DISTRITO:");
            System.out.println(file);

            e.printStackTrace();
            notasArchivo.add("ERROR al dibujar Distrito: " + e.getMessage());
        }
        return notasArchivo;
    }

    /** Orden obligatorio de las partes de la dirección cuando van unidas en un solo texto. */
    private static final String[] ORDEN_DIRECCION = {"complemento", "distrito", "municipio", "departamento"};

    private static final String PARTE = "(?:complemento|distrito|municipio|departamento)";

    /** $F{e_direccion_xxx} + "sep" + $F{e_direccion_yyy} + ... (dos o más partes, con cualquier espaciado) */
    private static final Pattern CADENA_DIRECCION = Pattern.compile(
            "\\$F\\{[er]_direccion_" + PARTE + "\\}" +
            "(?:\\s*\\+\\s*\"[^\"\\\\]*\"\\s*\\+\\s*\\$F\\{[er]_direccion_" + PARTE + "\\})+");

    private static final Pattern CAMPO_DIRECCION = Pattern.compile(
            "\\$F\\{([er])_direccion_(complemento|distrito|municipio|departamento)\\}");

    private static final Pattern SEPARADOR = Pattern.compile("\\+\\s*\"([^\"\\\\]*)\"\\s*\\+");

    /**
     * Agrega, si hace falta, los Fields e_direccion_distrito y r_direccion_distrito
     * (xpath /dte/emisor/direccion_distrito y /dte/receptor/direccion_distrito) y
     * reordena toda expresión que una las partes de la dirección del emisor o del
     * receptor para que quede: complemento, distrito, municipio, departamento.
     * Solo toca expresiones que incluyan el municipio. Es idempotente.
     */
    private boolean addDireccionDistritoFields(Path file) {
        return addDireccionDistritoFields(file, new LinkedHashSet<>());
    }

    private boolean addDireccionDistritoFields(Path file, Set<String> notas) {

        try {

            String content = Files.readString(file);
            String original = content;

            content = asegurarCampoDistrito(content, "e", "/dte/emisor/direccion_distrito");
            content = asegurarCampoDistrito(content, "r", "/dte/receptor/direccion_distrito");

            Set<String> reescritos = new LinkedHashSet<>(), yaListos = new LinkedHashSet<>();
            content = reordenarDirecciones(content, reescritos, yaListos);
            for (String l : reescritos) {
                notas.add(nombreLado(l) + ": dirección unida → orden complemento, distrito, municipio, departamento"
                        + " (el distrito solo aparece si viene en el XML)");
            }
            for (String l : yaListos) {
                if (!reescritos.contains(l)) notas.add(nombreLado(l) + ": dirección unida ya manejaba el distrito (sin cambios)");
            }

            if (!content.equals(original)) {

                Files.writeString(file, content);
                System.out.println("CAMPOS DE DISTRITO AGREGADOS / DIRECCION REORDENADA: " + file);
                return true;
            }

            return false;

        } catch (Exception e) {

            System.out.println("ERROR AGREGANDO CAMPOS DE DISTRITO:");
            System.out.println(file);

            e.printStackTrace();
            return false;
        }
    }

    /**
     * Crea el field {prefijo}_direccion_distrito si no existe, colocándolo junto a los
     * otros campos de dirección (después de municipio, o de complemento, o de departamento).
     */
    private String asegurarCampoDistrito(String content, String prefijo, String xpath) {

        String nuevo = prefijo + "_direccion_distrito";

        for (String despuesDe : new String[]{"municipio", "complemento", "departamento"}) {
            String resultado = insertFieldAfter(content, prefijo + "_direccion_" + despuesDe, nuevo, xpath);
            if (!resultado.equals(content) || content.contains("<field name=\"" + nuevo + "\"")) {
                return resultado;
            }
        }

        return content;
    }

    /**
     * Busca cada concatenación de partes de dirección (emisor o receptor) y la reescribe en el
     * orden complemento, distrito, municipio, departamento, usando el mismo separador que ya
     * tenía la plantilla (", " por defecto). Agrega el distrito si faltaba y el field existe.
     */
    private String reordenarDirecciones(String content) {
        return reordenarDirecciones(content, new LinkedHashSet<>(), new LinkedHashSet<>());
    }

    /**
     * Reescribe cada dirección unida (emisor o receptor) en el orden complemento, distrito,
     * municipio, departamento. El distrito se agrega de forma CONDICIONAL: solo se muestra (con su
     * separador) si viene en el XML; si no viene, no aparece ni "null" ni una coma de más:
     *
     *   $F{e_direccion_complemento}
     *   + ($F{e_direccion_distrito} != null && !$F{e_direccion_distrito}.trim().isEmpty()
     *       ? ", " + $F{e_direccion_distrito}.trim()
     *       : "")
     *   + ", " + $F{e_direccion_municipio}
     *   + ", " + $F{e_direccion_departamento}
     *
     * No toca expresiones que ya manejan el distrito por su cuenta (p.ej. las que ya tienen este
     * condicional), así que es idempotente.
     *
     * @param reescritos lados ("e"/"r") cuya dirección se reescribió
     * @param yaListos   lados cuya dirección ya incluía el distrito y no se tocó
     */
    private String reordenarDirecciones(String content, Set<String> reescritos, Set<String> yaListos) {

        Matcher m = CADENA_DIRECCION.matcher(content);
        StringBuilder sb = new StringBuilder();

        while (m.find()) {

            String cadena = m.group();
            String reemplazo = cadena;

            Matcher campos = CAMPO_DIRECCION.matcher(cadena);
            Set<String> prefijos = new HashSet<>();
            Set<String> partes = new HashSet<>();
            while (campos.find()) {
                prefijos.add(campos.group(1));
                partes.add(campos.group(2));
            }

            // Solo si todas las partes son del mismo (emisor o receptor) y aparece el municipio
            if (prefijos.size() == 1 && partes.contains("municipio")) {

                String prefijo = prefijos.iterator().next();
                String dis = "$F{" + prefijo + "_direccion_distrito}";

                // ¿La expresión que contiene esta cadena ya maneja el distrito fuera de la cadena?
                int ini = content.lastIndexOf("<![CDATA[", m.start());
                int fin = content.indexOf("]]>", m.end());
                String expr = (ini >= 0 && fin >= 0 && content.lastIndexOf("]]>", m.start()) < ini)
                        ? content.substring(ini, fin) : cadena;
                String fuera = expr.replace(cadena, "");

                if (fuera.contains(dis)) {
                    yaListos.add(prefijo);
                } else {
                    if (content.contains("<field name=\"" + prefijo + "_direccion_distrito\"")) {
                        partes.add("distrito");
                    }

                    Matcher sep = SEPARADOR.matcher(cadena);
                    String separador = sep.find() ? sep.group(1) : ", ";
                    String lit = "\"" + separador + "\"";

                    StringBuilder out = new StringBuilder();
                    boolean sinSeparador = false;   // el distrito al inicio ya trae su separador
                    for (String parte : ORDEN_DIRECCION) {
                        if (!partes.contains(parte)) continue;
                        String f = "$F{" + prefijo + "_direccion_" + parte + "}";
                        if (parte.equals("distrito")) {
                            String cond = dis + " != null && !" + dis + ".trim().isEmpty()";
                            if (out.length() > 0) {
                                out.append("\n+ (").append(cond)
                                   .append("\n    ? ").append(lit).append(" + ").append(dis).append(".trim()")
                                   .append("\n    : \"\")");
                            } else {
                                out.append("(").append(cond)
                                   .append("\n    ? ").append(dis).append(".trim() + ").append(lit)
                                   .append("\n    : \"\")");
                                sinSeparador = true;
                            }
                        } else if (out.length() == 0) {
                            out.append(f);
                        } else if (sinSeparador) {
                            out.append("\n+ ").append(f);
                            sinSeparador = false;
                        } else {
                            out.append("\n+ ").append(lit).append(" + ").append(f);
                        }
                    }
                    reemplazo = out.toString();
                    if (!reemplazo.equals(cadena)) reescritos.add(prefijo);
                }
            }

            m.appendReplacement(sb, Matcher.quoteReplacement(reemplazo));
        }

        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Inserta un nuevo <field> justo después del </field> del campo "afterFieldName",
     * copiando su indentación. No hace nada si "afterFieldName" no existe en el
     * archivo, o si "newFieldName" ya existe (para no duplicar en corridas repetidas).
     */
    private String insertFieldAfter(String content, String afterFieldName, String newFieldName, String xpathExpression) {

        if (content.contains("<field name=\"" + newFieldName + "\"")) {
            return content;
        }

        String marker = "<field name=\"" + afterFieldName + "\"";
        int start = content.indexOf(marker);

        if (start == -1) {
            return content;
        }

        int closeTag = content.indexOf("</field>", start);

        if (closeTag == -1) {
            return content;
        }

        int insertPos = closeTag + "</field>".length();

        int lineStart = content.lastIndexOf('\n', start) + 1;
        String indent = content.substring(lineStart, start);

        String newFieldBlock =
                "\n" + indent + "<field name=\"" + newFieldName + "\" class=\"java.lang.String\">\n" +
                indent + "\t<property name=\"net.sf.jasperreports.xpath.field.expression\" value=\"" + xpathExpression + "\"/>\n" +
                indent + "\t<fieldDescription><![CDATA[" + xpathExpression + "]]></fieldDescription>\n" +
                indent + "</field>";

        return content.substring(0, insertPos) + newFieldBlock + content.substring(insertPos);
    }


private void compile(Path jrxml) {

    try {

        System.out.println("=================================");
        System.out.println("COMPILANDO:");
        System.out.println(jrxml);

        // NOMBRE DEL .JASPER
        String jasperPath =
                jrxml.toString().replace(".jrxml", ".jasper");

        JasperCompileManager.compileReportToFile(
                jrxml.toString(),
                jasperPath
        );

        File jasperFile = new File(jasperPath);

        if (jasperFile.exists()) {

            System.out.println("JASPER GENERADO:");
            System.out.println(jasperFile.getAbsolutePath());

        } else {

            System.out.println("NO SE GENERÓ EL JASPER");
        }

        System.out.println("COMPILADO OK");
        System.out.println("=================================");

    } catch (Exception e) {

        System.out.println("=================================");
        System.out.println("ERROR COMPILANDO:");
        System.out.println(jrxml);

        e.printStackTrace();

        System.out.println("=================================");
    }
}



    /** Descomprime probando UTF-8 y, si los nombres vienen con la codificación de Windows, Cp437. */
    private void unzip(File zipFile, File destDir) throws IOException {
        IOException ultimo = null;
        for (java.nio.charset.Charset cs : CODIFICACIONES_ZIP) {
            try {
                unzip(zipFile, destDir, cs);
                return;
            } catch (IllegalArgumentException | java.util.zip.ZipException e) {
                ultimo = new IOException("No se pudo leer el ZIP: " + e.getMessage(), e);
            }
        }
        throw ultimo;
    }

    private void unzip(File zipFile, File destDir, java.nio.charset.Charset cs) throws IOException {

        byte[] buffer = new byte[1024];

        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile), cs);

        ZipEntry entry = zis.getNextEntry();

        while (entry != null) {

            File newFile = newFile(destDir, entry);

            if (entry.isDirectory()) {

                newFile.mkdirs();

            } else {

                new File(newFile.getParent()).mkdirs();

                FileOutputStream fos = new FileOutputStream(newFile);

                int len;

                while ((len = zis.read(buffer)) > 0) {
                    fos.write(buffer, 0, len);
                }

                fos.close();
            }

            entry = zis.getNextEntry();
        }

        zis.closeEntry();
        zis.close();
    }

    private File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {

        File destFile = new File(destinationDir, zipEntry.getName());

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("ZIP inválido");
        }

        return destFile;
    }

    private void zipFolder(File source, File output) throws IOException {

        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output));

        Files.walk(source.toPath())
                .filter(p ->
                        !Files.isDirectory(p)
                                && !p.getFileName().toString().equals("input.zip")
                                && !p.getFileName().toString().equals("output.zip")
                )
                .forEach(p -> {

                    try {

                        ZipEntry entry =
                                new ZipEntry(source.toPath().relativize(p).toString().replace('\\', '/'));

                        zos.putNextEntry(entry);

                        Files.copy(p, zos);

                        zos.closeEntry();

                    } catch (Exception e) {

                        e.printStackTrace();
                    }
                });

        zos.close();
    }
}

