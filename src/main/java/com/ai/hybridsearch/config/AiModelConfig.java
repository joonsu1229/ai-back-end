package com.ai.hybridsearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "langchain")
public class AiModelConfig {
    private String modelType = "onnx"; // 임베딩 모델 타입 (기본값)
    private String aiModelType = "gemini"; // AI 추출 모델 타입 (기본값)
    private Integer targetDimensions = 384;

    private OpenAiConfig openai;
    private GeminiConfig gemini;
    private AnthropicConfig anthropic;

    @Data
    public static class OpenAiConfig {
        private String apiKey;
        private String embeddingModel;
        private String aiChatModel;
        private String outputMaxToken;
        private String inputMaxToken ;
    }

    @Data
    public static class GeminiConfig {
        private String apiKey;
        private String embeddingModel;
        private String aiChatModel;
        private String outputMaxToken;
        private String inputMaxToken;
    }

    @Data
    public static class AnthropicConfig {
        private String apiKey;
        private String aiChatModel;
        private String outputMaxToken;
        private String inputMaxToken;
    }
}