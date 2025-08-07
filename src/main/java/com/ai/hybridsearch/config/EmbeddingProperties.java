package com.ai.hybridsearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@ConfigurationProperties(prefix = "langchain")
public class EmbeddingProperties {
    private String modelType; // "onnx" or "openai"
    private OpenAI openai;

    @Data
    public static class OpenAI {
        private String apiKey;
    }
}
