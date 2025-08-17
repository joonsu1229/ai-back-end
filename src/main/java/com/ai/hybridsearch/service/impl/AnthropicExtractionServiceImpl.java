package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.AiCrawlingConfig;
import com.ai.hybridsearch.config.AiModelConfig;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.service.AiExtractionService;
import com.ai.hybridsearch.util.PartialJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Anthropic Claude 기반 채용공고 추출 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "langchain.model-type", havingValue = "anthropic")
public class AnthropicExtractionServiceImpl implements AiExtractionService {

    private final AiModelConfig aiModelConfig;
    private final AiCrawlingConfig crawlingConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatLanguageModel chatModel;
    private ChatLanguageModel detailChatModel;

    // 재시도 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 20000; // Claude는 상대적으로 빠른 복구
    private static final long MAX_RETRY_DELAY_MS = 180000; // 3분

    // HTML 정리를 위한 패턴들
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");

    private PartialJsonParser partialJsonParser;

    @PostConstruct
    public void init() {
        try {
            log.info("=== Anthropic Claude 추출 서비스 초기화 시작 ===");

            validateAnthropicConfig();

//            // 기본 채팅 모델 초기화 (목록 추출용)
//            chatModel = AnthropicChatModel.builder()
//                    .apiKey(aiModelConfig.getAnthropic().getApiKey())
//                    .modelName(getModelName())
//                    .temperature(crawlingConfig.getSiteSpecific().getDefaultTemperature())
//                    .maxTokens(getOutputMaxTokens())
//                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
//                    .build();
//
//            // 상세 추출용 모델 (더 정확하고 일관성 있게)
//            detailChatModel = AnthropicChatModel.builder()
//                    .apiKey(aiModelConfig.getAnthropic().getApiKey())
//                    .modelName(getModelName())
//                    .temperature(crawlingConfig.getSiteSpecific().getDetailExtractionTemperature())
//                    .maxTokens(2000)
//                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
//                    .build();

            log.info("Anthropic Claude 모델 초기화 완료 - Model: {}", getModelName());

        } catch (Exception e) {
            log.error("Anthropic Claude 추출 서비스 초기화 실패", e);
            throw new RuntimeException("Anthropic Claude 추출 서비스 초기화 실패", e);
        }
    }

    @Override
    public List<JobPosting> extractJobsFromHtml(String html, String siteName) {
        try {
            log.info("Anthropic Claude를 이용한 채용공고 추출 시작 - 사이트: {}", siteName);

            if (!isModelAvailable()) {
                log.warn("Anthropic Claude 모델을 사용할 수 없음");
                return new ArrayList<>();
            }

            // HTML 전처리
            String cleanedHtml = preprocessHtml(html);

            // AI 프롬프트 생성 및 실행 (재시도 로직 포함)
            String prompt = createJobListExtractionPrompt(cleanedHtml, siteName);
            String response = generateChatResponseWithRetry(prompt, chatModel);

            log.info("Anthropic Claude API 응답 받음 - 길이: {}", response.length());

            // AI 응답 파싱
            List<JobPosting> jobs = parseJobListResponse(response, siteName);

            log.info("Anthropic Claude 추출 완료 - {}개 채용공고 추출 (신뢰도: {:.2f})",
                    jobs.size(), getExtractionConfidence(html, siteName));
            return jobs;

        } catch (Exception e) {
            log.error("Anthropic Claude를 이용한 채용공고 추출 실패 - 사이트: {}", siteName, e);

            if (crawlingConfig.isEnableFallback()) {
                log.info("폴백 모드로 전환하여 기본 추출 시도");
                return fallbackExtraction(html, siteName);
            }

            return new ArrayList<>();
        }
    }

    @Override
    public JobPosting extractJobDetailFromHtml(JobPosting baseJob, String detailHtml) {
        try {
            log.info("Anthropic Claude를 이용한 채용공고 상세정보 추출 시작 - {}", baseJob.getTitle());

            if (!isModelAvailable()) {
                log.warn("Anthropic Claude 모델을 사용할 수 없음, 원본 반환");
                return baseJob;
            }

            // HTML 전처리
            String cleanedHtml = preprocessHtml(detailHtml);

            // AI 프롬프트 생성 및 실행
            String prompt = createJobDetailExtractionPrompt(cleanedHtml, baseJob);
            String response = generateChatResponseWithRetry(prompt, detailChatModel);

            // AI 응답 파싱하여 기존 JobPosting 객체 업데이트
            updateJobFromDetailResponse(baseJob, response);

            log.info("채용공고 상세정보 추출 완료 - {}", baseJob.getTitle());
            return baseJob;

        } catch (Exception e) {
            log.error("채용공고 상세정보 추출 실패 - {}", baseJob.getTitle(), e);
            return baseJob;
        }
    }

    @Override
    public String getModelType() {
        return "anthropic";
    }

    @Override
    public boolean isModelAvailable() {
        try {
            if (chatModel == null) {
                return false;
            }

            // 간단한 테스트 요청으로 모델 상태 확인
            String testResponse = chatModel.generate("Test");
            return testResponse != null && !testResponse.trim().isEmpty();

        } catch (Exception e) {
            log.warn("Anthropic Claude 모델 상태 확인 실패", e);
            return false;
        }
    }

    @Override
    public double getExtractionConfidence(String html, String siteName) {
        try {
            // Claude는 매우 높은 품질의 텍스트 이해 능력을 가짐
            double htmlQuality = calculateHtmlQuality(html);
            double siteWeight = getSiteConfidenceWeight(siteName);
            double modelWeight = 0.98; // Claude의 최고 수준 신뢰도

            double confidence = htmlQuality * siteWeight * modelWeight;
            return Math.max(0.0, Math.min(1.0, confidence));

        } catch (Exception e) {
            log.info("신뢰도 계산 실패", e);
            return 0.95; // Claude 기본 신뢰도
        }
    }

    @Override
    public ModelStatus getModelStatus() {
        return AiExtractionService.super.getModelStatus();
    }

    @Override
    public List<JobPosting> extractJobsFromText(String text, String siteName) {
        return null;
    }

    // ===== 내부 헬퍼 메서드들 =====

    private void validateAnthropicConfig() {
        if (aiModelConfig.getAnthropic() == null ||
                aiModelConfig.getAnthropic().getApiKey() == null ||
                aiModelConfig.getAnthropic().getApiKey().isBlank()) {
            throw new IllegalStateException("Anthropic API Key가 설정되지 않았습니다.");
        }
        log.info("Anthropic API Key 검증 완료");
    }

    private String getModelName() {
        String configuredModel = aiModelConfig.getAnthropic().getAiChatModel();
        return configuredModel != null ? configuredModel : "claude-3-sonnet-20240229";
    }

    private Integer getOutputMaxTokens() {
        String maxTokens = aiModelConfig.getAnthropic().getOutputMaxToken();
        return maxTokens != null ? Integer.parseInt(maxTokens) : 4000;
    }

    /**
     * 재시도 로직이 포함된 채팅 응답 생성
     */
    private String generateChatResponseWithRetry(String prompt, ChatLanguageModel model) {
        int attempt = 0;
        long currentDelay = DEFAULT_RETRY_DELAY_MS;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                attempt++;
                log.info("Anthropic Claude 모델 호출 시도 {}/{}", attempt, MAX_RETRY_ATTEMPTS);

                long startTime = System.currentTimeMillis();
                String response = model.generate(prompt);
                long endTime = System.currentTimeMillis();

                log.info("Anthropic Claude API 응답 성공 - 시도: {}, 응답시간: {}ms", attempt, (endTime - startTime));
                return response;

            } catch (Exception e) {
                log.warn("Anthropic Claude 모델 호출 실패 - 시도: {}/{}, 오류: {}",
                        attempt, MAX_RETRY_ATTEMPTS, e.getMessage());

                // Rate limit 오류인지 확인하고 재시도 처리
                if (isRateLimitError(e) && attempt < MAX_RETRY_ATTEMPTS) {
                    long retryDelay = extractRetryDelay(e.getMessage());
                    currentDelay = retryDelay > 0 ? Math.min(retryDelay, MAX_RETRY_DELAY_MS) : currentDelay;

                    log.info("Rate limit 오류 감지, {}초 후 재시도 (시도: {}/{})",
                            currentDelay / 1000, attempt, MAX_RETRY_ATTEMPTS);

                    try {
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Anthropic Claude 모델 호출이 중단되었습니다.", ie);
                    }

                    currentDelay = Math.min((long) (currentDelay * 1.2), MAX_RETRY_DELAY_MS);

                } else if (attempt >= MAX_RETRY_ATTEMPTS) {
                    log.error("최대 재시도 횟수 도달");
                    throw new RuntimeException("Anthropic Claude 모델 응답 생성에 실패했습니다. 최대 재시도 횟수를 초과했습니다.", e);
                } else {
                    log.error("비 Rate limit 오류 발생");
                    throw new RuntimeException("Anthropic Claude 모델 응답 생성에 실패했습니다.", e);
                }
            }
        }

        throw new RuntimeException("Anthropic Claude 모델 응답 생성에 실패했습니다. 모든 재시도가 실패했습니다.");
    }

    private boolean isRateLimitError(Exception e) {
        String errorMessage = e.getMessage();
        if (errorMessage == null) {
            return false;
        }

        String lowerMessage = errorMessage.toLowerCase();
        return lowerMessage.contains("429") ||
                lowerMessage.contains("rate_limit") ||
                lowerMessage.contains("quota") ||
                lowerMessage.contains("resource_exhausted") ||
                lowerMessage.contains("exceed");
    }

    private long extractRetryDelay(String errorMessage) {
        if (errorMessage == null) {
            return 0;
        }

        try {
            Matcher matcher = RETRY_DELAY_PATTERN.matcher(errorMessage);
            if (matcher.find()) {
                int seconds = Integer.parseInt(matcher.group(1));
                return seconds * 1000L;
            }
        } catch (NumberFormatException e) {
            log.warn("retryDelay 파싱 실패: {}", errorMessage);
        }

        return 0;
    }

    private String preprocessHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            // 스크립트, 스타일, 주석 제거
            String cleaned = html;
            cleaned = SCRIPT_PATTERN.matcher(cleaned).replaceAll("");
            cleaned = STYLE_PATTERN.matcher(cleaned).replaceAll("");
            cleaned = COMMENT_PATTERN.matcher(cleaned).replaceAll("");

            // Jsoup을 사용한 추가 정리
            Document doc = Jsoup.parse(cleaned);
            doc.select("script, style, noscript, iframe, embed, object, meta, link").remove();
            doc.select("[style*='display:none'], [style*='visibility:hidden']").remove();
            doc.select(".ads, .advertisement, .banner, .popup").remove();

            // Claude 토큰 제한에 맞춰 길이 조정 (Claude는 매우 큰 컨텍스트 윈도우)
            String result = doc.html();
            int maxLength = getInputMaxTokens() * 4; // 대략적인 토큰-문자 비율
            if (result.length() > maxLength) {
                result = result.substring(0, maxLength) + "...";
                log.info("HTML 길이 제한 적용: {} -> {}", html.length(), result.length());
            }

            return result;

        } catch (Exception e) {
            log.warn("HTML 전처리 중 오류, 원본 반환", e);
            return html;
        }
    }

    private int getInputMaxTokens() {
        String maxTokens = aiModelConfig.getAnthropic().getInputMaxToken();
        return maxTokens != null ? Integer.parseInt(maxTokens) : 100000; // Claude의 큰 컨텍스트 윈도우
    }

    private String createJobListExtractionPrompt(String html, String siteName) {
        return String.format("""
            I'm a hiring data extraction specialist. Please analyze the following HTML content from %s job listing page and extract job posting information with high accuracy.
            
            Please extract the following information for each job posting:
            - title: Job title (required)
            - company: Company name (required)
            - location: Work location (city/district level preferred)
            - salary: Salary/compensation information  
            - employmentType: Employment type (정규직, 계약직, 인턴, 프리랜서 등)
            - experienceLevel: Experience requirement (신입, 경력, 경력무관, etc.)
            - sourceUrl: Complete URL to job detail page
            
            Critical requirements:
            1. Return ONLY a valid JSON array format
            2. No additional text, explanations, or markdown formatting
            3. sourceUrl must be complete URLs (http:// or https://)
            4. Use null for missing information
            5. Filter out advertisements and irrelevant content
            6. Both title and company are mandatory
            
            Expected JSON format:
            [
                {
                    "title": "백엔드 개발자",
                    "company": "테크회사",
                    "location": "서울 강남구",
                    "salary": "연봉 3000-5000만원",
                    "employmentType": "정규직",
                    "experienceLevel": "경력 3년 이상",
                    "sourceUrl": "https://example.com/job/123"
                }
            ]
            
            HTML content to analyze:
            %s
            """, siteName, html);
    }

    private String createJobDetailExtractionPrompt(String html, JobPosting baseJob) {
        return String.format("""
            I'm analyzing a detailed job posting page for "%s" position at "%s" company. 
            Please extract comprehensive job details from the HTML content below with high precision.
            
            Extract these specific details:
            - description: Detailed job responsibilities and role description
            - requirements: Required qualifications, skills, experience, and preferred qualifications
            - benefits: Employee benefits, perks, working conditions, company culture
            - salary: Salary details (only if more specific than existing data)
            - location: Detailed work location (only if more specific than existing data)  
            - deadline: Application deadline in YYYY-MM-DD format
            
            Critical requirements:
            1. Return ONLY a valid JSON object format
            2. No additional text, explanations, or markdown formatting
            3. Use null for missing information
            4. deadline must be exact YYYY-MM-DD format
            5. Provide content in Korean for Korean job postings
            
            Expected JSON format:
            {
                "description": "Spring Boot 기반 백엔드 API 개발 및 데이터베이스 설계 업무",
                "requirements": "Java, Spring Boot 3년 이상 경험 필수, AWS 경험 우대",
                "benefits": "4대보험, 연차 15일, 교육비 지원, 유연근무제",
                "salary": "연봉 4500만원 협의가능",
                "location": "서울특별시 강남구 테헤란로 123번길",
                "deadline": "2024-12-31"
            }
            
            HTML content to analyze:
            %s
            """, baseJob.getTitle(), baseJob.getCompany(), html);
    }

    private List<JobPosting> parseJobListResponse(String response, String siteName) {
        List<JobPosting> jobs = new ArrayList<>();
        PartialJsonParser partialJsonParser = new PartialJsonParser();
        try {
            String jsonStr = partialJsonParser.extractValidJson(response);
            JsonNode jsonArray = objectMapper.readTree(jsonStr);

            if (!jsonArray.isArray()) {
                log.warn("Anthropic Claude 응답이 배열이 아닙니다: {}", jsonStr);
                return jobs;
            }

            for (JsonNode jobNode : jsonArray) {
                try {
                    JobPosting job = createJobPostingFromNode(jobNode, siteName);
                    if (isValidJob(job)) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    log.warn("개별 채용공고 파싱 실패", e);
                }
            }

        } catch (Exception e) {
            log.error("Anthropic Claude 응답 파싱 실패", e);
        }

        return jobs;
    }

    private JobPosting createJobPostingFromNode(JsonNode jobNode, String siteName) {
        JobPosting job = new JobPosting();

        job.setTitle(getTextValue(jobNode, "title"));
        job.setCompany(getTextValue(jobNode, "company"));
        job.setLocation(getTextValue(jobNode, "location"));
        job.setSalary(getTextValue(jobNode, "salary"));
        job.setEmploymentType(getTextValue(jobNode, "employmentType"));
        job.setExperienceLevel(getTextValue(jobNode, "experienceLevel"));
        job.setSourceUrl(normalizeUrl(getTextValue(jobNode, "sourceUrl"), siteName));

        job.setSourceSite(siteName);
        job.setJobCategory("개발");
        job.setIsActive(true);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        return job;
    }

    private void updateJobFromDetailResponse(JobPosting job, String response) {
        PartialJsonParser partialJsonParser = new PartialJsonParser();
        try {
            String jsonStr = partialJsonParser.extractValidJson(response);
            JsonNode jsonNode = objectMapper.readTree(jsonStr);

            updateJobField(job::setDescription, job.getDescription(), getTextValue(jsonNode, "description"));
            updateJobField(job::setRequirements, job.getRequirements(), getTextValue(jsonNode, "requirements"));
            updateJobField(job::setBenefits, job.getBenefits(), getTextValue(jsonNode, "benefits"));

            updateIfMoreDetailed(job::setSalary, job.getSalary(), getTextValue(jsonNode, "salary"));
            updateIfMoreDetailed(job::setLocation, job.getLocation(), getTextValue(jsonNode, "location"));

            parseAndSetDeadline(job, getTextValue(jsonNode, "deadline"));

        } catch (Exception e) {
            log.error("Anthropic Claude 상세정보 응답 파싱 실패", e);
        }
    }

    // ===== 공통 헬퍼 메서드들 =====

    private String extractJsonFromResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return "[]";
        }

        // 백틱으로 감싸진 JSON 추출
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```", Pattern.MULTILINE);
        Matcher matcher = jsonPattern.matcher(response);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 직접 JSON 찾기
        Pattern directJsonPattern = Pattern.compile("([\\[\\{][\\s\\S]*[\\]\\}])");
        Matcher directMatcher = directJsonPattern.matcher(response.trim());

        if (directMatcher.find()) {
            return directMatcher.group(1);
        }

        return response.trim();
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) {
            return null;
        }
        String value = fieldNode.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeUrl(String url, String siteName) {
        if (url == null || url.isEmpty()) {
            return null;
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        Map<String, String> baseUrls = Map.of(
                "사람인", "https://www.saramin.co.kr",
                "잡코리아", "https://www.jobkorea.co.kr",
                "원티드", "https://www.wanted.co.kr",
                "프로그래머스", "https://career.programmers.co.kr",
                "점프", "https://www.jumpit.co.kr"
        );

        String baseUrl = baseUrls.get(siteName);
        if (baseUrl != null) {
            return url.startsWith("/") ? baseUrl + url : baseUrl + "/" + url;
        }

        return url;
    }

    private void updateJobField(java.util.function.Consumer<String> setter, String currentValue, String newValue) {
        if (newValue != null && !newValue.isEmpty() &&
                (currentValue == null || currentValue.isEmpty())) {
            setter.accept(newValue);
        }
    }

    private void updateIfMoreDetailed(java.util.function.Consumer<String> setter, String currentValue, String newValue) {
        if (newValue != null && !newValue.isEmpty() &&
                (currentValue == null || newValue.length() > currentValue.length())) {
            setter.accept(newValue);
        }
    }

    private void parseAndSetDeadline(JobPosting job, String deadlineStr) {
        if (deadlineStr != null && deadlineStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                job.setDeadline(LocalDateTime.parse(deadlineStr + "T23:59:59"));
            } catch (Exception e) {
                log.info("마감일 파싱 실패: {}", deadlineStr);
            }
        }
    }

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
                job.getCompany() != null && !job.getCompany().trim().isEmpty() &&
                job.getSourceUrl() != null && !job.getSourceUrl().trim().isEmpty();
    }

    private double calculateHtmlQuality(String html) {
        if (html == null || html.isEmpty()) {
            return 0.1;
        }

        double quality = 0.5;

        if (html.contains("</div>") && html.contains("class=")) {
            quality += 0.2;
        }

        if (html.contains("job") || html.contains("채용") || html.contains("recruit")) {
            quality += 0.2;
        }

        if (html.length() > 1000) {
            quality += 0.1;
        }

        return Math.min(1.0, quality);
    }

    private double getSiteConfidenceWeight(String siteName) {
        return switch (siteName) {
            case "사람인", "잡코리아" -> 0.9;
            case "원티드" -> 0.85;
            case "프로그래머스", "점프" -> 0.8;
            default -> 0.7;
        };
    }

    private List<JobPosting> fallbackExtraction(String html, String siteName) {
        log.info("Anthropic Claude 폴백 모드로 기본 추출 시도 - 사이트: {}", siteName);

        List<JobPosting> fallbackJobs = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);
            var links = doc.select("a[href]");

            for (var link : links) {
                String href = link.attr("href");
                String text = link.text();

                if (isLikelyJobLink(href, text)) {
                    JobPosting job = new JobPosting();
                    job.setTitle(text);
                    job.setCompany("추출 실패");
                    job.setSourceUrl(normalizeUrl(href, siteName));
                    job.setSourceSite(siteName);
                    job.setJobCategory("개발");
                    job.setIsActive(true);
                    job.setCreatedAt(LocalDateTime.now());
                    job.setUpdatedAt(LocalDateTime.now());

                    if (isValidJob(job)) {
                        fallbackJobs.add(job);
                    }
                }

                if (fallbackJobs.size() >= 5) break;
            }

        } catch (Exception e) {
            log.warn("Anthropic Claude 폴백 추출도 실패", e);
        }

        log.info("Anthropic Claude 폴백 추출 완료: {}개", fallbackJobs.size());
        return fallbackJobs;
    }

    private boolean isLikelyJobLink(String href, String text) {
        if (href == null || text == null || text.length() < 5) {
            return false;
        }

        String lowerHref = href.toLowerCase();
        String lowerText = text.toLowerCase();

        return (lowerHref.contains("job") || lowerHref.contains("recruit") || lowerHref.contains("position")) &&
                (lowerText.contains("개발") || lowerText.contains("engineer") || lowerText.contains("developer"));
    }
}