package com.app.controller;

import com.app.service.ZipService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/zip")
public class ZipController {

    private final ZipService zipService;

    public ZipController(ZipService zipService) {
        this.zipService = zipService;
    }

    @PostMapping("/process")
    public ResponseEntity<byte[]> process(@RequestParam("file") MultipartFile file) throws Exception {

        byte[] result = zipService.processZip(file);

        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=processed.zip")
                .body(result);
    }
}