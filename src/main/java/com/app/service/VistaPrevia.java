package com.app.service;

import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperExportManager;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.SimpleJasperReportsContext;
import net.sf.jasperreports.engine.query.JRXPathQueryExecuterFactory;
import net.sf.jasperreports.engine.util.xml.JRXPathExecuter;
import net.sf.jasperreports.engine.util.xml.JRXPathExecuterFactory;
import net.sf.jasperreports.repo.DefaultRepositoryService;
import net.sf.jasperreports.repo.FileRepositoryService;
import net.sf.jasperreports.repo.RepositoryService;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Genera PDFs de vista previa de cada plantilla principal con datos de ejemplo, para que el
 * agente vea si algo se deforma o se encima ANTES de entregarla al cliente:
 *   1) textos cortos y CON distrito
 *   2) textos largos y SIN distrito (como un documento anterior)
 *
 * Los datos de ejemplo se arman a partir de los campos (XPath) de la propia plantilla, así que
 * sirve para cualquier proyecto. Si una plantilla no se puede llenar, se informa el motivo y el
 * proceso sigue normalmente (la vista previa nunca bloquea la entrega del ZIP).
 */
public class VistaPrevia {

    public static final String CARPETA = "_VISTA_PREVIA_no_enviar_al_cliente";

    private static final String NS = "http://jasperreports.sourceforge.net/jasperreports";

    /** Ejecutor XPath del propio Java (así no se depende de Jaxen en el servidor). */
    public static class JdkXPathExecuterFactory implements JRXPathExecuterFactory, JRXPathExecuter {

        @Override
        public JRXPathExecuter getXPathExecuter() {
            return this;
        }

        private XPath xp() {
            return XPathFactory.newInstance().newXPath();
        }

        @Override
        public NodeList selectNodeList(Node contexto, String expr) throws JRException {
            try {
                return (NodeList) xp().evaluate(expr, contexto, XPathConstants.NODESET);
            } catch (Exception e) {
                throw new JRException(e);
            }
        }

        @Override
        public Object selectObject(Node contexto, String expr) throws JRException {
            try {
                try {
                    NodeList nl = (NodeList) xp().evaluate(expr, contexto, XPathConstants.NODESET);
                    return nl.getLength() > 0 ? nl.item(0) : null;
                } catch (XPathExpressionException noEsNodo) {
                    return xp().evaluate(expr, contexto, XPathConstants.NUMBER);   // count(...), etc.
                }
            } catch (Exception e) {
                throw new JRException(e);
            }
        }
    }

    /**
     * @param dir     carpeta donde están las plantillas (ya compiladas)
     * @param jrxmls  todas las plantillas del ZIP
     * @param salida  carpeta donde se escriben los PDFs
     * @return notas por plantilla (qué se generó o por qué no)
     */
    public Map<Path, List<String>> generar(Path dir, List<Path> jrxmls, Path salida) {

        Map<Path, List<String>> notas = new LinkedHashMap<>();
        Map<Path, String> textos = new LinkedHashMap<>();
        for (Path p : jrxmls) {
            try {
                textos.put(p, Files.readString(p));
            } catch (Exception e) {
                // ilegible: se ignora para la vista previa
            }
        }

        for (Path p : jrxmls) {
            String txt = textos.get(p);
            if (txt == null) continue;
            String base = p.getFileName().toString().replaceAll("(?i)\\.jrxml$", "");

            // Los subreportes (referenciados por otra plantilla) no se previsualizan solos
            boolean esSubreporte = false;
            for (Map.Entry<Path, String> otro : textos.entrySet()) {
                if (!otro.getKey().equals(p) && otro.getValue().contains(base + ".jasper")) {
                    esSubreporte = true;
                    break;
                }
            }
            if (esSubreporte) continue;
            if (!Pattern.compile("<queryString[^>]*language=\"(?i:xpath)\"").matcher(txt).find()) continue;
            // Solo las plantillas que muestran el encabezado del documento (emisor / receptor)
            if (!txt.contains("/emisor/") && !txt.contains("/receptor/")) continue;

            Path jasper = p.resolveSibling(base + ".jasper");
            List<String> n = new ArrayList<>();
            notas.put(p, n);
            if (!Files.exists(jasper)) {
                n.add("Vista previa: no se generó porque la plantilla no compiló");
                continue;
            }

            try {
                Files.createDirectories(salida);
                String carpetaRel = dir.relativize(p.getParent()).toString().replace('\\', '/');
                String prefijo = (carpetaRel.isEmpty() ? "" : carpetaRel.replace('/', '_') + "_") + base;

                String[][] variantes = {
                        {"1", prefijo + "_1_textos_cortos_con_distrito.pdf"},
                        {"2", prefijo + "_2_textos_largos_sin_distrito.pdf"}};
                for (String[] v : variantes) {
                    byte[] pdf = llenar(jasper, txt, p.getParent(), Integer.parseInt(v[0]));
                    Files.write(salida.resolve(v[1]), pdf);
                }
                n.add("Vista previa PDF generada (textos cortos con distrito / textos largos sin distrito)");
            } catch (Throwable e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                if (msg.length() > 160) msg = msg.substring(0, 160) + "...";
                n.add("Vista previa: no se pudo generar (" + msg + ")");
                System.out.println("VISTA PREVIA ERROR " + p + ": " + msg);
            }
        }
        return notas;
    }

    private byte[] llenar(Path jasper, String jrxml, Path carpeta, int variante) throws Exception {

        SimpleJasperReportsContext ctx = new SimpleJasperReportsContext();
        ctx.setProperty("net.sf.jasperreports.xpath.executer.factory", JdkXPathExecuterFactory.class.getName());
        List<RepositoryService> repos = new ArrayList<>();
        repos.add(new FileRepositoryService(ctx, carpeta.toString(), true));
        repos.add(new DefaultRepositoryService(ctx));
        ctx.setExtensions(RepositoryService.class, repos);

        Exception ultimo = null;
        // 1er intento con valores "realistas"; si alguna expresión de la plantilla espera un número
        // en un campo de texto, 2do intento con "1" en los campos desconocidos.
        for (boolean seguro : new boolean[]{false, true}) {
            try {
                Document datos = new Muestra(jrxml, variante, seguro).construir();
                Map<String, Object> params = new HashMap<>();
                params.put(JRXPathQueryExecuterFactory.PARAMETER_XML_DATA_DOCUMENT, datos);
                params.put("SUBREPORT_DIR", carpeta.toString() + File.separator);
                params.put("REPORT_LOCALE", new Locale("es", "SV"));
                // Llenar desde el archivo: así los subreportes ("tributos.jasper") e imágenes ("logo.png")
                // con ruta relativa se buscan junto a la plantilla, igual que en el sistema
                JasperPrint jp = JasperFillManager.getInstance(ctx).fill(jasper.toString(), params);
                return JasperExportManager.getInstance(ctx).exportToPdf(jp);
            } catch (Exception e) {
                ultimo = e;
            }
        }
        throw ultimo;
    }

    // ------------------------------------------------------------------ datos de ejemplo

    /** Construye un XML de ejemplo con todos los nodos que la plantilla lee. */
    static class Muestra {

        private final String jrxml;
        private final int variante;        // 1: cortos con distrito, 2: largos sin distrito
        private final boolean seguro;
        private Document doc;

        Muestra(String jrxml, int variante, boolean seguro) {
            this.jrxml = jrxml;
            this.variante = variante;
            this.seguro = seguro;
        }

        Document construir() throws Exception {

            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            Document plantilla = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(
                    jrxml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            doc = f.newDocumentBuilder().newDocument();

            String consulta = "";
            NodeList qs = plantilla.getElementsByTagNameNS(NS, "queryString");
            if (qs.getLength() > 0) consulta = qs.item(0).getTextContent().trim();

            // Filas de detalle (2) para los campos relativos a la consulta
            List<Element> filas = new ArrayList<>();
            if (consulta.startsWith("/") && !consulta.contains("(")) {
                String[] segs = consulta.substring(1).split("/");
                Element padre = asegurarRaiz(segs[0]);
                for (int i = 1; i < segs.length - 1; i++) padre = hijo(padre, limpio(segs[i]), true);
                if (segs.length > 1) {
                    for (int k = 0; k < 2; k++) {
                        Element fila = doc.createElement(limpio(segs[segs.length - 1]));
                        padre.appendChild(fila);
                        filas.add(fila);
                    }
                }
            }

            NodeList campos = plantilla.getElementsByTagNameNS(NS, "field");
            for (int i = 0; i < campos.getLength(); i++) {
                Element c = (Element) campos.item(i);
                String nombre = c.getAttribute("name");
                String clase = c.getAttribute("class");
                String xpath = null;
                NodeList props = c.getElementsByTagNameNS(NS, "property");
                for (int j = 0; j < props.getLength(); j++) {
                    Element pr = (Element) props.item(j);
                    if ("net.sf.jasperreports.xpath.field.expression".equals(pr.getAttribute("name"))) {
                        xpath = pr.getAttribute("value");
                    }
                }
                if (xpath == null) {
                    NodeList fd = c.getElementsByTagNameNS(NS, "fieldDescription");
                    if (fd.getLength() > 0) xpath = fd.item(0).getTextContent().trim();
                }
                if (xpath == null || xpath.isEmpty() || xpath.contains("(") || xpath.contains("//")
                        || xpath.contains("@") || xpath.contains("..")) continue;
                if (variante == 2 && xpath.endsWith("direccion_distrito")) continue;   // documento anterior

                if (xpath.startsWith("/")) {
                    agregar(null, xpath.substring(1), nombre, clase, false);
                } else {
                    for (Element fila : filas) agregar(fila, xpath, nombre, clase, true);
                }
            }
            if (doc.getDocumentElement() == null) asegurarRaiz("dte");
            return doc;
        }

        private void agregar(Element desde, String ruta, String nombre, String clase, boolean detalle) {
            String[] segs = ruta.split("/");
            Element actual = desde;
            for (int i = 0; i < segs.length; i++) {
                String seg = segs[i];
                Matcher pred = Pattern.compile("^([\\w.-]+)\\[\\s*([\\w.-]+)\\s*=\\s*['\"]([^'\"]*)['\"]\\s*]$").matcher(seg);
                boolean ultimo = i == segs.length - 1;
                if (actual == null) {
                    actual = asegurarRaiz(limpio(seg));
                    continue;
                }
                if (pred.find()) {
                    // p.ej. apendice[campo='Cuenta'] -> <apendice><campo>Cuenta</campo>...</apendice>
                    Element encontrado = null;
                    for (Node n = actual.getFirstChild(); n != null; n = n.getNextSibling()) {
                        if (n instanceof Element && ((Element) n).getTagName().equals(pred.group(1))) {
                            for (Node m = n.getFirstChild(); m != null; m = m.getNextSibling()) {
                                if (m instanceof Element && ((Element) m).getTagName().equals(pred.group(2))
                                        && m.getTextContent().equals(pred.group(3))) {
                                    encontrado = (Element) n;
                                }
                            }
                        }
                    }
                    if (encontrado == null) {
                        encontrado = doc.createElement(pred.group(1));
                        Element clave = doc.createElement(pred.group(2));
                        clave.setTextContent(pred.group(3));
                        encontrado.appendChild(clave);
                        actual.appendChild(encontrado);
                    }
                    actual = encontrado;
                } else {
                    actual = hijo(actual, limpio(seg), !ultimo);
                }
                if (ultimo && actual.getFirstChild() == null) {
                    actual.setTextContent(valor(nombre, limpio(seg), clase, detalle));
                }
            }
        }

        private Element asegurarRaiz(String nombre) {
            if (doc.getDocumentElement() == null) {
                Element r = doc.createElement(nombre.isEmpty() ? "dte" : nombre);
                doc.appendChild(r);
            }
            return doc.getDocumentElement();
        }

        /** Devuelve el hijo con ese nombre (lo crea si no existe). */
        private Element hijo(Element padre, String nombre, boolean reutilizar) {
            for (Node n = padre.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (n instanceof Element && ((Element) n).getTagName().equals(nombre)) return (Element) n;
            }
            Element e = doc.createElement(nombre);
            padre.appendChild(e);
            return e;
        }

        private static String limpio(String seg) {
            String s = seg.replaceAll("\\[.*]$", "").trim();
            return s.isEmpty() ? "x" : s;
        }

        private String valor(String nombre, String nodo, String clase, boolean detalle) {
            String k = (nombre + " " + nodo).toLowerCase();
            boolean largo = variante == 2;
            // En las filas de la tabla solo la descripción va larga (las columnas son angostas)
            if (detalle && !k.contains("descripcion")) largo = false;
            // El nombre del emisor es la empresa dueña de la plantilla: no varía entre documentos
            boolean nombreEmisor = nombre.toLowerCase().startsWith("e_") && k.contains("nombre") && !k.contains("comercial");

            if (clase.matches("java\\.lang\\.(Integer|Long|Short|Byte)")) return "1";
            if (clase.matches("java\\.(lang\\.(Double|Float|Number)|math\\.BigDecimal)")) return "10.00";
            if (clase.equals("java.lang.Boolean")) return "false";

            if (k.contains("fecha")) return "2026-10-13";
            if (k.contains("hora")) return "10:15:00";
            if (k.contains("correo") || k.contains("email")) {
                return largo ? "facturacion.electronica.sucursal.centro@empresadeejemplo.com.sv" : "correo@ejemplo.com";
            }
            if (k.matches(".*(total|monto|iva|precio|cantidad|descuento|venta|sub_total|subtotal|retencion|tributo|saldo|pago|valor|importe|percepcion|compra|exent|gravad|sujet).*")) {
                return "10.00";
            }
            if (k.contains("telefono")) return "22222222";
            if (k.matches("(.*[ _])?nit([ _].*)?") || k.contains("num_documento") || k.contains("numero_documento")) return "06140101011011";
            if (k.matches("(.*[ _])?nrc([ _].*)?")) return "123456";
            if (k.contains("codigo_generacion")) return "8F1C2A9B-0000-4D2E-9A11-123456789ABC";
            if (k.contains("numero_control")) return "DTE-01-M001P001-000000000000123";
            if (k.contains("sello")) return "2026ABCDEF0123456789ABCDEF0123456789";
            if (k.contains("version")) return "1";
            if (k.contains("ambiente")) return "01";
            if (k.contains("anulado")) return "0";
            if (k.matches(".*(total|monto|iva|precio|cantidad|descuento|venta|sub_total|subtotal|retencion|tributo|saldo|pago|valor|importe|percepcion|compra|exent|gravad|sujet).*")) {
                return "10.00";
            }
            if (k.contains("distrito")) return "San Salvador";
            if (k.contains("municipio")) return largo ? "SAN SALVADOR CENTRO" : "SAN SALVADOR CENTRO";
            if (k.contains("departamento")) return largo ? "LA LIBERTAD" : "SAN SALVADOR";
            if (k.contains("complemento") || k.contains("direccion")) {
                return largo ? "Km. 15 1/2 Carretera al Puerto de La Libertad, Cantón Ayagualo, Edificio 1, Bodega 4, frente a gasolinera, Zona Industrial Norte"
                        : "Col. Escalón, Calle 1 #10";
            }
            if (k.contains("nombre_comercial") || k.contains("sucursal")) {
                return largo ? "Sucursal Centro Comercial Plaza Mundo Soyapango Local 25 (21000) (W)" : "Sucursal Centro";
            }
            if (k.contains("actividad")) {
                return largo ? "Venta al por mayor de productos lácteos, embutidos, bebidas y otros productos alimenticios n.c.p."
                        : "Venta de repuestos";
            }
            if (k.contains("nombre") || k.contains("razon")) {
                return largo && !nombreEmisor ? "DISTRIBUIDORA Y COMERCIALIZADORA DE PRODUCTOS DEL OCCIDENTE SALVADOREÑO, SOCIEDAD ANÓNIMA DE CAPITAL VARIABLE"
                        : "EMPRESA DE EJEMPLO, S.A. DE C.V.";
            }
            if (k.contains("observacion")) {
                return largo ? "Observación de prueba con un texto largo para verificar que el campo crece sin encimarse con los demás elementos de la plantilla."
                        : "Sin observaciones";
            }
            if (k.contains("descripcion")) return largo ? "Producto de ejemplo con una descripción larga para probar el ajuste de línea" : "Producto de ejemplo";
            if (k.contains("tipo")) return "01";
            if (k.contains("letras")) return "DIEZ 00/100 USD";
            if (detalle) return "1";
            return seguro ? "1" : (largo ? "Texto de ejemplo largo para la vista previa" : "Ejemplo");
        }
    }
}
