package com.ai.hybridsearch.service.factory;

import com.ai.hybridsearch.config.AiModelConfig;
import com.ai.hybridsearch.service.AiExtractionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI 추출 서비스 팩토리
 * 설정에 따라 적절한 AI 모델 서비스를 제공하고 관리
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AiExtractionServiceFactory {

    private final AiModelConfig aiModelConfig;
    private final List<AiExtractionService> extractionServices;

    // 서비스 맵 캐시 (성능 최적화)
    private Map<String, AiExtractionService> serviceMap;

    @PostConstruct
    public void init() {
        try {
            log.info("=== AiExtractionServiceFactory 초기화 시작 ===");
            initializeServiceMap();
            log.info("사용 가능한 AI 서비스: {}", serviceMap.keySet());
            log.info("=== AiExtractionServiceFactory 초기화 완료 ===");
        } catch (Exception e) {
            log.error("AiExtractionServiceFactory 초기화 실패", e);
            throw new RuntimeException("AI 추출 서비스 팩토리 초기화 실패", e);
        }
    }

    /**
     * 서비스 맵 초기화
     */
    private void initializeServiceMap() {
        serviceMap = extractionServices.stream()
                .filter(service -> {
                    try {
                        return service.isModelAvailable();
                    } catch (Exception e) {
                        log.warn("서비스 가용성 확인 실패: {}", service.getModelType(), e);
                        return false;
                    }
                })
                .collect(Collectors.toMap(
                        service -> service.getModelType().toLowerCase(),
                        Function.identity(),
                        (existing, replacement) -> {
                            log.warn("중복된 모델 타입 발견: {}, 기존 서비스 사용", existing.getModelType());
                            return existing;
                        }
                ));
    }

    /**
     * 기본 모델 타입 결정
     */
    private String determineDefaultModelType() {
        // 1. 설정에서 명시적으로 지정된 경우
        if (aiModelConfig.getModelType() != null && !aiModelConfig.getModelType().isBlank()) {
            String modelType = aiModelConfig.getModelType().toLowerCase();
            if (serviceMap.containsKey(modelType)) {
                return modelType;
            }
            log.warn("설정된 모델 타입 {}을 사용할 수 없음, 대안 모델 선택", modelType);
        }

        // 2. 사용 가능한 API 키를 기반으로 자동 결정
        if (aiModelConfig.getOpenai() != null &&
                aiModelConfig.getOpenai().getApiKey() != null &&
                !aiModelConfig.getOpenai().getApiKey().isBlank() &&
                serviceMap.containsKey("openai")) {
            log.info("OpenAI 서비스 사용 가능, OpenAI 모델 사용");
            return "openai";
        }

        if (aiModelConfig.getGemini() != null &&
                aiModelConfig.getGemini().getApiKey() != null &&
                !aiModelConfig.getGemini().getApiKey().isBlank() &&
                serviceMap.containsKey("gemini")) {
            log.info("Gemini 서비스 사용 가능, Gemini 모델 사용");
            return "gemini";
        }

        if (aiModelConfig.getAnthropic() != null &&
                aiModelConfig.getAnthropic().getApiKey() != null &&
                !aiModelConfig.getAnthropic().getApiKey().isBlank() &&
                serviceMap.containsKey("anthropic")) {
            log.info("Anthropic 서비스 사용 가능, Anthropic 모델 사용");
            return "anthropic";
        }

        // 3. 사용 가능한 첫 번째 서비스 선택
        if (!serviceMap.isEmpty()) {
            String firstAvailable = serviceMap.keySet().iterator().next();
            log.info("기본 모델로 첫 번째 사용 가능한 서비스 사용: {}", firstAvailable);
            return firstAvailable;
        }

        throw new RuntimeException("사용 가능한 AI 서비스가 없습니다.");
    }

    /**
     * 현재 설정된 기본 AI 추출 서비스 반환
     */
    public AiExtractionService getDefaultService() {
        String modelType = determineDefaultModelType();
        return getServiceByType(modelType)
                .orElseThrow(() -> new RuntimeException("기본 AI 추출 서비스를 찾을 수 없습니다: " + modelType));
    }

    /**
     * 특정 모델 타입의 AI 추출 서비스 반환
     */
    public Optional<AiExtractionService> getServiceByType(String modelType) {
        if (serviceMap == null) {
            initializeServiceMap();
        }

        AiExtractionService service = serviceMap.get(modelType.toLowerCase());

        if (service != null && service.isModelAvailable()) {
            return Optional.of(service);
        }

        return Optional.empty();
    }

    /**
     * 사용 가능한 모든 AI 추출 서비스 반환
     */
    public List<AiExtractionService> getAvailableServices() {
        return extractionServices.stream()
                .filter(service -> {
                    try {
                        return service.isModelAvailable();
                    } catch (Exception e) {
                        log.warn("서비스 가용성 확인 실패: {}", service.getModelType(), e);
                        return false;
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 최적의 AI 추출 서비스 반환 (신뢰도 기반)
     */
    public AiExtractionService getBestService(String html, String siteName) {
        return extractionServices.stream()
                .filter(service -> {
                    try {
                        return service.isModelAvailable();
                    } catch (Exception e) {
                        log.warn("서비스 가용성 확인 실패: {}", service.getModelType(), e);
                        return false;
                    }
                })
                .max((s1, s2) -> {
                    try {
                        return Double.compare(
                                s1.getExtractionConfidence(html, siteName),
                                s2.getExtractionConfidence(html, siteName)
                        );
                    } catch (Exception e) {
                        log.warn("신뢰도 계산 실패", e);
                        return 0;
                    }
                })
                .orElse(getDefaultService());
    }

    /**
     * 폴백 서비스 반환 (기본 서비스가 실패했을 때 사용)
     */
    public Optional<AiExtractionService> getFallbackService(String currentModelType) {
        return extractionServices.stream()
                .filter(service -> !service.getModelType().equalsIgnoreCase(currentModelType))
                .filter(service -> {
                    try {
                        return service.isModelAvailable();
                    } catch (Exception e) {
                        log.warn("폴백 서비스 가용성 확인 실패: {}", service.getModelType(), e);
                        return false;
                    }
                })
                .findFirst();
    }

    /**
     * 서비스 상태 정보 반환
     */
    public Map<String, ServiceStatus> getServiceStatuses() {
        Map<String, ServiceStatus> statuses = new HashMap<>();

        for (AiExtractionService service : extractionServices) {
            try {
                boolean available = service.isModelAvailable();
                statuses.put(service.getModelType(), new ServiceStatus(
                        service.getModelType(),
                        available,
                        available ? "Available" : "Not Available",
                        System.currentTimeMillis()
                ));
            } catch (Exception e) {
                statuses.put(service.getModelType(), new ServiceStatus(
                        service.getModelType(),
                        false,
                        "Error: " + e.getMessage(),
                        System.currentTimeMillis()
                ));
            }
        }

        return statuses;
    }

    /**
     * 설정된 모든 모델 타입 반환
     */
    public List<String> getConfiguredModelTypes() {
        return extractionServices.stream()
                .map(AiExtractionService::getModelType)
                .collect(Collectors.toList());
    }

    /**
     * 서비스 재초기화 (런타임에 설정이 변경된 경우)
     */
    public void reinitialize() {
        log.info("AI 추출 서비스 팩토리 재초기화 시작");
        try {
            initializeServiceMap();
            log.info("AI 추출 서비스 팩토리 재초기화 완료");
        } catch (Exception e) {
            log.error("AI 추출 서비스 팩토리 재초기화 실패", e);
            throw new RuntimeException("AI 추출 서비스 팩토리 재초기화 실패", e);
        }
    }

    /**
     * 특정 모델이 설정되어 있는지 확인
     */
    public boolean isModelConfigured(String modelType) {
        String type = modelType.toLowerCase();
        switch (type) {
            case "openai":
                return aiModelConfig.getOpenai() != null &&
                        aiModelConfig.getOpenai().getApiKey() != null &&
                        !aiModelConfig.getOpenai().getApiKey().isBlank();
            case "gemini":
                return aiModelConfig.getGemini() != null &&
                        aiModelConfig.getGemini().getApiKey() != null &&
                        !aiModelConfig.getGemini().getApiKey().isBlank();
            case "anthropic":
                return aiModelConfig.getAnthropic() != null &&
                        aiModelConfig.getAnthropic().getApiKey() != null &&
                        !aiModelConfig.getAnthropic().getApiKey().isBlank();
            default:
                return false;
        }
    }

    /**
     * 팩토리 설정 정보 반환 (디버깅/모니터링용)
     */
    public Map<String, Object> getFactoryInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("configuredServices", getConfiguredModelTypes());
        info.put("availableServices", getAvailableServices().stream()
                .map(AiExtractionService::getModelType)
                .collect(Collectors.toList()));
        info.put("defaultModelType", determineDefaultModelType());
        info.put("serviceStatuses", getServiceStatuses());
        return info;
    }

    /**
     * 서비스 상태 정보를 담는 내부 클래스
     */
    public static class ServiceStatus {
        private final String modelType;
        private final boolean available;
        private final String status;
        private final long timestamp;

        public ServiceStatus(String modelType, boolean available, String status, long timestamp) {
            this.modelType = modelType;
            this.available = available;
            this.status = status;
            this.timestamp = timestamp;
        }

        public String getModelType() { return modelType; }
        public boolean isAvailable() { return available; }
        public String getStatus() { return status; }
        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("ServiceStatus{modelType='%s', available=%s, status='%s', timestamp=%d}",
                    modelType, available, status, timestamp);
        }
    }
}