package com.ai.hybridsearch.service;

import com.ai.hybridsearch.config.AiModelConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.googleai.GoogleAiEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmbeddingService {

    private final AiModelConfig config;
    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        try {
            log.info("=== EmbeddingService 초기화 시작 ===");
            log.info("Model Type: {}, Target Dimensions: {}", config.getModelType(), config.getTargetDimensions());

            switch (config.getModelType().toLowerCase()) {
                case "onnx" -> initOnnxModel();
                case "openai" -> {
                    validateOpenAiConfig();
                    initOpenAiModel();
                }
                case "gemini" -> {
                    validateGeminiConfig();
                    initGeminiModel();
                }
                default -> throw new IllegalArgumentException("지원하지 않는 모델 타입: " + config.getModelType());
            }

            log.info("=== EmbeddingService 초기화 완료 ===");
        } catch (Exception e) {
            log.error("=== EmbeddingService 초기화 실패 ===", e);
            throw e;
        }
    }

    private void initOnnxModel() {
        log.info("ONNX 모델 생성 시작...");
        try {
            embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            log.info("ONNX 모델 생성 완료");
        } catch (Exception e) {
            logDetailedException("ONNX 모델 생성", e);
            throw e;
        }
    }

    private void initOpenAiModel() {
        log.info("OpenAI 모델 생성 시작...");
        var builder = OpenAiEmbeddingModel.builder()
                .apiKey(config.getOpenai().getApiKey())
                .dimensions(config.getTargetDimensions());

        if (config.getOpenai().getEmbeddingModel() != null) {
            builder.modelName(config.getOpenai().getEmbeddingModel());
        }

        embeddingModel = builder.build();
        log.info("OpenAI 모델 생성 완료 - Model: {}", config.getOpenai().getEmbeddingModel() != null ? config.getOpenai().getEmbeddingModel() : "default");
    }

    private void initGeminiModel() {
        log.info("Gemini 모델 생성 시작...");
        var geminiConfig = config.getGemini();

        embeddingModel = GoogleAiEmbeddingModel.builder()
                .apiKey(geminiConfig.getApiKey())
                .modelName(geminiConfig.getEmbeddingModel())
                .build();

        log.info("Gemini 모델 생성 완료 - Model: {}", geminiConfig.getEmbeddingModel());
    }

    private void validateOpenAiConfig() {
        if (config.getOpenai() == null || config.getOpenai().getApiKey() == null || config.getOpenai().getApiKey().isBlank()) {
            throw new IllegalArgumentException("OpenAI API Key가 설정되지 않았습니다.");
        }
        log.info("OpenAI API Key 존재함");
    }

    private void validateGeminiConfig() {
        if (config.getGemini() == null || config.getGemini().getApiKey() == null || config.getGemini().getApiKey().isBlank()) {
            throw new IllegalArgumentException("Gemini API Key가 설정되지 않았습니다.");
        }
        log.info("Gemini API Key 존재함");
    }

    private void logDetailedException(String operation, Exception e) {
        log.error("{} 중 예외 발생", operation);
        log.error("예외 타입: {}", e.getClass().getName());
        log.error("예외 메시지: {}", e.getMessage());

        Throwable cause = e.getCause();
        while (cause != null) {
            log.error("원인: {} - {}", cause.getClass().getName(), cause.getMessage());
            cause = cause.getCause();
        }

        log.error("전체 스택 트레이스:", e);
    }

    public Embedding generateEmbedding(String text) {
        if (text == null || text.trim().isEmpty()) {
            throw new IllegalArgumentException("임베딩할 텍스트가 비어있습니다.");
        }
        try {
            return embeddingModel.embed(text).content();
        } catch (Exception e) {
            log.error("임베딩 생성 실패 - Text: {}", text, e);
            throw new RuntimeException("임베딩 생성에 실패했습니다.", e);
        }
    }

    public float[] embed(String text) {
        return generateEmbedding(text).vector();
    }

    public double cosineSimilarity(Embedding embedding1, Embedding embedding2) {
        float[] vector1 = embedding1.vector();
        float[] vector2 = embedding2.vector();

        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("임베딩 벡터의 차원이 다릅니다.");
        }

        double dotProduct = 0.0, normA = 0.0, normB = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            normA += vector1[i] * vector1[i];
            normB += vector2[i] * vector2[i];
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0.0) {
            log.warn("코사인 유사도 계산 중 영벡터 발견");
            return 0.0;
        }
        return dotProduct / denominator;
    }
}