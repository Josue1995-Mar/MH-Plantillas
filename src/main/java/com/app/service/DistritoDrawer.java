package com.app.service;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * "Dibuja" en el diseño de un .jrxml (como se haría a mano en Jaspersoft Studio / iReport)
 * la fila "Distrito:" para el emisor y el receptor, en plantillas donde la dirección
 * va en campos separados (Dirección / Municipio / Departamento).
 *
 * No depende de coordenadas fijas: en cada banda o frame busca el textField que muestra
 * $F{e_direccion_municipio} (o r_), su etiqueta "Municipio" en la misma fila, y:
 *   1. Inserta una etiqueta "Distrito:" y un textField $F{x_direccion_distrito} en la
 *      posición de la fila de Municipio (copiando fuente, tamaño, estilo, condiciones).
 *   2. Baja la fila Municipio/Departamento y todo lo que está debajo en esa columna
 *      (emisor o receptor), y los elementos de ancho completo (rectángulos, títulos...).
 *   3. Si ya no cabe, agranda la banda / frame.
 *
 * Resultado: Dirección → Distrito → Municipio / Departamento.
 *
 * Trabaja sobre el texto original del .jrxml (solo cambia atributos y agrega bloques),
 * así el archivo se sigue viendo y abriendo igual en Jaspersoft. Es idempotente: si la
 * banda ya muestra el distrito de ese lado, no hace nada.
 */
public class DistritoDrawer {

    private static final Set<String> ELEMENTOS = new HashSet<>(Arrays.asList(
            "staticText", "textField", "image", "line", "rectangle", "ellipse", "frame",
            "subreport", "componentElement", "break", "genericElement", "crosstab", "chart"));

    /** Tipos que, si atraviesan la fila insertada (p.ej. un recuadro alrededor del emisor), se alargan. */
    private static final Set<String> CONTENEDORES_VISUALES = new HashSet<>(Arrays.asList(
            "rectangle", "frame", "line", "ellipse"));

    /** Separación horizontal máxima entre elementos de la misma fila para considerarlos del mismo lado. */
    private static final int GAP_COLUMNA = 25;

    /** Tolerancia vertical para considerar que dos elementos están en la misma fila. */
    private static final int TOL_FILA = 3;

    public static class Resultado {
        public final String contenido;
        public final int filasDibujadas;
        /** Situaciones que conviene revisar a mano en Jaspersoft (p.ej. la banda tuvo que crecer). */
        public final List<String> avisos;
        /** Qué detectó y qué hizo, en lenguaje simple (para el resumen del agente). */
        public final List<String> notas = new ArrayList<>();

        public Resultado(String contenido, int filasDibujadas, List<String> avisos) {
            this.contenido = contenido;
            this.filasDibujadas = filasDibujadas;
            this.avisos = avisos;
        }
    }

    // ------------------------------------------------------------------ modelo

    private static class Elem {
        Element el;          // staticText, textField, ...
        Element re;          // su <reportElement>
        int reIndex;         // índice del <reportElement> en el documento
        String tipo;
        int x, y, w, h;
        String expr = "";    // textFieldExpression
        String texto = "";   // text de staticText
        int fontSize = 10;   // tamaño de fuente (textElement/font size)
        boolean estira;      // textField que crece hacia abajo (isStretchWithOverflow / StretchHeight)
        int reserva;         // espacio libre que el diseño dejó debajo para que el texto crezca
        String positionType = "";
        String stretchType = "";

        int right() { return x + w; }
        int bottom() { return y + h; }
        boolean solapaX(int x0, int x1) { return x < x1 && right() > x0; }
    }

    private static class Contenedor {
        Element nodo;        // <band> o <frame>
        Elem frameElem;      // si es frame, su Elem en el contenedor padre
        Contenedor padre;
        int ancho;
        int alto;
        int bandIndex = -1;  // si es band, índice del <band> en el documento
        String seccion = ""; // title, pageHeader, columnHeader, detail, background...
        /**
         * true si el contenido de este contenedor puede "fluir" (bajar en tiempo de ejecución) sin
         * chocar con algo fijo: no hay elementos del background dibujados debajo del encabezado.
         */
        boolean fluido = true;
        List<Elem> hijos = new ArrayList<>();
    }

    /** Una fila de Distrito a dibujar en un contenedor. */
    private static class Insercion {
        String prefijo;      // e | r
        Elem campo;          // textField de municipio
        Elem etiqueta;       // staticText "Municipio:"
        int filaY;           // y superior de la fila de municipio
        int filaH;           // alto de la fila
        int x0, x1;          // rango horizontal de la columna (emisor o receptor)
        int delta;           // cuánto se baja todo
        int anchoCampo;      // ancho del nuevo textField de distrito
        boolean desdeDireccion; // true: no hay fila de Municipio, se ancla en la fila de Dirección
        int yNueva = -1;     // (desdeDireccion) y de la fila nueva
        int altoCampo = -1;  // (desdeDireccion) alto del nuevo textField
        /** Fila plegable: mide 1 pt y solo crece (empujando lo de abajo) si viene el distrito. */
        boolean plegable;
    }

    // ------------------------------------------------------------------ cambios de texto

    private static class Cambio {
        final int inicio, fin;
        final String texto;

        Cambio(int inicio, int fin, String texto) {
            this.inicio = inicio;
            this.fin = fin;
            this.texto = texto;
        }
    }

    // ------------------------------------------------------------------ flujo (anti-encimado)

    /**
     * Hace que el encabezado "fluya" como lo haría un diseñador en Jaspersoft/iReport, para que
     * ningún texto que crece se monte sobre lo que tiene debajo:
     *  - todo elemento que esté debajo de un texto que puede crecer (isStretchWithOverflow), o de
     *    otro elemento que ya baja, y comparta columna con él, pasa a positionType="Float";
     *  - los recuadros / líneas / frames que envuelven un texto que puede crecer pasan a
     *    stretchType="RelativeToTallestObject" para estirarse con él.
     * Solo en bandas "fluidas" (sin una tabla fija del background debajo) y SOLO a partir de los
     * campos del bloque emisor/receptor (dirección, distrito, sucursal, nombre, actividad):
     * lo que está debajo de ellos en su columna, y los recuadros que los envuelven. No toca elementos que ya
     * tienen posición/estiramiento definidos a mano (FixRelativeToBottom, etc.). Idempotente.
     */
    public Resultado fluir(String jrxml) throws Exception {

        Modelo m = cargar(jrxml);
        if (m == null) return new Resultado(jrxml, 0, Collections.emptyList());

        Map<Integer, Map<String, String>> attrs = new TreeMap<>();
        int nFloat = 0, nStretch = 0, nAncho = 0;

        for (Contenedor c : m.contenedores) {
            if (!c.fluido) continue;
            List<Elem> vis = new ArrayList<>();
            for (Elem e : c.hijos) if (e.x < c.ancho && e.right() > 0) vis.add(e);
            vis.sort(Comparator.comparingInt((Elem e) -> e.y).thenComparingInt(e -> e.x));

            // Solo los campos que esta herramienta toca o que suelen traer textos largos en el bloque
            // emisor/receptor (dirección, distrito, sucursal, nombre, actividad). El resto de la
            // plantilla queda exactamente como la dejó el diseñador.
            Set<Elem> crecen = Collections.newSetFromMap(new IdentityHashMap<>());
            for (Elem e : vis) if (e.tipo.equals("textField") && e.estira && esCampoClave(e.expr)) crecen.add(e);
            if (crecen.isEmpty()) continue;
            Set<Elem> bajan = Collections.newSetFromMap(new IdentityHashMap<>());

            // Un texto que crece no puede pasar por debajo de la siguiente etiqueta/campo de su fila
            // (si no, al hacer 2 líneas se monta sobre esa otra columna): se recorta su ancho.
            for (Elem f : crecen) {
                int ancho = f.w;
                for (Elem o : vis) {
                    if (o != f && !CONTENEDORES_VISUALES.contains(o.tipo) && !o.tipo.equals("image")
                            && Math.abs(o.y - f.y) <= TOL_FILA && o.x > f.x + 5 && o.x < f.x + ancho) {
                        ancho = o.x - f.x - 2;
                    }
                }
                if (ancho < f.w && ancho >= 20) {
                    attrs.computeIfAbsent(f.reIndex, k -> new LinkedHashMap<>()).put("width", String.valueOf(ancho));
                    f.w = ancho;
                    nAncho++;
                }
            }

            for (Elem e : vis) {
                for (Elem p : vis) {
                    if (p == e || !(crecen.contains(p) || bajan.contains(p))) continue;
                    boolean encima = p.y < e.y && p.bottom() <= e.y + 2;
                    boolean mismaColumna = p.x < e.right() && p.right() > e.x;
                    if (encima && mismaColumna) {
                        bajan.add(e);
                        break;
                    }
                }
            }

            // Lo que está DENTRO de un recuadro/frame que baja, baja con él (si no, se sale del recuadro)
            boolean mas = true;
            while (mas) {
                mas = false;
                for (Elem r : vis) {
                    if (!bajan.contains(r) || !CONTENEDORES_VISUALES.contains(r.tipo)) continue;
                    for (Elem e : vis) {
                        if (e == r || bajan.contains(e)) continue;
                        if (e.x >= r.x - 2 && e.right() <= r.right() + 2 && e.y >= r.y - 1 && e.bottom() <= r.bottom() + 2) {
                            bajan.add(e);
                            mas = true;
                        }
                    }
                }
            }

            for (Elem e : bajan) {
                if (e.positionType.isEmpty() || e.positionType.equals("FixRelativeToTop")) {
                    attrs.computeIfAbsent(e.reIndex, k -> new LinkedHashMap<>()).put("positionType", "Float");
                    nFloat++;
                }
            }

            // Envolventes: recuadros/líneas/frames que contienen algo que crece o baja
            for (Elem r : vis) {
                if (!CONTENEDORES_VISUALES.contains(r.tipo)) continue;
                if (!r.stretchType.isEmpty() && !r.stretchType.equals("NoStretch")) continue;
                boolean envuelve = false;
                for (Elem e : vis) {
                    if (e == r || !crecen.contains(e)) continue;   // solo si adentro hay un texto que crece
                    if (e.x >= r.x - 2 && e.right() <= r.right() + 2 && e.y >= r.y && e.y < r.bottom()
                            && e.bottom() <= r.bottom() + 2) {
                        envuelve = true;
                        break;
                    }
                }
                if (envuelve) {
                    attrs.computeIfAbsent(r.reIndex, k -> new LinkedHashMap<>()).put("stretchType", "RelativeToTallestObject");
                    nStretch++;
                }
            }
        }

        if (attrs.isEmpty()) return new Resultado(jrxml, 0, Collections.emptyList());

        List<Cambio> cambios = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, String>> en : attrs.entrySet()) {
            int[] t = m.reTags.get(en.getKey());
            String tag = jrxml.substring(t[0], t[1]);
            String nuevo = tag;
            for (Map.Entry<String, String> a : en.getValue().entrySet()) nuevo = setAttrTexto(nuevo, a.getKey(), a.getValue());
            if (!nuevo.equals(tag)) cambios.add(new Cambio(t[0], t[1], nuevo));
        }
        Resultado r = new Resultado(aplicar(jrxml, cambios), nFloat + nStretch + nAncho, Collections.emptyList());
        r.notas.add("Anti-encimado: " + nFloat + " elementos bajan solos cuando un texto de arriba crece"
                + (nStretch > 0 ? "; " + nStretch + " recuadros/líneas se estiran con su contenido" : "")
                + (nAncho > 0 ? "; " + nAncho + " textos ya no pasan por debajo de la etiqueta de al lado" : ""));
        return r;
    }

    /** Campos del bloque emisor/receptor que pueden crecer y sobre los que se aplica el anti-encimado. */
    private static boolean esCampoClave(String expr) {
        return expr.contains("_direccion_") || expr.contains("$F{e_nombre_comercial}") || expr.contains("$F{r_nombre}")
                || expr.contains("$F{r_nombre_comercial}") || expr.contains("_descripcion_actividad}");
    }

    private static String setAttrTexto(String tag, String attr, String valor) {
        Pattern p = Pattern.compile("(\\s" + attr + "\\s*=\\s*\")[^\"]*(\")");
        Matcher mm = p.matcher(tag);
        if (mm.find()) return tag.substring(0, mm.start()) + mm.group(1) + valor + mm.group(2) + tag.substring(mm.end());
        int sp = tag.indexOf(' ');
        if (sp < 0) sp = tag.indexOf('>');
        return tag.substring(0, sp) + " " + attr + "=\"" + valor + "\"" + tag.substring(sp);
    }

    // ------------------------------------------------------------------ distrito vacío

    /**
     * Para cada fila "Distrito:" separada (la que dibuja esta herramienta o la que ya traía la
     * plantilla), agrega a la etiqueta y al campo la condición de impresión
     *     $F{x_direccion_distrito} != null && !$F{x_direccion_distrito}.trim().isEmpty()
     * para que, si el XML no trae distrito (documentos anteriores), no aparezca "Distrito:" vacío.
     * Si el elemento ya tenía una condición, se combina con &&. Compatible con iReport. Idempotente.
     */
    public Resultado ocultarDistritoVacio(String jrxml) throws Exception {

        Modelo m = cargar(jrxml);
        if (m == null) return new Resultado(jrxml, 0, Collections.emptyList());

        List<Cambio> cambios = new ArrayList<>();
        Set<String> notas = new LinkedHashSet<>();
        int n = 0;

        for (Contenedor c : m.contenedores) {
            for (String p : new String[]{"e", "r"}) {
                String dis = "$F{" + p + "_direccion_distrito}";
                String cond = dis + " != null && !" + dis + ".trim().isEmpty()";
                for (Elem f : c.hijos) {
                    if (!f.tipo.equals("textField") || f.x >= c.ancho || !f.expr.contains(dis)) continue;
                    if (f.expr.contains("_direccion_municipio") || f.expr.contains("_direccion_complemento")) continue;

                    List<Elem> objetivo = new ArrayList<>();
                    objetivo.add(f);
                    Elem etiqueta = null;
                    for (Elem e : c.hijos) {
                        if (e.tipo.equals("staticText") && Math.abs(e.y - f.y) <= TOL_FILA && e.x <= f.x
                                && e.texto.toLowerCase().contains("distrito")
                                && (etiqueta == null || e.x > etiqueta.x)) {
                            etiqueta = e;
                        }
                    }
                    if (etiqueta != null) objetivo.add(etiqueta);

                    for (Elem e : objetivo) {
                        int[] t = m.reTags.get(e.reIndex);
                        String tag = jrxml.substring(t[0], t[1]);
                        int fin = tag.endsWith("/>") ? t[1] : jrxml.indexOf("</reportElement>", t[1]) + "</reportElement>".length();
                        String seccion = jrxml.substring(t[0], fin);
                        if (seccion.contains("printWhenExpression") && seccion.contains("_direccion_distrito}")) continue;
                        cambios.add(new Cambio(t[0], fin, conCondicion(jrxml, t[0], seccion, cond)));
                        n++;
                    }
                    notas.add((p.equals("e") ? "Emisor" : "Receptor")
                            + ": la fila Distrito solo se imprime si viene el dato");
                }
            }
        }

        if (cambios.isEmpty()) return new Resultado(jrxml, 0, Collections.emptyList());
        Resultado r = new Resultado(aplicar(jrxml, cambios), n, Collections.emptyList());
        r.notas.addAll(notas);
        return r;
    }

    /** Agrega (o combina con &&) una printWhenExpression dentro de un <reportElement>. */
    private static String conCondicion(String jrxml, int inicioTag, String seccion, String cond) {

        int lineStart = jrxml.lastIndexOf('\n', inicioTag) + 1;
        String indent = jrxml.substring(lineStart, inicioTag);
        if (!indent.isBlank()) indent = "";

        Matcher pw = Pattern.compile("(<printWhenExpression[^>]*>\\s*<!\\[CDATA\\[)([\\s\\S]*?)(]]>)").matcher(seccion);
        if (pw.find()) {
            String combinada = "(" + pw.group(2).trim() + ") && (" + cond + ")";
            return seccion.substring(0, pw.start(2)) + combinada + seccion.substring(pw.end(2));
        }
        String expr = indent + "\t<printWhenExpression><![CDATA[" + cond + "]]></printWhenExpression>";
        if (seccion.endsWith("/>")) {
            return seccion.substring(0, seccion.length() - 2).replaceAll("\\s+$", "") + ">\n"
                    + expr + "\n" + indent + "</reportElement>";
        }
        int cierre = seccion.lastIndexOf("</reportElement>");
        String antes = seccion.substring(0, cierre).replaceAll("[ \\t]+$", "");
        if (!antes.endsWith("\n")) antes += "\n";
        return antes + expr + "\n" + indent + seccion.substring(cierre);
    }

    // ------------------------------------------------------------------ espacio para textos largos

    /** Campos que suelen traer textos largos y cuántas líneas de espacio se les reserva. */
    private static final LinkedHashMap<String, Integer> CAMPOS_LARGOS = new LinkedHashMap<>();
    static {
        CAMPOS_LARGOS.put("$F{e_nombre_comercial}", 2);   // Sucursal / Taller
        // El nombre del receptor ($F{r_nombre}) se deja como lo diseñó la plantilla (decisión de negocio)
    }

    /** Propiedad (compatible con iReport) que corta el texto que no cabe y le agrega "...". */
    private static final String SUFIJO_CORTE = "net.sf.jasperreports.text.truncate.suffix";

    /** Lo máximo que se sube un bloque para ganar espacio (Talleres subió el nombre ~7 pt). */
    private static final int MAX_SUBIR = 7;

    private static class Modelo {
        List<Contenedor> contenedores = new ArrayList<>();
        List<int[]> reTags, bandTags;
    }

    private Modelo cargar(String jrxml) throws Exception {

        Document doc = parse(jrxml);
        Element root = doc.getDocumentElement();
        int columnWidth = intAttr(root, "columnWidth",
                intAttr(root, "pageWidth", 595) - intAttr(root, "leftMargin", 0) - intAttr(root, "rightMargin", 0));

        List<Element> reportElements = elementosPorNombre(root, "reportElement");
        List<Element> bands = elementosPorNombre(root, "band");
        Modelo m = new Modelo();
        m.reTags = tags(jrxml, "reportElement");
        m.bandTags = tags(jrxml, "band");
        if (reportElements.size() != m.reTags.size() || bands.size() != m.bandTags.size()) return null;

        Map<Element, Integer> idxRe = new IdentityHashMap<>();
        for (int i = 0; i < reportElements.size(); i++) idxRe.put(reportElements.get(i), i);
        for (int i = 0; i < bands.size(); i++) {
            Contenedor c = new Contenedor();
            c.nodo = bands.get(i);
            c.ancho = columnWidth;
            c.alto = intAttr(bands.get(i), "height", 0);
            c.bandIndex = i;
            Node padre = bands.get(i).getParentNode();
            c.seccion = padre instanceof Element ? local((Element) padre) : "";
            int desde = m.contenedores.size();
            m.contenedores.add(c);
            cargarHijos(c, bands.get(i), idxRe, m.contenedores);
            for (int k = desde + 1; k < m.contenedores.size(); k++) m.contenedores.get(k).seccion = c.seccion;
        }
        for (Contenedor c : m.contenedores) calcularReservas(c);
        calcularFluidez(m.contenedores);
        return m;
    }

    /**
     * Da más espacio a los campos que suelen traer textos largos (Sucursal y nombre del receptor)
     * para que no se encimen con lo que tienen al lado o debajo:
     *  1. El campo deja de pasar por debajo de la siguiente etiqueta de su fila (p.ej. "Teléfono:").
     *  2. Se le reservan N líneas de alto: primero con el espacio libre que tenga debajo; si no
     *     alcanza, sube su fila (y el nombre de arriba) hasta MAX_SUBIR pt, como en Talleres; lo que
     *     falte se empuja hacia abajo aprovechando los huecos.
     *  3. El campo queda en su caja fija (sin "crecer" hacia abajo): si aun así el texto no cabe,
     *     se corta con "..." en vez de encimarse. Se usa isStretchWithOverflow + la propiedad
     *     net.sf.jasperreports.text.truncate.suffix, que también entiende iReport (no se usa
     *     textAdjust, que iReport no reconoce).
     * Es idempotente.
     */
    public Resultado darEspacio(String jrxml) throws Exception {

        Modelo m = cargar(jrxml);
        if (m == null) return new Resultado(jrxml, 0, Collections.emptyList());

        Map<Integer, int[]> nuevosYH = new HashMap<>();
        Map<Integer, Integer> nuevosAltoBanda = new HashMap<>();
        List<Cambio> cambios = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        Set<Integer> agregarSufijo = new HashSet<>();
        int ajustados = 0;

        for (Contenedor c : m.contenedores) {

            // En bandas fluidas los textos largos simplemente crecen y empujan lo de abajo (ver fluir)
            if (c.fluido) continue;

            Map<Elem, Integer> ny = new IdentityHashMap<>();
            Map<Elem, Integer> nh = new IdentityHashMap<>();
            Set<Elem> forzados = Collections.newSetFromMap(new IdentityHashMap<>());
            List<Elem> visibles = new ArrayList<>();
            for (Elem e : c.hijos) {
                if (e.x < c.ancho && e.right() > 0) {
                    visibles.add(e);
                    ny.put(e, e.y);
                    nh.put(e, e.h);
                }
            }
            boolean tocado = false;

            for (Map.Entry<String, Integer> campo : CAMPOS_LARGOS.entrySet()) {
                for (Elem f : visibles) {
                    if (!f.tipo.equals("textField") || !f.expr.trim().equals(campo.getKey())) continue;

                    int linea = (int) Math.ceil(f.fontSize * 1.25);
                    int altoDeseado = campo.getValue() * linea;

                    // 1) Ancho: no pasar por debajo del siguiente elemento de la fila
                    int ancho = f.w;
                    for (Elem o : visibles) {
                        if (o != f && Math.abs(o.y - f.y) <= TOL_FILA && o.x > f.x + 5 && o.x < f.right()) {
                            ancho = Math.min(ancho, o.x - f.x - 2);
                        }
                    }

                    String abre = aperturaTextField(jrxml, f, m.reTags);
                    String reTag = jrxml.substring(m.reTags.get(f.reIndex)[0], m.reTags.get(f.reIndex)[1]);
                    boolean yaFijo = !abre.contains("isStretchWithOverflow=\"true\"") && !abre.contains("textAdjust")
                            && bloque(jrxml, f, m.reTags).contains(SUFIJO_CORTE);
                    if (f.h >= altoDeseado && ancho == f.w && yaFijo) continue;   // ya estaba listo

                    // Fila del campo (dentro de su columna)
                    List<Elem> fila = new ArrayList<>();
                    for (Elem o : visibles) {
                        if (Math.abs(o.y - f.y) <= TOL_FILA && !o.tipo.equals("image")) {
                            fila.add(o);
                        }
                    }
                    // la fila se limita al grupo contiguo alrededor del campo
                    fila.sort(Comparator.comparingInt(o -> o.x));
                    int fx0 = f.x, fx1 = f.right();
                    boolean crece = true;
                    while (crece) {
                        crece = false;
                        for (Elem o : fila) {
                            if (o.right() >= fx0 - GAP_COLUMNA && o.x <= fx1 + GAP_COLUMNA
                                    && (o.x < fx0 || o.right() > fx1)) {
                                fx0 = Math.min(fx0, o.x);
                                fx1 = Math.max(fx1, o.right());
                                crece = true;
                            }
                        }
                    }
                    final int cx0 = fx0, cx1 = fx1;
                    fila.removeIf(o -> !o.solapaX(cx0, cx1));

                    int falta = Math.max(0, altoDeseado - f.h);

                    // 2a) Espacio libre debajo del campo
                    int debajo = Integer.MAX_VALUE;
                    for (Elem o : visibles) {
                        if (o != f && o.solapaX(f.x, f.x + ancho) && o.y >= f.bottom() - 1) {
                            debajo = Math.min(debajo, o.y);
                        }
                    }
                    int libreAbajo = debajo == Integer.MAX_VALUE ? falta : Math.max(0, debajo - f.bottom());
                    falta = Math.max(0, falta - libreAbajo);

                    // 2b) Subir la fila (y lo que choque arriba) hasta MAX_SUBIR
                    int subir = Math.min(MAX_SUBIR, falta);
                    if (subir > 0) {
                        Set<Elem> mover = Collections.newSetFromMap(new IdentityHashMap<>());
                        mover.addAll(fila);
                        boolean mas = true;
                        while (mas) {
                            mas = false;
                            for (Elem o : visibles) {
                                if (mover.contains(o) || o.tipo.equals("image") || CONTENEDORES_VISUALES.contains(o.tipo)) continue;
                                if (!o.solapaX(cx0, cx1) || o.y >= f.y) continue;
                                int topeMovido = mover.stream().filter(mv -> mv.solapaX(o.x, o.right()))
                                        .mapToInt(mv -> mv.y).min().orElse(Integer.MAX_VALUE);
                                int traslape = Math.max(0, o.bottom() - topeMovido);
                                if (topeMovido != Integer.MAX_VALUE && o.bottom() - traslape > topeMovido - subir) {
                                    mover.add(o);
                                    mas = true;
                                }
                            }
                        }
                        // no subir por encima de 0, ni chocar con lo que no se mueve, ni meterse más en un
                        // logo/imagen que esté arriba (no sabemos dónde termina el dibujo dentro de la imagen)
                        mover.removeIf(mv -> CONTENEDORES_VISUALES.contains(mv.tipo));   // los recuadros no se suben
                        for (Elem mv : mover) {
                            subir = Math.min(subir, Math.max(0, mv.y));
                            // si está dentro de un recuadro, no puede salirse por arriba de su borde
                            for (Elem r : visibles) {
                                if (r == mv || !CONTENEDORES_VISUALES.contains(r.tipo)) continue;
                                if (mv.x >= r.x - 2 && mv.right() <= r.right() + 2 && mv.y >= r.y && mv.y < r.bottom()) {
                                    subir = Math.min(subir, Math.max(0, mv.y - (r.y + 2)));
                                }
                            }
                            for (Elem img : visibles) {
                                if (img.tipo.equals("image") && img.y < mv.y && img.solapaX(mv.x, mv.right())) {
                                    subir = 0;
                                }
                            }
                            for (Elem o : visibles) {
                                if (mover.contains(o) || o.tipo.equals("image")) continue;
                                if (o.bottom() <= mv.y && o.solapaX(mv.x, mv.right())) {
                                    subir = Math.min(subir, mv.y - o.bottom());
                                }
                            }
                        }
                        if (subir > 0) {
                            for (Elem mv : mover) {
                                ny.put(mv, mv.y - subir);
                                forzados.add(mv);
                            }
                            falta -= subir;
                        }
                    }

                    // 2c) Nuevo alto del campo; lo que falte se empuja hacia abajo
                    nh.put(f, Math.max(f.h, altoDeseado));
                    forzados.add(f);

                    // 1') ancho y 3) caja fija con corte "..."
                    if (ancho != f.w) {
                        f.w = ancho;   // se escribe junto con y/alto al final
                    }
                    if (!yaFijo) {
                        int ini = jrxml.lastIndexOf("<textField", m.reTags.get(f.reIndex)[0]);
                        String nuevaAbre = abre
                                .replaceAll("\\s+isStretchWithOverflow=\"[^\"]*\"", "")
                                .replaceAll("\\s+textAdjust=\"[^\"]*\"", "");
                        if (!nuevaAbre.equals(abre)) cambios.add(new Cambio(ini, ini + abre.length(), nuevaAbre));
                        if (!bloque(jrxml, f, m.reTags).contains(SUFIJO_CORTE)) agregarSufijo.add(f.reIndex);
                    }
                    ajustados++;
                    tocado = true;
                    System.out.println("ESPACIO para " + campo.getKey() + ": alto " + f.h + "->" + nh.get(f)
                            + ", ancho " + ancho + ", subido " + Math.max(0, f.y - ny.get(f)));
                }
            }

            if (!tocado) continue;

            // Empujar hacia abajo lo que choque (consume huecos, mantiene alineaciones).
            // Nunca rebasar lo que ya ocupaba la banda (debajo puede haber una tabla fija en el
            // background): si no alcanza, se reduce el alto extra y el texto se corta con "...".
            int limite = 0;
            for (Elem e : visibles) limite = Math.max(limite, e.bottom());
            Map<Elem, Integer> nyBase = new IdentityHashMap<>(ny);
            List<Elem> crecidos = new ArrayList<>();
            Map<Elem, Integer> deseado = new IdentityHashMap<>();
            for (Elem e : visibles) {
                if (nh.get(e) > e.h) {
                    crecidos.add(e);
                    deseado.put(e, nh.get(e));
                    nh.put(e, e.h);
                }
            }
            // Cada campo toma el mayor alto (hasta el deseado) que quepa sin rebasar el límite
            for (Elem g : crecidos) {
                for (int alto = deseado.get(g); alto >= g.h; alto--) {
                    nh.put(g, alto);
                    Map<Elem, Integer> prueba = new IdentityHashMap<>(nyBase);
                    empujar(visibles, prueba, nh, forzados);
                    int max = 0;
                    for (Elem e : visibles) max = Math.max(max, prueba.get(e) + nh.get(e));
                    if (max <= limite || alto == g.h) break;
                }
            }
            ny.putAll(nyBase);
            empujar(visibles, ny, nh, forzados);
            for (Elem g : crecidos) {
                System.out.println("ESPACIO final " + g.expr.trim() + ": alto " + nh.get(g));
            }

            int maxAntes = 0, maxDespues = 0;
            for (Elem e : visibles) {
                maxAntes = Math.max(maxAntes, e.bottom());
                maxDespues = Math.max(maxDespues, ny.get(e) + nh.get(e));
                if (ny.get(e) != e.y || nh.get(e) != e.h || e.w != anchoOriginal(jrxml, e, m.reTags)) {
                    nuevosYH.put(e.reIndex, new int[]{ny.get(e), nh.get(e), e.w});
                }
            }
            if (maxDespues > maxAntes) {
                avisos.add("al dar espacio a textos largos la banda creció " + (maxDespues - maxAntes)
                        + " pt; revisar que no se encime con el fondo (background) o la tabla");
            }
            if (maxDespues > c.alto) agrandar(c, maxDespues - c.alto, nuevosYH, nuevosAltoBanda);
        }

        if (ajustados == 0) return new Resultado(jrxml, 0, avisos);

        Set<Integer> indices = new TreeSet<>(nuevosYH.keySet());
        indices.addAll(agregarSufijo);
        for (Integer idx : indices) {
            int[] t = m.reTags.get(idx);
            String tag = jrxml.substring(t[0], t[1]);
            String nuevo = tag;
            int[] v = nuevosYH.get(idx);
            if (v != null) {
                nuevo = setAttr(setAttr(nuevo, "y", v[0]), "height", v[1]);
                if (v.length > 2) nuevo = setAttr(nuevo, "width", v[2]);
            }
            if (agregarSufijo.contains(idx)) {
                int lineStart = jrxml.lastIndexOf('\n', t[0]) + 1;
                String indent = jrxml.substring(lineStart, t[0]);
                if (!indent.isBlank()) indent = "";
                String prop = "<property name=\"" + SUFIJO_CORTE + "\" value=\"...\"/>";
                if (nuevo.endsWith("/>")) {
                    nuevo = nuevo.substring(0, nuevo.length() - 2).replaceAll("\\s+$", "") + ">\n"
                            + indent + "\t" + prop + "\n" + indent + "</reportElement>";
                } else {
                    nuevo = nuevo + "\n" + indent + "\t" + prop;
                }
            }
            if (!nuevo.equals(tag)) cambios.add(new Cambio(t[0], t[1], nuevo));
        }
        for (Map.Entry<Integer, Integer> en : nuevosAltoBanda.entrySet()) {
            int[] t = m.bandTags.get(en.getKey());
            cambios.add(new Cambio(t[0], t[1], setAttr(jrxml.substring(t[0], t[1]), "height", en.getValue())));
        }
        Resultado res = new Resultado(aplicar(jrxml, cambios), ajustados, avisos);
        res.notas.add("Más espacio para la Sucursal (si no cabe se corta con \"...\")");
        return res;
    }

    private String aperturaTextField(String jrxml, Elem f, List<int[]> reTags) {
        int ini = jrxml.lastIndexOf("<textField", reTags.get(f.reIndex)[0]);
        int fin = jrxml.indexOf('>', ini) + 1;
        return jrxml.substring(ini, fin);
    }

    private int anchoOriginal(String jrxml, Elem e, List<int[]> reTags) {
        int[] t = reTags.get(e.reIndex);
        Matcher mm = Pattern.compile("\\swidth\\s*=\\s*\"([^\"]*)\"").matcher(jrxml.substring(t[0], t[1]));
        return mm.find() ? (int) Math.round(Double.parseDouble(mm.group(1))) : e.w;
    }

    /**
     * Empuja hacia abajo, lo justo, cada elemento que quedaría encimado con algo que tenía encima
     * (usando las nuevas posiciones y altos), respetando traslapes originales y manteniendo
     * alineados los elementos que compartían la misma y. Los "forzados" no se realinean.
     */
    private void empujar(List<Elem> visibles, Map<Elem, Integer> ny, Map<Elem, Integer> nh, Set<Elem> forzados) {

        List<Elem> orden = new ArrayList<>();
        for (Elem e : visibles) if (!e.tipo.equals("image")) orden.add(e);
        orden.sort(Comparator.comparingInt(e -> e.y));

        boolean cambio = true;
        for (int vuelta = 0; cambio && vuelta < 100; vuelta++) {
            cambio = false;
            for (Elem e : orden) {
                int req = ny.get(e);
                for (Elem p : orden) {
                    if (p == e || p.y >= e.y) continue;
                    if (p.bottom() > e.y + 2) continue;
                    if (!(p.x < e.right() && p.right() > e.x)) continue;
                    // si el campo ya creció, su crecimiento usa primero el espacio que tenía reservado
                    int reserva = Math.max(0, p.reserva - (nh.get(p) - p.h));
                    int traslape = Math.max(0, p.bottom() + p.reserva - e.y);
                    req = Math.max(req, ny.get(p) + nh.get(p) + reserva - traslape);
                }
                if (req > ny.get(e)) {
                    ny.put(e, req);
                    cambio = true;
                }
            }
            Map<Integer, List<Elem>> porY = new HashMap<>();
            for (Elem e : orden) if (!forzados.contains(e)) porY.computeIfAbsent(e.y, k -> new ArrayList<>()).add(e);
            for (List<Elem> grupo : porY.values()) {
                int max = grupo.stream().mapToInt(ny::get).max().orElse(0);
                for (Elem e : grupo) {
                    if (ny.get(e) < max) {
                        ny.put(e, max);
                        cambio = true;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ API

    public Resultado dibujar(String jrxml) throws Exception {
        return dibujar(jrxml, new HashSet<>(Arrays.asList("e", "r")));
    }

    /**
     * @param lados "e" (emisor) y/o "r" (receptor): en qué lados se dibuja el distrito.
     */
    public Resultado dibujar(String jrxml, Set<String> lados) throws Exception {

        Modelo modelo = cargar(jrxml);
        if (modelo == null) {
            System.out.println("DISTRITO: no se pudo mapear el archivo (estructura no estándar), se omite");
            return new Resultado(jrxml, 0, Collections.emptyList());
        }
        List<Contenedor> contenedores = modelo.contenedores;
        List<int[]> reTags = modelo.reTags;
        List<int[]> bandTags = modelo.bandTags;

        // y/alto finales por reportElement (para reescribir solo lo que cambia)
        Map<Integer, int[]> nuevosYH = new HashMap<>();
        Map<Integer, Integer> nuevosAltoBanda = new HashMap<>();
        List<Cambio> cambios = new ArrayList<>();
        List<String> avisos = new ArrayList<>();
        Set<String> notasDibujo = new LinkedHashSet<>();
        int filas = 0;

        for (Contenedor c : contenedores) {

            List<Insercion> ins = new ArrayList<>();
            for (String p : new String[]{"e", "r"}) {
                if (!lados.contains(p)) continue;
                String lado = p.equals("e") ? "Emisor" : "Receptor";
                if (muestraDistritoSeparado(c, p)) {
                    notasDibujo.add(lado + ": ya mostraba Distrito (sin cambios)");
                }
                Insercion i = detectar(c, p);
                if (i != null) {
                    if (c.fluido) {
                        i.plegable = true;
                        i.delta = 1;
                        i.filaH = 1;
                        if (i.desdeDireccion) i.altoCampo = 1;
                    }
                    ins.add(i);
                    notasDibujo.add(lado + (i.desdeDireccion
                            ? ": solo Dirección → se dibujó Distrito debajo de la Dirección"
                            : ": Dirección / Municipio / Departamento separados → se dibujó Distrito entre Dirección y Municipio"));
                }
            }
            if (ins.isEmpty()) continue;

            // Acomodar: bajar la fila de municipio y empujar solo lo que choque,
            // aprovechando los espacios libres (como se haría a mano en Jaspersoft)
            acomodar(c, ins, nuevosYH);
            // Dibujar las filas nuevas (en la posición original de la fila de municipio)
            for (Insercion i : ins) {
                cambios.add(insertarDespues(jrxml, i.campo, reTags, clonarCampo(jrxml, i, reTags)));
                if (i.etiqueta != null) {
                    cambios.add(insertarDespues(jrxml, i.etiqueta, reTags, clonarEtiqueta(jrxml, i, reTags)));
                }
                filas++;
                System.out.println("DISTRITO DIBUJADO (" + (i.prefijo.equals("e") ? "emisor" : "receptor")
                        + ") en y=" + i.filaY + ", x=" + i.x0 + "-" + i.x1);
            }

            // ¿Cabe todavía? Si no, agrandar banda/frame (y propagar si es frame)
            int maxAntes = 0, maxBottom = 0;
            for (Elem e : c.hijos) {
                if (e.x >= c.ancho) continue;
                int[] yh = nuevosYH.get(e.reIndex);
                maxAntes = Math.max(maxAntes, e.bottom());
                maxBottom = Math.max(maxBottom, yh != null ? yh[0] + yh[1] : e.bottom());
            }
            if (maxBottom > maxAntes) {
                avisos.add("el contenido de la banda creció " + (maxBottom - maxAntes)
                        + " pt; revisar que no se encime con el fondo (background) o la tabla");
            }
            if (maxBottom > c.alto) {
                agrandar(c, maxBottom - c.alto, nuevosYH, nuevosAltoBanda);
            }
        }

        if (filas == 0) {
            Resultado r0 = new Resultado(jrxml, 0, avisos);
            r0.notas.addAll(notasDibujo);
            return r0;
        }

        // Reescribir atributos y / height de los reportElement afectados
        for (Map.Entry<Integer, int[]> en : nuevosYH.entrySet()) {
            int[] t = reTags.get(en.getKey());
            String tag = jrxml.substring(t[0], t[1]);
            String nuevo = setAttr(setAttr(tag, "y", en.getValue()[0]), "height", en.getValue()[1]);
            if (!nuevo.equals(tag)) cambios.add(new Cambio(t[0], t[1], nuevo));
        }
        for (Map.Entry<Integer, Integer> en : nuevosAltoBanda.entrySet()) {
            int[] t = bandTags.get(en.getKey());
            String tag = jrxml.substring(t[0], t[1]);
            cambios.add(new Cambio(t[0], t[1], setAttr(tag, "height", en.getValue())));
        }

        Resultado res = new Resultado(aplicar(jrxml, cambios), filas, avisos);
        res.notas.addAll(notasDibujo);
        return res;
    }

    /** ¿Ese lado ya muestra el distrito en un campo propio (no dentro de una dirección unida)? */
    private boolean muestraDistritoSeparado(Contenedor c, String p) {
        String dis = "$F{" + p + "_direccion_distrito}";
        for (Elem e : c.hijos) {
            if (e.tipo.equals("textField") && e.x < c.ancho && e.expr.contains(dis)
                    && !e.expr.contains("_direccion_municipio") && !e.expr.contains("_direccion_complemento")) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ detección

    private Insercion detectar(Contenedor c, String p) {

        String mun = "$F{" + p + "_direccion_municipio}";
        String dis = "$F{" + p + "_direccion_distrito}";

        // Si ese lado ya muestra el distrito en esta banda, no tocar
        for (Elem e : c.hijos) {
            if (e.tipo.equals("textField") && e.x < c.ancho && e.expr.contains(dis)) return null;
        }

        // textField visible que muestre SOLO el municipio (no la dirección concatenada)
        Elem campo = null;
        for (Elem e : c.hijos) {
            if (!e.tipo.equals("textField") || e.x >= c.ancho || e.x + e.w <= 0) continue;
            if (!e.expr.contains(mun)) continue;
            if (e.expr.contains("_direccion_complemento") || e.expr.contains("_direccion_departamento")) continue;
            if (campo == null || e.y < campo.y) campo = e;
        }
        if (campo == null) return detectarPorDireccion(c, p);

        // Elementos de la misma fila
        List<Elem> fila = new ArrayList<>();
        for (Elem e : c.hijos) {
            if (e.x >= c.ancho) continue;
            if (Math.abs(e.y - campo.y) <= TOL_FILA && e.h <= Math.max(campo.h, 1) * 3) fila.add(e);
        }
        fila.sort(Comparator.comparingInt(e -> e.x));

        // Etiqueta "Municipio" a la izquierda del campo
        Elem etiqueta = null;
        for (Elem e : fila) {
            if (e.tipo.equals("staticText") && e.texto.toLowerCase().contains("municipio") && e.x <= campo.x) {
                if (etiqueta == null || e.x > etiqueta.x) etiqueta = e;
            }
        }

        // Rango de la columna: desde la etiqueta (o el campo) hacia la derecha mientras los elementos sean contiguos
        int x0 = etiqueta != null ? Math.min(etiqueta.x, campo.x) : campo.x;
        int x1 = Math.max(campo.right(), etiqueta != null ? etiqueta.right() : 0);
        boolean creciendo = true;
        while (creciendo) {
            creciendo = false;
            for (Elem e : fila) {
                if (e.x >= x0 - GAP_COLUMNA && e.x <= x1 + GAP_COLUMNA && e.right() > x1) {
                    x1 = e.right();
                    creciendo = true;
                }
                if (e.right() <= x0 + GAP_COLUMNA && e.right() >= x0 - GAP_COLUMNA && e.x < x0) {
                    x0 = e.x;
                    creciendo = true;
                }
            }
        }
        x1 = Math.min(x1, c.ancho + 40);

        int filaY = campo.y, filaH = campo.h;
        for (Elem e : fila) {
            if (e.solapaX(x0, x1)) {
                filaY = Math.min(filaY, e.y);
                filaH = Math.max(filaH, e.bottom() - filaY);
            }
        }

        // Siguiente elemento debajo en la columna, para respetar el espaciado que ya usa la plantilla
        int siguiente = Integer.MAX_VALUE;
        for (Elem e : c.hijos) {
            if (e.solapaX(x0, x1) && e.y >= filaY + filaH && e.x < c.ancho) siguiente = Math.min(siguiente, e.y);
        }
        int gap = siguiente == Integer.MAX_VALUE ? 1 : Math.max(0, Math.min(1, siguiente - (filaY + filaH)));

        Insercion i = new Insercion();
        i.prefijo = p;
        i.campo = campo;
        i.etiqueta = etiqueta;
        i.filaY = filaY;
        i.filaH = filaH;
        i.x0 = x0;
        i.x1 = x1;
        i.delta = filaH + gap;
        // El distrito va solo en su fila: el campo se estira hasta el final de la columna
        i.anchoCampo = Math.max(campo.w, Math.min(x1, c.ancho + 20) - campo.x);
        return i;
    }

    /**
     * Caso "solo Dirección": ese lado no muestra Municipio por separado, solo
     * $F{x_direccion_complemento}. Se dibuja "Distrito:" en la fila siguiente a la Dirección
     * (respetando el espacio que la Dirección tenía reservado para su segunda línea) y se baja
     * lo que estaba debajo.
     */
    private Insercion detectarPorDireccion(Contenedor c, String p) {

        String comp = "$F{" + p + "_direccion_complemento}";

        Elem campo = null;
        for (Elem e : c.hijos) {
            if (!e.tipo.equals("textField") || e.x >= c.ancho || e.x + e.w <= 0) continue;
            if (!e.expr.contains(comp)) continue;
            if (e.expr.contains("_direccion_municipio") || e.expr.contains("_direccion_departamento")
                    || e.expr.contains("_direccion_distrito")) continue;
            if (campo == null || e.y < campo.y) campo = e;
        }
        if (campo == null) return null;

        // Etiqueta "Dirección" en la misma fila, a la izquierda
        Elem etiqueta = null;
        List<Elem> fila = new ArrayList<>();
        for (Elem e : c.hijos) {
            if (e.x >= c.ancho || e.tipo.equals("image")) continue;
            if (Math.abs(e.y - campo.y) <= TOL_FILA) fila.add(e);
        }
        for (Elem e : fila) {
            if (e.tipo.equals("staticText") && e.texto.toLowerCase().matches("(?s).*direcci[oó]n.*") && e.x <= campo.x) {
                if (etiqueta == null || e.x > etiqueta.x) etiqueta = e;
            }
        }

        int x0 = etiqueta != null ? Math.min(etiqueta.x, campo.x) : campo.x;
        int x1 = Math.max(campo.right(), etiqueta != null ? etiqueta.right() : 0);
        x1 = Math.min(x1, c.ancho + 40);

        // Dónde empieza lo siguiente en la columna (debajo de la Dirección y su espacio reservado)
        int siguiente = Integer.MAX_VALUE;
        for (Elem e : c.hijos) {
            if (e == campo || fila.contains(e) || e.x >= c.ancho || e.tipo.equals("image")) continue;
            if (e.solapaX(x0, x1) && e.y >= campo.bottom() - 1) siguiente = Math.min(siguiente, e.y);
        }
        int linea = (int) Math.ceil(campo.fontSize * 1.25);
        int alto = etiqueta != null ? Math.max(etiqueta.h, Math.min(campo.h, linea)) : Math.min(campo.h, linea);
        // Justo debajo de la Dirección, dejándole a lo sumo una línea extra para su segunda línea
        int yNueva = campo.bottom() + Math.min(campo.reserva, linea);
        if (siguiente != Integer.MAX_VALUE) yNueva = Math.min(yNueva, siguiente);

        Insercion i = new Insercion();
        i.prefijo = p;
        i.campo = campo;
        i.etiqueta = etiqueta;
        i.desdeDireccion = true;
        i.yNueva = yNueva;
        i.altoCampo = alto;
        i.filaY = yNueva;
        i.filaH = alto;
        i.x0 = x0;
        i.x1 = x1;
        i.delta = alto;
        i.anchoCampo = campo.w;
        return i;
    }


    // ------------------------------------------------------------------ acomodo

    /**
     * Calcula la nueva y de cada elemento del contenedor:
     *  - la fila de municipio (y lo que esté en ella dentro de la columna) baja lo necesario
     *    para dejar sitio a la fila de distrito;
     *  - cualquier elemento que, al bajar algo que está encima, quedaría encimado, baja
     *    lo justo (se consumen los espacios en blanco que ya existían);
     *  - los elementos que estaban alineados en la misma y siguen alineados
     *    (p.ej. "Vendedor" y "Cuenta Cliente");
     *  - recuadros/líneas que envuelven la zona se alargan.
     */
    private void acomodar(Contenedor c, List<Insercion> ins, Map<Integer, int[]> nuevosYH) {

        List<Elem> visibles = new ArrayList<>();
        for (Elem e : c.hijos) if (e.x < c.ancho && e.right() > 0) visibles.add(e);

        Map<Elem, Integer> ny = new IdentityHashMap<>();
        for (Elem e : visibles) ny.put(e, e.y);

        // Elementos que envuelven la fila (empiezan arriba y terminan abajo): no se mueven, se alargan
        Set<Elem> envolventes = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Elem e : visibles) {
            for (Insercion i : ins) {
                if (e.solapaX(i.x0, i.x1) && e.y < i.filaY - TOL_FILA && e.bottom() > i.filaY + i.filaH) {
                    envolventes.add(e);
                }
            }
        }

        // 1) Bajar la fila de municipio de cada lado
        for (Insercion i : ins) {
            for (Elem e : visibles) {
                if (envolventes.contains(e)) continue;
                if (e.solapaX(i.x0, i.x1) && Math.abs(e.y - i.filaY) <= TOL_FILA) {
                    ny.put(e, Math.max(ny.get(e), e.y + i.delta));
                }
            }
        }

        // 2) Empujar por choque + mantener alineaciones, hasta que nada cambie. Los recuadros que
        //    envuelven la zona crecen con su contenido y, ya crecidos, también empujan lo que
        //    tienen debajo (p.ej. el recuadro de "No. interno SAP" debajo del recuadro del receptor).
        List<Elem> orden = new ArrayList<>(visibles);
        orden.removeAll(envolventes);
        orden.sort(Comparator.comparingInt(e -> e.y));

        Map<Elem, Integer> altoEnv = new IdentityHashMap<>();
        for (Elem env : envolventes) altoEnv.put(env, env.h);

        for (int ronda = 0; ronda < 20; ronda++) {

            boolean cambio = true;
            for (int vuelta = 0; cambio && vuelta < 100; vuelta++) {
                cambio = false;

                for (Elem e : orden) {
                    int req = ny.get(e);
                    for (Elem p : orden) {
                        if (p == e || p.y >= e.y) continue;
                        if (p.bottom() > e.y + 2) continue;                    // p no estaba encima de e
                        if (!(p.x < e.right() && p.right() > e.x)) continue;    // no comparten columna
                        int traslape = Math.max(0, p.bottom() + p.reserva - e.y); // respetar traslapes originales
                        req = Math.max(req, ny.get(p) + p.h + p.reserva - traslape);
                    }
                    for (Elem env : envolventes) {
                        if (env.bottom() > e.y + 2) continue;                  // e no estaba debajo del recuadro
                        if (!(env.x < e.right() && env.right() > e.x)) continue;
                        // baja lo mismo que creció el recuadro (conserva la separación original)
                        req = Math.max(req, e.y + (altoEnv.get(env) - env.h));
                    }
                    if (req > ny.get(e)) {
                        ny.put(e, req);
                        cambio = true;
                    }
                }

                // Mantener alineados los elementos que compartían la misma y (y alguno se movió)
                Map<Integer, List<Elem>> porY = new HashMap<>();
                for (Elem e : orden) porY.computeIfAbsent(e.y, k -> new ArrayList<>()).add(e);
                for (List<Elem> grupo : porY.values()) {
                    int max = grupo.stream().mapToInt(ny::get).max().orElse(0);
                    for (Elem e : grupo) {
                        if (ny.get(e) < max) {
                            ny.put(e, max);
                            cambio = true;
                        }
                    }
                }
            }

            // Alto de cada recuadro envolvente según lo que quedó adentro
            boolean crecio = false;
            for (Elem env : envolventes) {
                int maxDentroAntes = env.y, maxDentroDespues = env.y;
                for (Elem e : orden) {
                    if (e.x >= env.x && e.right() <= env.right() + 2 && e.y >= env.y && e.bottom() <= env.bottom() + 2) {
                        maxDentroAntes = Math.max(maxDentroAntes, e.bottom());
                        maxDentroDespues = Math.max(maxDentroDespues, ny.get(e) + e.h);
                    }
                }
                int alto = env.h + Math.max(0, maxDentroDespues - maxDentroAntes);
                if (!CONTENEDORES_VISUALES.contains(env.tipo)) alto = env.h;
                if (alto != altoEnv.get(env)) {
                    altoEnv.put(env, alto);
                    crecio = true;
                }
            }
            if (!crecio) break;
        }

        // 3) Guardar movimientos
        for (Elem e : orden) {
            if (ny.get(e) != e.y) nuevosYH.put(e.reIndex, new int[]{ny.get(e), e.h});
        }

        // 4) Alargar recuadros/líneas que envuelven la zona, para que sigan conteniendo lo de adentro
        for (Elem env : envolventes) {
            if (altoEnv.get(env) > env.h) nuevosYH.put(env.reIndex, new int[]{env.y, altoEnv.get(env)});
        }
    }

    // ------------------------------------------------------------------ clonado

    private String clonarEtiqueta(String jrxml, Insercion i, List<int[]> reTags) {

        String bloque = bloque(jrxml, i.etiqueta, reTags);
        bloque = nuevoUuid(bloque);
        if (i.desdeDireccion) {
            bloque = setAttrEnReportElement(bloque, "y", i.yNueva + (i.etiqueta.y - i.campo.y));
        }

        Matcher m = Pattern.compile(i.desdeDireccion ? "(?i)direcci[oó]n" : "(?i)municipio").matcher(bloque);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String orig = m.group();
            String rep = orig.equals(orig.toUpperCase()) ? "DISTRITO"
                    : Character.isUpperCase(orig.charAt(0)) ? "Distrito" : "distrito";
            m.appendReplacement(sb, rep);
        }
        m.appendTail(sb);
        String res = sb.toString();
        if (i.plegable) res = etiquetaPlegable(res, i);
        return res;
    }

    /** Convierte la etiqueta (staticText) en un textField de 1 pt que solo muestra "Distrito:" si viene el dato. */
    private String etiquetaPlegable(String bloque, Insercion i) {
        String dis = "$F{" + i.prefijo + "_direccion_distrito}";
        String cond = dis + " != null && !" + dis + ".trim().isEmpty()";
        Matcher t = Pattern.compile("<text>\\s*(?:<!\\[CDATA\\[)?([\\s\\S]*?)(?:]]>)?\\s*</text>").matcher(bloque);
        String texto = t.find() ? t.group(1) : "Distrito:";
        String lit = "\"" + texto.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String expr = "<textFieldExpression><![CDATA[" + cond + " ? " + lit + " : \"\"]]></textFieldExpression>";
        bloque = t.find(0) ? bloque.substring(0, t.start()) + expr + bloque.substring(t.end()) : bloque;
        bloque = bloque.replaceFirst("<staticText[^>]*>", "<textField isStretchWithOverflow=\"true\" isBlankWhenNull=\"true\">");
        bloque = bloque.replace("</staticText>", "</textField>");
        bloque = setAttrEnReportElement(bloque, "y", i.filaY);
        return setAttrEnReportElement(bloque, "height", 1);
    }

    private String clonarCampo(String jrxml, Insercion i, List<int[]> reTags) {

        String bloque = bloque(jrxml, i.campo, reTags);
        bloque = nuevoUuid(bloque);
        bloque = setAttrEnReportElement(bloque, "width", i.anchoCampo);
        if (i.desdeDireccion) {
            bloque = setAttrEnReportElement(bloque, "y", i.yNueva);
            bloque = setAttrEnReportElement(bloque, "height", i.altoCampo);
        }

        String mun = "$F{" + i.prefijo + (i.desdeDireccion ? "_direccion_complemento}" : "_direccion_municipio}");
        String dis = "$F{" + i.prefijo + "_direccion_distrito}";

        if (i.plegable) {
            bloque = setAttrEnReportElement(bloque, "y", i.filaY);
            bloque = setAttrEnReportElement(bloque, "height", 1);
            if (!bloque.contains("isStretchWithOverflow=\"true\"")) {
                bloque = bloque.replaceFirst("<textField", "<textField isStretchWithOverflow=\"true\"")
                        .replaceFirst("(<textField[^>]*?)\\s+textAdjust=\"[^\"]*\"", "$1");
            }
        }

        Matcher m = Pattern.compile("(<textFieldExpression[^>]*>\\s*<!\\[CDATA\\[)([\\s\\S]*?)(]]>)").matcher(bloque);
        if (m.find()) {
            String expr = m.group(2).trim().replace(mun, dis);
            if (i.plegable) {
                // vacío si no viene: así la fila de 1 pt no crece ni deja hueco
                String base = expr.equals(dis) ? dis + ".trim()" : expr;
                expr = dis + " != null && !" + dis + ".trim().isEmpty() ? " + base + " : \"\"";
            } else if (!expr.equals(dis)) {
                // Si la expresión usa métodos (toUpperCase, trim...) protegerla contra distrito nulo
                expr = dis + " == null ? \"\" : " + expr;
            }
            bloque = bloque.substring(0, m.start(2)) + expr + bloque.substring(m.end(2));
        } else {
            bloque = bloque.replace(mun, dis);
        }
        return bloque;
    }

    /** Texto completo del elemento (desde <staticText|<textField ... hasta su cierre). */
    private String bloque(String jrxml, Elem e, List<int[]> reTags) {
        int[] r = reTags.get(e.reIndex);
        int ini = jrxml.lastIndexOf("<" + e.tipo, r[0]);
        String cierre = "</" + e.tipo + ">";
        int fin = jrxml.indexOf(cierre, r[1]) + cierre.length();
        return jrxml.substring(ini, fin);
    }

    private Cambio insertarDespues(String jrxml, Elem e, List<int[]> reTags, String nuevoBloque) {
        int[] r = reTags.get(e.reIndex);
        int ini = jrxml.lastIndexOf("<" + e.tipo, r[0]);
        String cierre = "</" + e.tipo + ">";
        int fin = jrxml.indexOf(cierre, r[1]) + cierre.length();
        int lineStart = jrxml.lastIndexOf('\n', ini) + 1;
        String indent = jrxml.substring(lineStart, ini);
        if (!indent.isBlank()) indent = "";
        return new Cambio(fin, fin, "\n" + indent + nuevoBloque);
    }

    // ------------------------------------------------------------------ crecimiento de contenedores

    private void agrandar(Contenedor c, int extra, Map<Integer, int[]> nuevosYH, Map<Integer, Integer> altosBanda) {

        if (c.bandIndex >= 0) {
            int actual = altosBanda.getOrDefault(c.bandIndex, c.alto);
            altosBanda.put(c.bandIndex, actual + extra);
            c.alto = actual + extra;
            System.out.println("DISTRITO: banda agrandada a " + c.alto);
            return;
        }

        // Frame: crecer y empujar lo que está debajo en el contenedor padre
        Elem f = c.frameElem;
        int[] yh = nuevosYH.getOrDefault(f.reIndex, new int[]{f.y, f.h});
        int bottomViejo = yh[0] + yh[1];
        nuevosYH.put(f.reIndex, new int[]{yh[0], yh[1] + extra});
        c.alto += extra;

        Contenedor p = c.padre;
        int maxBottom = 0;
        for (Elem e : p.hijos) {
            int[] eyh = nuevosYH.getOrDefault(e.reIndex, new int[]{e.y, e.h});
            if (e != f && e.solapaX(f.x, f.right()) && eyh[0] >= bottomViejo) {
                eyh = new int[]{eyh[0] + extra, eyh[1]};
                nuevosYH.put(e.reIndex, eyh);
            }
            if (e.x < p.ancho) maxBottom = Math.max(maxBottom, eyh[0] + eyh[1]);
        }
        if (maxBottom > p.alto) agrandar(p, maxBottom - p.alto, nuevosYH, altosBanda);
    }


    /**
     * Para cada textField que crece hacia abajo, si el diseño le dejó debajo al menos una línea
     * libre, ese espacio se considera reservado (no se usa para acomodar otras cosas), porque es
     * donde cae la segunda línea de textos como "Actividad económica" o "Dirección".
     */
    private static void calcularReservas(Contenedor c) {
        for (Elem e : c.hijos) {
            e.reserva = 0;
            if (!e.tipo.equals("textField") || !e.estira) continue;
            int linea = (int) Math.ceil(e.fontSize * 1.25);
            int siguiente = Integer.MAX_VALUE;
            for (Elem o : c.hijos) {
                if (o != e && o.x < c.ancho && o.solapaX(e.x, e.right()) && o.y >= e.bottom() - 1
                        && !o.tipo.equals("image")) {
                    siguiente = Math.min(siguiente, o.y);
                }
            }
            if (siguiente != Integer.MAX_VALUE && siguiente - e.bottom() >= linea) {
                e.reserva = siguiente - e.bottom();
            }
        }
    }


    /**
     * Decide qué bandas pueden "fluir": si en el background hay elementos dibujados a la altura de
     * la parte baja de un encabezado (p.ej. la tabla de productos fija), ese encabezado NO puede
     * crecer en tiempo de ejecución sin encimarse, y se usa el acomodo fijo de siempre.
     */
    /**
     * Acomodo dinámico (fila plegable + elementos que bajan solos) DESACTIVADO por decisión de
     * negocio: la fila "Distrito:" se dibuja siempre fija; en v1 se ve vacía y en v2 con el dato.
     */
    private static final boolean FLUJO_DINAMICO = false;

    private static void calcularFluidez(List<Contenedor> cs) {
        if (!FLUJO_DINAMICO) {
            for (Contenedor c : cs) c.fluido = false;
            return;
        }
        Contenedor fondo = null;
        int altoTitulo = 0, altoEncabezado = 0;
        for (Contenedor c : cs) {
            if (c.bandIndex < 0) continue;
            if (c.seccion.equals("background")) fondo = c;
            if (c.seccion.equals("title")) altoTitulo = Math.max(altoTitulo, c.alto);
            if (c.seccion.equals("pageHeader")) altoEncabezado = Math.max(altoEncabezado, c.alto);
        }
        for (Contenedor c : cs) {
            if (fondo == null || c == fondo) continue;
            int offset;
            switch (c.seccion) {
                case "title": offset = 0; break;
                case "pageHeader": offset = altoTitulo; break;
                case "columnHeader": offset = altoTitulo + altoEncabezado; break;
                default: continue;   // detalle, pie, etc.: se consideran fluidos
            }
            Contenedor banda = c;
            int dy = 0;
            while (banda.padre != null) { dy += banda.frameElem.y; banda = banda.padre; }
            int ini = offset + dy + 10, fin = offset + banda.alto + 30;
            for (Elem e : fondo.hijos) {
                if (e.x >= fondo.ancho || e.right() <= 0 || e.h > 400) continue;   // fuera de página o marco de página
                if (e.y > ini && e.y < fin) {
                    c.fluido = false;
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ DOM

    private void cargarHijos(Contenedor c, Element nodo, Map<Element, Integer> idxRe, List<Contenedor> todos) {

        for (Node n = nodo.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element el = (Element) n;
            String nombre = local(el);

            if (nombre.equals("elementGroup")) {          // grupo sin coordenadas: transparente
                cargarHijos(c, el, idxRe, todos);
                continue;
            }
            if (!ELEMENTOS.contains(nombre)) continue;

            Element re = hijo(el, "reportElement");
            if (re == null || !idxRe.containsKey(re)) continue;

            Elem e = new Elem();
            e.el = el;
            e.re = re;
            e.reIndex = idxRe.get(re);
            e.tipo = nombre;
            e.x = intAttr(re, "x", 0);
            e.y = intAttr(re, "y", 0);
            e.w = intAttr(re, "width", 0);
            e.h = intAttr(re, "height", 0);
            Element ex = hijo(el, "textFieldExpression");
            if (ex != null) e.expr = ex.getTextContent();
            Element tx = hijo(el, "text");
            if (tx != null) e.texto = tx.getTextContent();
            Element te = hijo(el, "textElement");
            Element fo = te != null ? hijo(te, "font") : null;
            if (fo != null) e.fontSize = intAttr(fo, "size", 10);
            e.positionType = re.getAttribute("positionType");
            e.stretchType = re.getAttribute("stretchType");
            e.estira = "true".equals(el.getAttribute("isStretchWithOverflow"))
                    || "StretchHeight".equals(el.getAttribute("textAdjust"));
            c.hijos.add(e);

            if (nombre.equals("frame")) {
                Contenedor f = new Contenedor();
                f.nodo = el;
                f.frameElem = e;
                f.padre = c;
                f.ancho = e.w;
                f.alto = e.h;
                todos.add(f);
                cargarHijos(f, el, idxRe, todos);
            }
        }
    }

    private static Document parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        f.setExpandEntityReferences(false);
        DocumentBuilder b = f.newDocumentBuilder();
        return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> elementosPorNombre(Element root, String nombre) {
        List<Element> out = new ArrayList<>();
        NodeList nl = root.getElementsByTagNameNS("*", nombre);
        for (int i = 0; i < nl.getLength(); i++) out.add((Element) nl.item(i));
        return out;
    }

    private static Element hijo(Element el, String nombre) {
        for (Node n = el.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element && local((Element) n).equals(nombre)) return (Element) n;
        }
        return null;
    }

    private static String local(Element el) {
        return el.getLocalName() != null ? el.getLocalName() : el.getTagName();
    }

    private static int intAttr(Element el, String a, int def) {
        String v = el.getAttribute(a);
        if (v == null || v.isEmpty()) return def;
        try {
            return (int) Math.round(Double.parseDouble(v));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    // ------------------------------------------------------------------ texto

    /** Posiciones [inicio, fin) de cada etiqueta de apertura <nombre ...> fuera de CDATA y comentarios. */
    private static List<int[]> tags(String xml, String nombre) {
        List<int[]> out = new ArrayList<>();
        Pattern p = Pattern.compile("<!\\[CDATA\\[[\\s\\S]*?]]>|<!--[\\s\\S]*?-->|<" + nombre + "(?=[\\s>/])[^>]*>");
        Matcher m = p.matcher(xml);
        while (m.find()) {
            if (m.group().startsWith("<" + nombre)) out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    private static String setAttr(String tag, String attr, int valor) {
        Pattern p = Pattern.compile("(\\s" + attr + "\\s*=\\s*\")[^\"]*(\")");
        Matcher m = p.matcher(tag);
        if (m.find()) {
            return tag.substring(0, m.start()) + m.group(1) + valor + m.group(2) + tag.substring(m.end());
        }
        // No existía (p.ej. y="0" omitido): agregarlo después del nombre de la etiqueta
        int sp = tag.indexOf(' ');
        if (sp < 0) sp = tag.indexOf('>');
        return tag.substring(0, sp) + " " + attr + "=\"" + valor + "\"" + tag.substring(sp);
    }

    private static String setAttrEnReportElement(String bloque, String attr, int valor) {
        Matcher m = Pattern.compile("<reportElement(?=[\\s>/])[^>]*>").matcher(bloque);
        if (!m.find()) return bloque;
        return bloque.substring(0, m.start()) + setAttr(m.group(), attr, valor) + bloque.substring(m.end());
    }

    private static String nuevoUuid(String bloque) {
        return bloque.replaceFirst("uuid=\"[^\"]*\"", "uuid=\"" + UUID.randomUUID() + "\"");
    }

    private static String aplicar(String texto, List<Cambio> cambios) {
        cambios.sort((a, b) -> a.inicio != b.inicio ? Integer.compare(b.inicio, a.inicio) : Integer.compare(b.fin, a.fin));
        StringBuilder sb = new StringBuilder(texto);
        for (Cambio c : cambios) sb.replace(c.inicio, c.fin, c.texto);
        return sb.toString();
    }
}
