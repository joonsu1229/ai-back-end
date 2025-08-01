package com.ai.hybridsearch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**") // 또는 "/**" 로 전체 경로 허용 가능
                .allowedOrigins("https://jsai.secretroom.kro.kr")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false) // 쿠키 필요 없으면 false
                .maxAge(3600); // pre-flight 캐시 시간 (초)
    }
}