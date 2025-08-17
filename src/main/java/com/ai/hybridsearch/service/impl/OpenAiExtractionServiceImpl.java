package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.AiCrawlingConfig;
import com.ai.hybridsearch.config.AiModelConfig;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.service.AiExtractionService;
import com.ai.hybridsearch.util.PartialJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * OpenAI 기반 채용공고 추출 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "langchain.model-type", havingValue = "openai")
public class OpenAiExtractionServiceImpl implements AiExtractionService {

    private final AiModelConfig aiModelConfig;
    private final AiCrawlingConfig crawlingConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String currentModelType;

    private ChatLanguageModel chatModel;
    private ChatLanguageModel detailChatModel;

    // 재시도 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 30000;
    private static final long MAX_RETRY_DELAY_MS = 300000;

    // HTML 정리를 위한 패턴들
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");

    private PartialJsonParser partialJsonParser;

    @PostConstruct
    public void init() {
        try {
            log.info("=== OpenAI 추출 서비스 초기화 시작 ===");

            validateOpenAiConfig();

            // 기본 채팅 모델 초기화 (목록 추출용)
            chatModel = OpenAiChatModel.builder()
                    .apiKey(aiModelConfig.getOpenai().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDefaultTemperature())
                    .maxTokens(getOutputMaxTokens())
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            // 상세 추출용 모델
            detailChatModel = OpenAiChatModel.builder()
                    .apiKey(aiModelConfig.getOpenai().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDetailExtractionTemperature())
                    .maxTokens(2000)
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            log.info("OpenAI 모델 초기화 완료 - Model: {}", getModelName());

        } catch (Exception e) {
            log.error("OpenAI 추출 서비스 초기화 실패", e);
            throw new RuntimeException("OpenAI 추출 서비스 초기화 실패", e);
        }
    }

    @Override
    public List<JobPosting> extractJobsFromHtml(String html, String siteName) {
        try {
            log.info("OpenAI를 이용한 채용공고 추출 시작 - 사이트: {}", siteName);

            if (!isModelAvailable()) {
                log.warn("OpenAI 모델을 사용할 수 없음");
                return new ArrayList<>();
            }

            // HTML 전처리
            String cleanedHtml = preprocessHtml(html);

            // AI 프롬프트 생성 및 실행 (재시도 로직 포함)
            String prompt = createJobListExtractionPrompt(cleanedHtml, siteName);
            String response = generateChatResponseWithRetry(prompt, chatModel);

            log.info("OpenAI API 응답 받음 - 길이: {}", response.length());

            // AI 응답 파싱
            List<JobPosting> jobs = parseJobListResponse(response, siteName);

            log.info("OpenAI 추출 완료 - {}개 채용공고 추출 (신뢰도: {:.2f})",
                    jobs.size(), getExtractionConfidence(html, siteName));
            return jobs;

        } catch (Exception e) {
            log.error("OpenAI를 이용한 채용공고 추출 실패 - 사이트: {}", siteName, e);

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
            log.info("OpenAI를 이용한 채용공고 상세정보 추출 시작 - {}", baseJob.getTitle());

            if (!isModelAvailable()) {
                log.warn("OpenAI 모델을 사용할 수 없음, 원본 반환");
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
        return "openai";
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
            log.warn("OpenAI 모델 상태 확인 실패", e);
            return false;
        }
    }

    @Override
    public double getExtractionConfidence(String html, String siteName) {
        try {
            // OpenAI GPT-4는 일반적으로 높은 신뢰도를 가짐
            double htmlQuality = calculateHtmlQuality(html);
            double siteWeight = getSiteConfidenceWeight(siteName);
            double modelWeight = 0.95; // OpenAI의 높은 신뢰도

            double confidence = htmlQuality * siteWeight * modelWeight;
            return Math.max(0.0, Math.min(1.0, confidence));

        } catch (Exception e) {
            log.info("신뢰도 계산 실패", e);
            return 0.9; // OpenAI 기본 신뢰도
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

    private void validateOpenAiConfig() {
        if (aiModelConfig.getOpenai() == null ||
                aiModelConfig.getOpenai().getApiKey() == null ||
                aiModelConfig.getOpenai().getApiKey().isBlank()) {
            throw new IllegalStateException("OpenAI API Key가 설정되지 않았습니다.");
        }
        log.info("OpenAI API Key 검증 완료");
    }

    private String getModelName() {
        String configuredModel = aiModelConfig.getOpenai().getAiChatModel();
        return configuredModel != null ? configuredModel : "gpt-4";
    }

    private Integer getOutputMaxTokens() {
        String maxTokens = aiModelConfig.getOpenai().getOutputMaxToken();
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
                log.info("OpenAI 모델 호출 시도 {}/{}", attempt, MAX_RETRY_ATTEMPTS);

                long startTime = System.currentTimeMillis();
                String response = model.generate(prompt);
                long endTime = System.currentTimeMillis();

                log.info("OpenAI API 응답 성공 - 시도: {}, 응답시간: {}ms", attempt, (endTime - startTime));
                return response;

            } catch (Exception e) {
                log.warn("OpenAI 모델 호출 실패 - 시도: {}/{}, 오류: {}",
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
                        throw new RuntimeException("OpenAI 모델 호출이 중단되었습니다.", ie);
                    }

                    currentDelay = Math.min((long) (currentDelay * 1.5), MAX_RETRY_DELAY_MS);

                } else if (attempt >= MAX_RETRY_ATTEMPTS) {
                    log.error("최대 재시도 횟수 도달");
                    throw new RuntimeException("OpenAI 모델 응답 생성에 실패했습니다. 최대 재시도 횟수를 초과했습니다.", e);
                } else {
                    log.error("비 Rate limit 오류 발생");
                    throw new RuntimeException("OpenAI 모델 응답 생성에 실패했습니다.", e);
                }
            }
        }

        throw new RuntimeException("OpenAI 모델 응답 생성에 실패했습니다. 모든 재시도가 실패했습니다.");
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

            // OpenAI 토큰 제한에 맞춰 길이 조정
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
        String maxTokens = aiModelConfig.getOpenai().getInputMaxToken();
        return maxTokens != null ? Integer.parseInt(maxTokens) : 8000;
    }

    private String createJobListExtractionPrompt(String html, String siteName) {
        return String.format("""
            You are an expert web scraper specialized in extracting job posting information. 
            Extract job posting information accurately from the following HTML content from %s job listing page.
            
            Required information to extract:
            - title: Job title (required)
            - company: Company name (required)  
            - location: Work location
            - salary: Salary/compensation information
            - employmentType: Employment type (full-time, contract, intern, freelance, etc.)
            - experienceLevel: Experience requirement (entry-level, experienced, no experience required, etc.)
            - sourceUrl: Job posting detail page link (must be complete URL)
            
            Important rules:
            1. Respond ONLY in valid JSON array format
            2. Do not include any other text or explanations
            3. sourceUrl must be a complete URL (starting with http:// or https://)
            4. Use null for missing information
            5. Exclude advertisements, banners, or irrelevant content
            6. Both title and company are mandatory fields
            
            Response example:
            [
                {
                    "title": "Backend Developer",
                    "company": "ABC Tech",
                    "location": "Seoul Gangnam-gu",
                    "salary": "Annual 30-50 million KRW",
                    "employmentType": "Full-time",
                    "experienceLevel": "3+ years experience",
                    "sourceUrl": "https://example.com/job/123"
                }
            ]
            
            HTML content:
            %s
            """, siteName, html);
    }

    private String createJobDetailExtractionPrompt(String html, JobPosting baseJob) {
        return String.format("""
            You are a job posting analysis expert. The following HTML is the job detail page for "%s" position at "%s" company.
            Extract detailed information accurately from this page.
            
            Information to extract:
            - description: Main job responsibilities and role description
            - requirements: Qualifications, required skills, preferred qualifications
            - benefits: Employee benefits, perks, working conditions
            - salary: Salary information (only if more detailed than existing)
            - location: Work location (only if more detailed than existing)
            - deadline: Application deadline (YYYY-MM-DD format)
            
            Important rules:
            1. Respond ONLY in valid JSON object format
            2. Do not include any other text or explanations
            3. Use null for missing information
            4. deadline must be in exact date format (YYYY-MM-DD)
            5. Organize all text content in Korean
            
            Response example:
            {
                "description": "Develop backend APIs using Spring Boot and design databases.",
                "requirements": "3+ years Java, Spring Boot experience required, AWS cloud experience preferred",
                "benefits": "Full insurance coverage, 15 vacation days, education allowance, overtime pay",
                "salary": "Annual 45 million KRW negotiable",
                "location": "Seoul Gangnam-gu Teheran-ro 123-gil",
                "deadline": "2024-12-31"
            }
            
            HTML content:
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
                log.warn("OpenAI 응답이 배열이 아닙니다: {}", jsonStr);
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
            log.error("OpenAI 응답 파싱 실패", e);
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
            log.error("OpenAI 상세정보 응답 파싱 실패", e);
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
        log.info("OpenAI 폴백 모드로 기본 추출 시도 - 사이트: {}", siteName);

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
            log.warn("OpenAI 폴백 추출도 실패", e);
        }

        log.info("OpenAI 폴백 추출 완료: {}개", fallbackJobs.size());
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