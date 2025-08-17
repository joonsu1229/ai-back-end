package com.ai.hybridsearch;

import com.ai.hybridsearch.config.AiModelConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AiModelConfig.class)
public class HybridSearchApplication {
    public static void main(String[] args) {
        SpringApplication.run(HybridSearchApplication.class, args);
    }
}