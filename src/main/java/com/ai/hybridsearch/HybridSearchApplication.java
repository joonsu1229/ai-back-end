package com.ai.hybridsearch;

import com.ai.hybridsearch.config.EmbeddingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(EmbeddingProperties.class)
public class HybridSearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(HybridSearchApplication.class, args);
    }
}