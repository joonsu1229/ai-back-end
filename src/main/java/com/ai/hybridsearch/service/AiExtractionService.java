package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.JobPosting;
import java.util.List;

public interface AiExtractionService {

    /**
     * HTML에서 여러 채용공고 추출
     *
     * @param html HTML 내용
     * @param siteName 사이트명
     * @return 추출된 채용공고 목록
     */
    List<JobPosting> extractJobsFromHtml(String html, String siteName);

    /**
     * 단일 채용공고 상세정보 추출
     *
     * @param baseJob 기본 채용공고 정보
     * @param detailHtml 상세 페이지 HTML
     * @return 상세정보가 추가된 채용공고
     */
    JobPosting extractJobDetailFromHtml(JobPosting baseJob, String detailHtml);

    /**
     * 현재 사용 중인 AI 모델 타입 반환
     *
     * @return 모델 타입 (openai, gemini, anthropic 등)
     */
    String getModelType();

    /**
     * AI 모델이 사용 가능한지 확인
     *
     * @return 모델 사용 가능 여부
     */
    boolean isModelAvailable();

    /**
     * 특정 HTML과 사이트에 대한 추출 신뢰도 반환
     *
     * @param html HTML 내용
     * @param siteName 사이트명
     * @return 추출 신뢰도 (0.0 ~ 1.0)
     */
    double getExtractionConfidence(String html, String siteName);

    /**
     * AI 모델 상태 정보 반환
     *
     * @return 모델 상태 정보
     */
    default ModelStatus getModelStatus() {
        return new ModelStatus(getModelType(), isModelAvailable(), getExtractionConfidence("", ""));
    }

    /**
     * 전처리된 텍스트에서 채용공고 추출
     * @param text 전처리된 텍스트
     * @param siteName 사이트 이름
     * @return 추출된 채용공고 목록
     */
    List<JobPosting> extractJobsFromText(String text, String siteName);


    /**
     * AI 모델 상태 정보 클래스
     */
    record ModelStatus(
            String modelType,
            boolean available,
            double defaultConfidence
    ) {}
}