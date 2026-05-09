
package com.app.service;

import net.sf.jasperreports.engine.JasperCompileManager;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

@Service
public class ZipService {

    private static final String OLD_URL = "https://webapp.dtes.mh.gob.sv";
    private static final String NEW_URL = "https://admin.factura.gob.sv";

    public byte[] processZip(MultipartFile file) throws Exception {

        if (file.getOriginalFilename() == null ||
                !file.getOriginalFilename().toLowerCase().endsWith(".zip")) {

            throw new RuntimeException("Solo ZIP permitido");
        }

        Path tempDir = Files.createTempDirectory("jasper");

        try {

            File zip = tempDir.resolve("input.zip").toFile();

            file.transferTo(zip);

            unzip(zip, tempDir.toFile());

            // REEMPLAZAR URL
           Files.walk(tempDir) .filter(p -> p.toString().endsWith(".jrxml")) .forEach(this::replaceUrl);


            // ELIMINAR JASPER VIEJOS
             Files.walk(tempDir) .filter(p -> p.toString().endsWith(".jasper")) .forEach(p -> { try { Files.delete(p); System.out.println("JASPER ELIMINADO: " + p); } catch (Exception e) { e.printStackTrace(); } });

            // COMPILAR JRXML
            Files.walk(tempDir)
                    .filter(p -> p.toString().endsWith(".jrxml"))
                    .forEach(this::compile);

            // CREAR ZIP FINAL
            File outputZip = tempDir.resolve("output.zip").toFile();

            zipFolder(tempDir.toFile(), outputZip);

            return Files.readAllBytes(outputZip.toPath());

        } finally {

            FileSystemUtils.deleteRecursively(tempDir);
        }
    }

    private void replaceUrl(Path file) {

        try {

            System.out.println("Modificando URL: " + file);

            String content = Files.readString(file);

            content = content.replace(OLD_URL, NEW_URL);

            Files.writeString(file, content);

            System.out.println("URL modificada OK");

        } catch (Exception e) {

            System.out.println("ERROR MODIFICANDO URL:");
            System.out.println(file);

            e.printStackTrace();
        }
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



    private void unzip(File zipFile, File destDir) throws IOException {

        byte[] buffer = new byte[1024];

        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));

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
                                new ZipEntry(source.toPath().relativize(p).toString());

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

