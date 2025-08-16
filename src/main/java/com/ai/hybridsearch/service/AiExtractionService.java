package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.JobPosting;
import java.util.List;

/**
 * AI 기반 데이터 추출 서비스 인터페이스
 * 향후 다양한 AI 모델 (OpenAI, Claude, 로컬 모델 등) 지원을 위한 확장 가능한 구조
 */
public interface AiExtractionService {

    /**
     * HTML에서 여러 채용공고 추출
     * @param html 웹페이지 HTML 소스
     * @param siteName 사이트명
     * @return 추출된 채용공고 목록
     */
    List<JobPosting> extractJobsFromHtml(String html, String siteName);

    /**
     * 단일 채용공고 상세정보 추출
     * @param baseJob 기본 채용공고 정보
     * @param detailHtml 상세 페이지 HTML
     * @return 상세정보가 업데이트된 채용공고
     */
    JobPosting extractJobDetailFromHtml(JobPosting baseJob, String detailHtml);

    /**
     * AI 모델 타입 반환
     * @return 사용 중인 AI 모델 타입 (gemini, openai, claude 등)
     */
    String getModelType();

    /**
     * AI 모델 상태 확인
     * @return AI 모델 사용 가능 여부
     */
    boolean isModelAvailable();

    /**
     * 추출 정확도 측정을 위한 신뢰도 점수
     * @param html 분석할 HTML
     * @param siteName 사이트명
     * @return 0.0-1.0 사이의 신뢰도 점수
     */
    double getExtractionConfidence(String html, String siteName);
}