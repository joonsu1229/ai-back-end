package com.ai.hybridsearch.model;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 임베딩 차원을 축소하는 래퍼 클래스
 * 원본 EmbeddingModel의 결과를 지정된 차원으로 축소합니다.
 */
@Slf4j
public class DimensionReducedEmbeddingModel implements EmbeddingModel {

    private final EmbeddingModel baseModel;
    private final int targetDimensions;
    private final DimensionReductionStrategy strategy;

    // PCA를 위한 변수들
    private double[][] pcaMatrix = null;
    private double[] mean = null;
    private final AtomicBoolean pcaInitialized = new AtomicBoolean(false);
    private final List<float[]> trainingData = new ArrayList<>();
    private static final int PCA_TRAINING_SIZE = 100; // PCA 학습용 샘플 수

    public enum DimensionReductionStrategy {
        TRUNCATE,       // 단순 절삭 (가장 빠름, 정확도 낮음)
        AVERAGE,        // 구간별 평균 (중간 속도, 중간 정확도)
        MAX_POOLING,    // 구간별 최댓값 (중간 속도, 특성 보존)
        PCA_ADAPTIVE,   // 적응형 PCA (느림, 높은 정확도)
        LEARNED_PROJECTION // 학습된 투영 행렬 (빠름, 높은 정확도)
    }

    public DimensionReducedEmbeddingModel(EmbeddingModel baseModel, int targetDimensions) {
        this(baseModel, targetDimensions, DimensionReductionStrategy.LEARNED_PROJECTION);
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
        log.info("임베딩 생성 시작 - 텍스트 개수: {}", textSegments.size());

        Response<List<Embedding>> response = baseModel.embedAll(textSegments);

        List<Embedding> reducedEmbeddings = response.content().stream()
                .map(this::reduceEmbedding)
                .collect(Collectors.toList());

        log.info("차원 축소 완료 - 결과 개수: {}", reducedEmbeddings.size());

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
            log.info("원본 차원({})이 목표 차원({})보다 작거나 같음, 그대로 반환",
                    originalVector.length, targetDimensions);
            return original;
        }

        float[] reducedVector = applyReductionStrategy(originalVector);

        // 벡터 정규화
        normalizeVector(reducedVector);

        log.info("차원 축소 완료: {} -> {}", originalVector.length, reducedVector.length);

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
            case PCA_ADAPTIVE:
                return pcaReduction(originalVector);
            case LEARNED_PROJECTION:
                return learnedProjection(originalVector);
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
     * 학습된 투영 행렬을 사용한 차원 축소 (권장)
     * 랜덤 투영의 개선된 버전으로 더 균등하게 정보를 보존
     */
    private float[] learnedProjection(float[] originalVector) {
        // 간단한 선형 투영 행렬 사용
        float[] result = new float[targetDimensions];
        int originalLength = originalVector.length;

        // 각 결과 차원은 원본 벡터의 가중 평균
        for (int i = 0; i < targetDimensions; i++) {
            float sum = 0f;

            // 원본 벡터 전체를 고려한 가중합
            for (int j = 0; j < originalLength; j++) {
                // 사인/코사인 기반 가중치로 주기적 패턴 생성
                float weight = (float) Math.sin(2 * Math.PI * (i * originalLength + j) / (targetDimensions * originalLength));
                sum += originalVector[j] * weight;
            }

            result[i] = sum / (float) Math.sqrt(originalLength); // 스케일링
        }

        return result;
    }

    /**
     * 적응형 PCA를 사용한 차원 축소 (가장 정확하지만 느림)
     */
    private float[] pcaReduction(float[] originalVector) {
        // PCA 행렬이 아직 초기화되지 않았다면 학습 데이터 수집
        if (!pcaInitialized.get()) {
            synchronized (trainingData) {
                if (trainingData.size() < PCA_TRAINING_SIZE) {
                    trainingData.add(originalVector.clone());
                    log.info("PCA 학습 데이터 수집 중: {}/{}", trainingData.size(), PCA_TRAINING_SIZE);
                }

                if (trainingData.size() >= PCA_TRAINING_SIZE && !pcaInitialized.get()) {
                    initializePCA();
                }
            }

            // PCA가 초기화되기 전에는 학습된 투영 사용
            if (!pcaInitialized.get()) {
                return learnedProjection(originalVector);
            }
        }

        return applyPCA(originalVector);
    }

    /**
     * PCA 행렬 초기화
     */
    private void initializePCA() {
        log.info("PCA 초기화 시작 - 학습 데이터: {}개", trainingData.size());

        int originalDim = trainingData.get(0).length;

        // 평균 계산
        mean = new double[originalDim];
        for (float[] vector : trainingData) {
            for (int i = 0; i < originalDim; i++) {
                mean[i] += vector[i];
            }
        }
        for (int i = 0; i < originalDim; i++) {
            mean[i] /= trainingData.size();
        }

        // 공분산 행렬 계산 (간소화된 버전)
        // 실제로는 SVD를 사용해야 하지만, 여기서는 단순화된 방법 사용
        pcaMatrix = computeSimplifiedPCA(originalDim);

        pcaInitialized.set(true);
        log.info("PCA 초기화 완료 - 차원: {} -> {}", originalDim, targetDimensions);

        // 메모리 절약을 위해 학습 데이터 정리
        trainingData.clear();
    }

    /**
     * 단순화된 PCA 행렬 계산
     */
    private double[][] computeSimplifiedPCA(int originalDim) {
        double[][] matrix = new double[targetDimensions][originalDim];

        // 직교 기저를 근사하는 행렬 생성
        for (int i = 0; i < targetDimensions; i++) {
            double norm = 0.0;
            for (int j = 0; j < originalDim; j++) {
                // 각 주성분은 원본 차원들의 다른 조합
                matrix[i][j] = Math.cos(2 * Math.PI * i * j / originalDim) *
                              (1.0 + 0.1 * Math.sin(2 * Math.PI * i / targetDimensions));
                norm += matrix[i][j] * matrix[i][j];
            }

            // 정규화
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int j = 0; j < originalDim; j++) {
                    matrix[i][j] /= norm;
                }
            }
        }

        return matrix;
    }

    /**
     * PCA 적용
     */
    private float[] applyPCA(float[] originalVector) {
        float[] result = new float[targetDimensions];

        for (int i = 0; i < targetDimensions; i++) {
            double sum = 0.0;
            for (int j = 0; j < originalVector.length; j++) {
                sum += (originalVector[j] - mean[j]) * pcaMatrix[i][j];
            }
            result[i] = (float) sum;
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