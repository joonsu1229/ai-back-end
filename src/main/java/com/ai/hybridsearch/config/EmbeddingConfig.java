package com.ai.hybridsearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "langchain")
public class EmbeddingConfig {
    private String modelType = "onnx"; // 기본값을 무료 모델로 설정
    private Integer targetDimensions = 768; // 기본 차원 설정
    private OpenAI openai;
    private Gemini gemini;

    @Data
    public static class OpenAI {
        private String apiKey;
        private String model;
    }

    @Data
    public static class Gemini {
        private String apiKey;
        private String model;
    }
}