package com.ai.hybridsearch.model;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 임베딩 차원을 축소하는 래퍼 클래스
 * 원본 EmbeddingModel의 결과를 지정된 차원으로 축소합니다.
 */
@Slf4j
public class DimensionReducedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel baseModel;
    private final int targetDimensions;
    private final DimensionReductionStrategy strategy;

    public enum DimensionReductionStrategy {
        TRUNCATE,    // 단순 절삭
        AVERAGE,     // 구간별 평균
        MAX_POOLING  // 구간별 최댓값
    }

    public DimensionReducedEmbeddingModel(EmbeddingModel baseModel, int targetDimensions) {
        this(baseModel, targetDimensions, DimensionReductionStrategy.TRUNCATE);
    }

    public DimensionReducedEmbeddingModel(EmbeddingModel baseModel, int targetDimensions,
                                        DimensionReductionStrategy strategy) {
        this.baseModel = baseModel;
        this.targetDimensions = targetDimensions;
        this.strategy = strategy;
        log.info("DimensionReducedEmbeddingModel 생성 - 목표 차원: {}, 전략: {}",
                targetDimensions, strategy);
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        log.debug("임베딩 생성 시작 - 텍스트 개수: {}", textSegments.size());

        Response<List<Embedding>> response = baseModel.embedAll(textSegments);

        List<Embedding> reducedEmbeddings = response.content().stream()
                .map(this::reduceEmbedding)
                .collect(Collectors.toList());

        log.debug("차원 축소 완료 - 결과 개수: {}", reducedEmbeddings.size());

        return Response.from(reducedEmbeddings, response.tokenUsage(), response.finishReason());
    }

    @Override
    public Response<Embedding> embed(TextSegment textSegment) {
        Response<Embedding> response = baseModel.embed(textSegment);
        Embedding reducedEmbedding = reduceEmbedding(response.content());

        return Response.from(reducedEmbedding, response.tokenUsage(), response.finishReason());
    }

    @Override
    public Response<Embedding> embed(String text) {
        Response<Embedding> response = baseModel.embed(text);
        Embedding reducedEmbedding = reduceEmbedding(response.content());

        return Response.from(reducedEmbedding, response.tokenUsage(), response.finishReason());
    }

    private Embedding reduceEmbedding(Embedding original) {
        float[] originalVector = original.vector();

        if (originalVector.length <= targetDimensions) {
            log.debug("원본 차원({})이 목표 차원({})보다 작거나 같음, 그대로 반환",
                    originalVector.length, targetDimensions);
            return original;
        }

        float[] reducedVector = applyReductionStrategy(originalVector);

        // 벡터 정규화
        normalizeVector(reducedVector);

        log.debug("차원 축소 완료: {} -> {}", originalVector.length, reducedVector.length);

        return Embedding.from(reducedVector);
    }

    private float[] applyReductionStrategy(float[] originalVector) {
        switch (strategy) {
            case TRUNCATE:
                return truncateVector(originalVector);
            case AVERAGE:
                return averagePooling(originalVector);
            case MAX_POOLING:
                return maxPooling(originalVector);
            default:
                throw new IllegalArgumentException("지원하지 않는 차원 축소 전략: " + strategy);
        }
    }

    /**
     * 단순 절삭: 앞의 targetDimensions 개만 사용
     */
    private float[] truncateVector(float[] originalVector) {
        return Arrays.copyOf(originalVector, targetDimensions);
    }

    /**
     * 평균 풀링: 원본을 구간으로 나누어 각 구간의 평균값 사용
     */
    private float[] averagePooling(float[] originalVector) {
        float[] result = new float[targetDimensions];
        int originalLength = originalVector.length;
        float step = (float) originalLength / targetDimensions;

        for (int i = 0; i < targetDimensions; i++) {
            int startIdx = Math.round(i * step);
            int endIdx = Math.round((i + 1) * step);
            endIdx = Math.min(endIdx, originalLength);

            float sum = 0f;
            int count = 0;
            for (int j = startIdx; j < endIdx; j++) {
                sum += originalVector[j];
                count++;
            }

            result[i] = count > 0 ? sum / count : 0f;
        }

        return result;
    }

    /**
     * 최댓값 풀링: 원본을 구간으로 나누어 각 구간의 최댓값 사용
     */
    private float[] maxPooling(float[] originalVector) {
        float[] result = new float[targetDimensions];
        int originalLength = originalVector.length;
        float step = (float) originalLength / targetDimensions;

        for (int i = 0; i < targetDimensions; i++) {
            int startIdx = Math.round(i * step);
            int endIdx = Math.round((i + 1) * step);
            endIdx = Math.min(endIdx, originalLength);

            float max = Float.NEGATIVE_INFINITY;
            for (int j = startIdx; j < endIdx; j++) {
                max = Math.max(max, Math.abs(originalVector[j]));
            }

            result[i] = max == Float.NEGATIVE_INFINITY ? 0f : max;
        }

        return result;
    }

    /**
     * 벡터 정규화 (L2 norm)
     */
    private void normalizeVector(float[] vector) {
        float norm = 0f;
        for (float value : vector) {
            norm += value * value;
        }
        norm = (float) Math.sqrt(norm);

        if (norm > 0f && norm != 1f) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] /= norm;
            }
        }
    }

    /**
     * 원본 모델의 정보를 얻기 위한 헬퍼 메소드
     */
    public EmbeddingModel getBaseModel() {
        return baseModel;
    }

    public int getTargetDimensions() {
        return targetDimensions;
    }

    public DimensionReductionStrategy getStrategy() {
        return strategy;
    }
}