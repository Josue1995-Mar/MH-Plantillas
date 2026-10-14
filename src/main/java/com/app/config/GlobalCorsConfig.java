package com.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class GlobalCorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/**")
                        .allowedOriginPatterns("*")  // 👈 CLAVE
                        .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                        .allowedHeaders("*")
                        // Para que el frontend pueda leer el resumen y el nombre del ZIP
                        .exposedHeaders("Content-Disposition", "X-Modo", "X-Plantillas",
                                "X-QR-Actualizados", "X-Distrito-Agregados", "X-Distrito-Dibujados", "X-Espacio-Ajustado", "X-Revisar", "X-Detalle", "X-Observaciones",
                                "X-Masivo-Total", "X-Masivo-Error", "X-Masivo-Revisar", "X-Masivo-Resumen")
                        .allowCredentials(false);
            }
        };
    }
}