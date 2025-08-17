package com.ai.hybridsearch.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "crawling.ai")
public class AiCrawlingConfig {

    /**
     * AI 기반 크롤링 활성화 여부
     */
    private boolean enabled = true;

    /**
     * 사이트별 최대 크롤링 페이지 수
     */
    private int maxPagesPerSite = 3;

    /**
     * 페이지 로드 실패시 최대 재시도 횟수
     */
    private int maxRetries = 2;

    /**
     * 페이지 간 최소 지연 시간 (ms)
     */
    private long delayMinMs = 1000;

    /**
     * 페이지 간 최대 지연 시간 (ms)
     */
    private long delayMaxMs = 2000;

    /**
     * 상세 페이지 병렬 처리 스레드 수
     */
    private int parallelDetailThreads = 4;

    /**
     * AI 모델 응답 타임아웃 (초)
     */
    private int aiResponseTimeoutSeconds = 30;

    /**
     * HTML 전처리시 최대 길이 제한 (AI 토큰 제한 대응)
     */
    private int maxHtmlLengthForAi = 8000;

    /**
     * AI 추출 실패시 폴백 사용 여부
     */
    private boolean enableFallback = true;

    /**
     * 사이트별 AI 모델 설정 (향후 확장용)
     */
    private SiteSpecificConfig siteSpecific = new SiteSpecificConfig();

    @Data
    public static class SiteSpecificConfig {
        /**
         * 사이트별 다른 모델 사용 설정 (향후 확장)
         */
        private String saraminModel = "gemini-1.5-flash";
        private String jobkoreaModel = "gemini-1.5-flash";
        private String wantedModel = "gemini-1.5-flash";

        /**
         * 사이트별 프롬프트 온도 설정
         */
        private double defaultTemperature = 0.1;
        private double detailExtractionTemperature = 0.05; // 상세 추출시 더 일관성 있게
    }
}