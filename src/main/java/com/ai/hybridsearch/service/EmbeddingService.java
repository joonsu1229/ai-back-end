package com.ai.hybridsearch.service;

import com.ai.hybridsearch.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j  // 이것만 추가하면 됨
public class EmbeddingService {

    private final EmbeddingProperties properties;

    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        try {
            log.info("=== EmbeddingService 초기화 시작 ===");
            log.info("Model Type: {}", properties.getModelType());
            log.info("OpenAI API Key exists: {}", properties.getOpenai() != null && properties.getOpenai().getApiKey() != null);

            switch (properties.getModelType().toLowerCase()) {
                case "onnx" -> {
                    log.info("ONNX 모델 생성 시작...");
                    log.info("Java version: {}", System.getProperty("java.version"));
                    log.info("OS: {} {}", System.getProperty("os.name"), System.getProperty("os.arch"));
                    log.info("Available memory: {} MB", Runtime.getRuntime().maxMemory() / 1024 / 1024);
                    log.info("Current working directory: {}", System.getProperty("user.dir"));
                    log.info("Classpath: {}", System.getProperty("java.class.path"));

                    try {
                        embeddingModel = new AllMiniLmL6V2EmbeddingModel();
                        log.info("ONNX 모델 생성 완료");
                    } catch (Exception onnxException) {
                        log.error("ONNX 모델 생성 중 예외 발생");
                        log.error("예외 타입: {}", onnxException.getClass().getName());
                        log.error("예외 메시지: {}", onnxException.getMessage());

                        Throwable cause = onnxException.getCause();
                        while (cause != null) {
                            log.error("원인: {} - {}", cause.getClass().getName(), cause.getMessage());
                            cause = cause.getCause();
                        }

                        log.error("전체 스택 트레이스:", onnxException);
                        throw onnxException;
                    }
                }
                case "openai" -> {
                    log.info("OpenAI 모델 생성 시작...");
                    embeddingModel = OpenAiEmbeddingModel.builder()
                            .apiKey(properties.getOpenai().getApiKey())
                            .build();
                    log.info("OpenAI 모델 생성 완료");
                }
                default -> throw new IllegalArgumentException("Unsupported embedding model type: " + properties.getModelType());
            }

            log.info("=== EmbeddingService 초기화 완료 ===");
        } catch (Exception e) {
            log.error("=== EmbeddingService 초기화 실패 ===", e);
            throw e;
        }
    }

    public Embedding generateEmbedding(String text) {
        return embeddingModel.embed(text).content();
    }

    public float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    public double cosineSimilarity(Embedding embedding1, Embedding embedding2) {
        float[] vector1 = embedding1.vector();
        float[] vector2 = embedding2.vector();

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            normA += Math.pow(vector1[i], 2);
            normB += Math.pow(vector2[i], 2);
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
