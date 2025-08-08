package com.ai.hybridsearch.service;

import com.ai.hybridsearch.config.EmbeddingConfig;
import com.ai.hybridsearch.model.DimensionReducedEmbeddingModel;
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

    private final EmbeddingConfig config;
    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        try {
            log.info("=== EmbeddingService 초기화 시작 ===");
            log.info("Model Type: {}, Dimensions reduced to: {}", config.getModelType(), config.getTargetDimensions());

            switch (config.getModelType().toLowerCase()) {
                case "onnx" -> initOnnxModel();
                case "openai" -> initOpenAiModel();
                case "gemini" -> initGeminiModel();
                default -> throw new IllegalArgumentException("Unsupported embedding model type: " + config.getModelType());
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
        validateApiKey(config.getOpenai() != null ? config.getOpenai().getApiKey() : null, "OpenAI");

        var builder = OpenAiEmbeddingModel.builder()
                .apiKey(config.getOpenai().getApiKey())
                .dimensions(config.getTargetDimensions());

        // 모델명이 설정되어 있으면 사용
        if (config.getOpenai().getModel() != null) {
            builder.modelName(config.getOpenai().getModel());
        }

        embeddingModel = builder.build();
        log.info("OpenAI 모델 생성 완료 - Model: {}",
                config.getOpenai().getModel() != null ? config.getOpenai().getModel() : "default");
    }

    private void initGeminiModel() {
        log.info("Gemini 모델 생성 시작...");

        var geminiconfig = config.getGemini();
        if (geminiconfig == null || geminiconfig.getApiKey() == null) {
            throw new IllegalArgumentException("Gemini API 키가 설정되지 않았습니다.");
        }

        // 차원 축소를 위한 래퍼 모델 사용
        var baseModel = GoogleAiEmbeddingModel.builder()
                .apiKey(geminiconfig.getApiKey())
                .modelName(geminiconfig.getModel())
                .build();

        // 기본 - 학습된 투영 (권장)
        //embeddingModel = new DimensionReducedEmbeddingModel(baseModel, config.getTargetDimensions());

        // 최고 정확도가 필요한 경우
        embeddingModel = new DimensionReducedEmbeddingModel(baseModel, config.getTargetDimensions(),
            DimensionReducedEmbeddingModel.DimensionReductionStrategy.PCA_ADAPTIVE);

        // 빠른 처리가 필요한 경우
/*        embeddingModel = new DimensionReducedEmbeddingModel(baseModel, config.getTargetDimensions(),
            DimensionReducedEmbeddingModel.DimensionReductionStrategy.AVERAGE);    */


        log.info("Gemini 모델 생성 완료 - Model: {}", geminiconfig.getModel());
    }

    private void validateApiKey(String apiKey, String provider) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            throw new IllegalArgumentException(provider + " API Key가 설정되지 않았습니다.");
        }
        log.info("{} API Key exists: true", provider);
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

    /**
     * 텍스트의 임베딩 생성
     * @param text 임베딩할 텍스트
     * @return Embedding 객체
     */
    public Embedding generateEmbedding(String text) {
        try {
            return embeddingModel.embed(text).content();
        } catch (Exception e) {
            log.error("임베딩 생성 실패 - Text: {}", text, e);
            throw new RuntimeException("임베딩 생성에 실패했습니다.", e);
        }
    }

    /**
     * 텍스트의 임베딩 벡터 생성
     * @param text 임베딩할 텍스트
     * @return float 배열 벡터
     */
    public float[] embed(String text) {
        return generateEmbedding(text).vector();
    }

    /**
     * 두 임베딩 간의 코사인 유사도 계산
     * @param embedding1 첫 번째 임베딩
     * @param embedding2 두 번째 임베딩
     * @return 코사인 유사도 (-1 ~ 1)
     */
    public double cosineSimilarity(Embedding embedding1, Embedding embedding2) {
        float[] vector1 = embedding1.vector();
        float[] vector2 = embedding2.vector();

        if (vector1.length != vector2.length) {
            throw new IllegalArgumentException("임베딩 벡터의 차원이 다릅니다.");
        }

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            normA += Math.pow(vector1[i], 2);
            normB += Math.pow(vector2[i], 2);
        }

        double denominator = Math.sqrt(normA) * Math.sqrt(normB);
        if (denominator == 0) {
            return 0.0; // 영벡터인 경우
        }

        return dotProduct / denominator;
    }

    /**
     * 두 텍스트 간의 유사도 직접 계산
     * @param text1 첫 번째 텍스트
     * @param text2 두 번째 텍스트
     * @return 코사인 유사도
     */
    public double similarity(String text1, String text2) {
        Embedding embedding1 = generateEmbedding(text1);
        Embedding embedding2 = generateEmbedding(text2);
        return cosineSimilarity(embedding1, embedding2);
    }

    /**
     * 현재 사용 중인 모델 타입 반환
     * @return 모델 타입 문자열
     */
    public String getCurrentModelType() {
        return config.getModelType();
    }

    /**
     * 임베딩 모델의 차원 수 반환
     * @return 벡터 차원 수
     */
    public int getDimension() {
        // 샘플 텍스트로 차원 확인
        return embed("sample").length;
    }
}