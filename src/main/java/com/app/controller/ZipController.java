package com.app.controller;

import com.app.service.ZipService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/zip")
@CrossOrigin(origins = "*", exposedHeaders = {
        "Content-Disposition", "X-Modo", "X-Plantillas", "X-QR-Actualizados", "X-Distrito-Agregados",
        "X-Distrito-Dibujados", "X-Espacio-Ajustado", "X-Revisar", "X-Detalle", "X-Observaciones",
        "X-Masivo-Total", "X-Masivo-Error", "X-Masivo-Revisar", "X-Masivo-Resumen"
})
public class ZipController {

    private final ZipService zipService;

    public ZipController(ZipService zipService) {
        this.zipService = zipService;
    }

    /**
     * 🔴 ENDPOINT REAL (ZIP)
     *
     * Parámetro "modo" (opcional):
     *   - auto     → (por defecto) detecta cómo viene cada plantilla y aplica lo que corresponde
     *   - qr       → solo actualiza la URL del QR
     *   - completo → actualiza la URL del QR + agrega distrito en emisor y receptor
     *   - separado → actualiza la URL del QR + dibuja la fila "Distrito:" en emisor y receptor
     *   - mixto    → emisor unido (ordena la dirección) + dibuja "Distrito:" solo en el receptor
     */
    @PostMapping("/process")
    public ResponseEntity<?> process(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "modo", required = false) String modo) throws Exception {

        ZipService.Modo m;
        try {
            m = ZipService.Modo.from(modo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }

        // Un "ZIP de ZIPs" (el ZIP de cada cliente adentro) se procesa como masivo
        byte[] datos = file.getBytes();
        if (ZipService.esZipDeZips(datos)) {
            return respuestaMasiva(zipService.procesarMasivo(
                    List.of(file.getOriginalFilename() == null ? "archivo.zip" : file.getOriginalFilename()),
                    List.of(datos), m));
        }

        ZipService.Resultado r = zipService.procesar(datos, m);

        String nombre = (m == ZipService.Modo.QR) ? "processed_qr.zip"
                : (m == ZipService.Modo.SEPARADO) ? "processed_distrito_separado.zip"
                : (m == ZipService.Modo.MIXTO) ? "processed_emisor_unido_receptor_separado.zip"
                : (m == ZipService.Modo.AUTO) ? "processed_auto.zip"
                : "processed_qr_distrito.zip";

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=" + nombre)
                .header("X-Modo", r.modo.name().toLowerCase())
                .header("X-Plantillas", String.valueOf(r.plantillas))
                .header("X-QR-Actualizados", String.valueOf(r.qrActualizados))
                .header("X-Distrito-Agregados", String.valueOf(r.distritoAgregados))
                .header("X-Distrito-Dibujados", String.valueOf(r.distritoDibujados))
                .header("X-Espacio-Ajustado", String.valueOf(r.espacioAjustado))
                .header("X-Revisar", URLEncoder.encode(String.join("\n", r.revisar), StandardCharsets.UTF_8))
                .header("X-Observaciones", String.valueOf(r.observacionesActualizadas))
                .header("X-Detalle", URLEncoder.encode(String.join("\n", r.detalle), StandardCharsets.UTF_8))
                .body(r.zip);
    }

    /**
     * 📦 MASIVO: varios ZIP a la vez (uno por cliente) y/o ZIPs que contienen el ZIP de cada cliente.
     * Devuelve un ZIP con "procesados/<cliente>.zip" y RESUMEN_MASIVO.csv / .txt.
     */
    @PostMapping("/masivo")
    public ResponseEntity<?> masivo(@RequestParam("files") List<MultipartFile> files,
                                    @RequestParam(value = "modo", required = false) String modo) throws Exception {
        ZipService.Modo m;
        try {
            m = ZipService.Modo.from(modo);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .header("Content-Type", "text/plain; charset=UTF-8")
                    .body(e.getMessage().getBytes(StandardCharsets.UTF_8));
        }
        return respuestaMasiva(zipService.procesarMasivo(files, m));
    }

    private ResponseEntity<byte[]> respuestaMasiva(ZipService.ResultadoMasivo rm) {
        // Resumen corto por cliente para la pantalla (el completo va en RESUMEN_MASIVO.csv).
        // Se limita el tamaño para no pasar el máximo de cabeceras HTTP.
        StringBuilder sb = new StringBuilder();
        int incluidas = 0;
        for (ZipService.FilaMasivo f : rm.filas) {
            String linea = String.join("|", f.archivo.replace("|", "/"), f.estado.replace("|", "/"),
                    String.valueOf(f.plantillas), String.valueOf(f.qr), String.valueOf(f.distritoOrdenado),
                    String.valueOf(f.distritoDibujado), String.valueOf(f.observaciones), String.valueOf(f.revisar.size()));
            if (sb.length() + linea.length() > 5000) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(linea);
            incluidas++;
        }
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=procesado_masivo.zip")
                .header("X-Modo", rm.modo.name().toLowerCase())
                .header("X-Masivo-Total", String.valueOf(rm.filas.size()))
                .header("X-Masivo-Error", String.valueOf(rm.conError()))
                .header("X-Masivo-Revisar", String.valueOf(rm.conRevisar()))
                .header("X-Masivo-Resumen", URLEncoder.encode(sb.toString(), StandardCharsets.UTF_8)
                        + (incluidas < rm.filas.size() ? "&mas=" + (rm.filas.size() - incluidas) : ""))
                .body(rm.zip);
    }

    // 🧪 ENDPOINT DE PRUEBA (IMPORTANTE PARA DEBUG)
    @PostMapping("/test")
    public ResponseEntity<String> test(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok("RECIBIDO: " + file.getOriginalFilename());
    }
}
