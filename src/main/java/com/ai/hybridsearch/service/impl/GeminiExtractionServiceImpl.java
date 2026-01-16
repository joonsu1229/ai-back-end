package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.AiCrawlingConfig;
import com.ai.hybridsearch.config.AiModelConfig;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.service.AiExtractionService;
import com.ai.hybridsearch.util.PartialJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.langchain4j.data.message.UserMessage;

@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "langchain.model-type", havingValue = "gemini")
public class GeminiExtractionServiceImpl implements AiExtractionService {

    private final AiModelConfig aiModelConfig;
    private final AiCrawlingConfig crawlingConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatLanguageModel chatModel;
    private ChatLanguageModel detailChatModel;

    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 30000;
    private static final long MAX_RETRY_DELAY_MS = 300000;
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");
    private static final int MAX_DETAIL_HTML_LENGTH = 80000;


    @PostConstruct
    public void init() {
        try {
            log.info("=== Gemini Extraction Service Initializing ===");
            validateGeminiConfig();

            chatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(aiModelConfig.getGemini().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDefaultTemperature())
                    .maxOutputTokens(getOutputMaxTokens())
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            detailChatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(aiModelConfig.getGemini().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDetailExtractionTemperature())
                    .maxOutputTokens(2000)
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            log.info("Gemini models initialized successfully. Model: {}", getModelName());

        } catch (Exception e) {
            log.error("Failed to initialize Gemini Extraction Service", e);
            throw new RuntimeException("Failed to initialize Gemini Extraction Service", e);
        }
    }

    @Override
    public List<JobPosting> extractJobsFromHtml(String html, String siteName) {
        log.info("Starting job extraction with Gemini. Site: {}, HTML size: {}", siteName, html.length());

        if (html == null || html.trim().isEmpty()) {
            log.warn("HTML content is empty for site: {}", siteName);
            return Collections.emptyList();
        }

        try {
            String prompt = createJobListExtractionPrompt(html, siteName);
            String response = generateChatResponseWithRetry(prompt, chatModel);
            List<JobPosting> jobs = parseJobListResponse(response, siteName);
            
            List<JobPosting> uniqueJobs = removeDuplicateJobs(jobs);

            log.info("Gemini extraction complete. Found {} jobs ({} unique).", jobs.size(), uniqueJobs.size());
            return uniqueJobs;

        } catch (Exception e) {
            log.error("Failed to extract job postings from HTML for site: {}", siteName, e);
            return Collections.emptyList();
        }
    }

    @Override
    public JobPosting extractJobDetailFromHtml(JobPosting baseJob, String detailHtml) {
        log.info("Starting job detail extraction with Gemini for: {}", baseJob.getTitle());
        try {
            // Simple truncation if detailHtml is too long
            String processedHtml = detailHtml;
            if (processedHtml.length() > MAX_DETAIL_HTML_LENGTH) {
                processedHtml = processedHtml.substring(0, MAX_DETAIL_HTML_LENGTH);
                log.info("Detail HTML length truncated from {} to {}", detailHtml.length(), processedHtml.length());
            }

            String prompt = createJobDetailExtractionPrompt(processedHtml, baseJob);
            String response = generateChatResponseWithRetry(prompt, detailChatModel);
            updateJobFromDetailResponse(baseJob, response);
            log.info("Successfully extracted details for: {}", baseJob.getTitle());
            return baseJob;
        } catch (Exception e) {
            log.error("Failed to extract job detail for: {}", baseJob.getTitle(), e);
            return baseJob;
        }
    }

    /**
     * 단일 이미지(byte[])를 기반으로 채용 상세 정보를 추출합니다.
     */
    @Override
    public String extractJobDetailFromImage(JobPosting baseJob, byte[] imageBytes) {
        log.info("Starting job detail image extraction with Gemini.Image size: {} bytes", (imageBytes != null ? imageBytes.length : 0));

        if (imageBytes == null || imageBytes.length == 0) {
            log.info("Image data is empty for job: {}", baseJob.getTitle());
            return null;
        }

        try {
            String prompt = createJobDetailImagePrompt(baseJob);
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            // 팩토리 메서드(.from) 오류를 피하기 위해 List를 사용한 명시적 생성
            List<Content> contents = new ArrayList<>();
            contents.add(new TextContent(prompt));
            contents.add(new ImageContent(base64Image, "image/png"));

            UserMessage userMessage = new UserMessage(contents);

            // 재시도 로직을 통해 멀티모달 응답 획득
            String response = generateChatResponseWithRetry(userMessage, detailChatModel);

            log.info("Gemini image extraction complete for: {}", baseJob.getTitle());
            return response;

        } catch (Exception e) {
            log.error("Failed to extract job detail from image for: {}", baseJob.getTitle(), e);
            return null;
        }
    }

    private String generateChatResponseWithRetry(UserMessage userMessage, ChatLanguageModel model) {
        int attempt = 0;
        long currentDelay = DEFAULT_RETRY_DELAY_MS;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                attempt++;
                log.info("Calling Gemini API (Multimodal). Attempt: {}/{}", attempt, MAX_RETRY_ATTEMPTS);
                long startTime = System.currentTimeMillis();

                // 모델을 통해 생성 (UserMessage 버전 호출)
                String response = model.generate(userMessage).content().text();

                long endTime = System.currentTimeMillis();
                log.info("Gemini API call successful. Attempt: {}. Response time: {}ms", attempt, (endTime - startTime));
                return response;
            } catch (Exception e) {
                log.warn("Gemini API call failed. Attempt: {}/{}. Error: {}", attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
                if (isRateLimitError(e) && attempt < MAX_RETRY_ATTEMPTS) {
                    long retryDelay = extractRetryDelay(e.getMessage());
                    currentDelay = (retryDelay > 0) ? Math.min(retryDelay, MAX_RETRY_DELAY_MS) : currentDelay;
                    log.info("Rate limit error detected. Retrying in {} seconds...", currentDelay / 1000);
                    try {
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Gemini API call interrupted during retry wait.", ie);
                    }
                    currentDelay = Math.min((long) (currentDelay * 1.5), MAX_RETRY_DELAY_MS);
                } else {
                    throw new RuntimeException("Failed to get response from Gemini model after " + attempt + " attempts.", e);
                }
            }
        }
        throw new RuntimeException("Failed to get response from Gemini model. All retry attempts failed.");
    }

    @Override
    public String findIframeSrc(String html) {
        if (html == null || html.trim().isEmpty()) {
            return "";
        }
        try {
            String prompt = createIframeSrcExtractionPrompt(html);
            // Use the general purpose chat model, it should be fast enough.
            String response = generateChatResponseWithRetry(prompt, chatModel);
            // The model should return only the src value. We trim it to remove any potential newlines or spaces.
            log.info("AI detected iframe src: '{}'", response.trim());
            return response.trim();
        } catch (Exception e) {
            log.error("Failed to find iframe src using AI.", e);
            return ""; // Return empty string on failure to allow fallback.
        }
    }

    private String createIframeSrcExtractionPrompt(String html) {
        // Simple truncation if html is too long for this specific task
        String processedHtml = html;
        if (processedHtml.length() > MAX_DETAIL_HTML_LENGTH) {
            processedHtml = processedHtml.substring(0, MAX_DETAIL_HTML_LENGTH);
        }
        return String.format("""
        당신은 웹 페이지 구조 분석 전문가입니다. 주어진 HTML에서 주요 콘텐츠를 담고 있는 iframe의 'src' 속성 값을 찾아주세요.
        주요 콘텐츠 iframe은 보통 채용 상세 내용을 담고 있습니다.
        '상세내용, 상세요강 등의 태그에 있는 iframe 태그를 찾아보세요.
        
        규칙:
        1. 오직 'src' 속성의 값만 응답하세요.
        2. 다른 설명, 마크다운, 텍스트를 포함하지 마세요.
        3. 관련된 iframe을 찾지 못하면 빈 문자열을 응답하세요.

        HTML 내용:
        %s
        """, processedHtml);
    }
    
    private List<JobPosting> removeDuplicateJobs(List<JobPosting> jobs) {
        Map<String, JobPosting> uniqueJobs = new LinkedHashMap<>();
        for (JobPosting job : jobs) {
            String key = (job.getSourceUrl() != null && !job.getSourceUrl().trim().isEmpty())
                    ? job.getSourceUrl().trim()
                    : (job.getTitle() + "_" + job.getCompany()).replaceAll("\s+", "");
            if (!uniqueJobs.containsKey(key)) {
                uniqueJobs.put(key, job);
            }
        }
        return new ArrayList<>(uniqueJobs.values());
    }

    private void validateGeminiConfig() {
        if (aiModelConfig.getGemini() == null || aiModelConfig.getGemini().getApiKey() == null || aiModelConfig.getGemini().getApiKey().isBlank()) {
            throw new IllegalStateException("Gemini API Key is not configured.");
        }
    }

    private String getModelName() {
        return Optional.ofNullable(aiModelConfig.getGemini().getAiChatModel()).orElse("gemini-1.5-flash");
    }

    private Integer getOutputMaxTokens() {
        return Optional.ofNullable(aiModelConfig.getGemini().getOutputMaxToken()).map(Integer::parseInt).orElse(8192);
    }

    private String generateChatResponseWithRetry(String prompt, ChatLanguageModel model) {
        int attempt = 0;
        long currentDelay = DEFAULT_RETRY_DELAY_MS;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                attempt++;
                log.info("Calling Gemini API. Attempt: {}/{}. Prompt size: {} chars", attempt, MAX_RETRY_ATTEMPTS, prompt.length());
                long startTime = System.currentTimeMillis();
                String response = model.generate(prompt);
                long endTime = System.currentTimeMillis();
                log.info("Gemini API call successful. Attempt: {}. Response time: {}ms", attempt, (endTime - startTime));
                return response;
            } catch (Exception e) {
                log.warn("Gemini API call failed. Attempt: {}/{}. Error: {}", attempt, MAX_RETRY_ATTEMPTS, e.getMessage());
                if (isRateLimitError(e) && attempt < MAX_RETRY_ATTEMPTS) {
                    long retryDelay = extractRetryDelay(e.getMessage());
                    currentDelay = (retryDelay > 0) ? Math.min(retryDelay, MAX_RETRY_DELAY_MS) : currentDelay;
                    log.info("Rate limit error detected. Retrying in {} seconds...", currentDelay / 1000);
                    try {
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Gemini API call interrupted during retry wait.", ie);
                    }
                    currentDelay = Math.min((long) (currentDelay * 1.5), MAX_RETRY_DELAY_MS);
                } else {
                    throw new RuntimeException("Failed to get response from Gemini model after " + attempt + " attempts.", e);
                }
            }
        }
        throw new RuntimeException("Failed to get response from Gemini model. All retry attempts failed.");
    }


    private boolean isRateLimitError(Exception e) {
        String errorMessage = (e.getMessage() != null) ? e.getMessage().toLowerCase() : "";
        return errorMessage.contains("429") || errorMessage.contains("rate_limit") || errorMessage.contains("quota") || errorMessage.contains("resource_exhausted");
    }

    private long extractRetryDelay(String errorMessage) {
        if (errorMessage == null) return 0;
        Matcher matcher = RETRY_DELAY_PATTERN.matcher(errorMessage);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1)) * 1000L;
            } catch (NumberFormatException e) {
                log.warn("Failed to parse retryDelay from error message: {}", errorMessage);
            }
        }
        return 0;
    }

    private String createJobListExtractionPrompt(String html, String siteName) {
        return String.format("""
        당신은 채용 공고 정보 추출을 전문으로 하는 웹 스크래퍼입니다.
        '%s' 웹사이트의 다음 HTML 콘텐츠에서 채용 공고 정보를 정확하게 추출하세요.
        HTML 구조는 각 채용 사이트(사람인, 잡코리아, 원티드 등)마다 다릅니다. 제공된 HTML을 주의 깊게 분석하세요.

        각 채용 공고에서 추출할 정보:
        - title: 채용 직무명 (필수).
        - company: 회사명 (필수).
        - location: 근무지 (시/군/구 단위까지, 예: "서울시 도봉구", "경기도 일산").
        - salary: 급여 또는 보상 정보.
        - employmentType: 고용 형태 (예: "정규직", "계약직", "인턴").
        - experienceLevel: 요구 경력 수준 (예: "신입", "경력", "무관").
        - sourceUrl: 상세 채용 공고로 연결되는 URL 링크. 이 부분이 가장 중요합니다. 채용 직무명에 대한 'a' 태그를 찾아 'href' 속성을 추출하세요. 값은 "/zf_user/jobs/..."와 같은 상대 경로이거나 전체 URL일 수 있습니다. 'href' 속성에 나타난 그대로 추출하세요.

        주요 규칙:
        1.  반드시 유효한 JSON 객체 배열 형식으로만 응답하세요. 다른 텍스트나 설명을 포함하지 마세요.
        2.  `sourceUrl`이 중요합니다. 채용 직무명과 관련된 앵커 태그(`<a>`)를 찾아 `href` 속성 값을 추출하세요. 전체 URL을 만들려고 시도하지 마세요. 시스템이 처리할 것입니다.
        3.  정보를 사용할 수 없는 경우 `null`을 사용하세요.
        4.  광고, 배너 및 관련 없는 콘텐츠는 제외하세요.
        5.  `title`과 `company`는 필수 필드입니다.
        6.  `location`의 경우, "서울 강남구", "서울특별시 강남구"는 "서울시 강남구"로, "경기도 수원시 팔달구"는 "경기도 수원시"로 표시하세요 (시/군/구 단위까지).
        7.  최종 JSON은 `[{...}, {...}]`과 같은 깔끔한 배열이어야 합니다.

        응답 예시:
        [
            {
                "title": "백엔드 개발자",
                "company": "㈜한샘",
                "location": "서울시 마포구",
                "salary": null,
                "employmentType": "정규직",
                "experienceLevel": "경력3년↑",
                "sourceUrl": "/zf_user/jobs/relay/view?view_type=search&rec_idx=52796819"
            },
            {
                "title": "프론트엔드 개발자",
                "company": "카카오",
                "location": "경기도 성남시",
                "salary": "회사내규에 따름",
                "employmentType": "정규직",
                "experienceLevel": "신입",
                "sourceUrl": "https://careers.kakao.com/jobs/456"
            }
        ]

        HTML 내용:
        %s
        """, siteName, html);
    }

    private String createJobDetailExtractionPrompt(String html, JobPosting baseJob) {
        return String.format("""
        당신은 채용 공고 분석 전문가입니다.
        아래 HTML은 "%s" 직무, "%s" 회사의 채용 상세 페이지입니다.
        이 페이지에서 상세 정보를 정확하게 추출하세요.
        
        추출해야 할 정보:
        - description: 주요 업무 및 역할 설명
        - requirements: 자격 요건, 필요 기술, 우대 사항
        - benefits: 복리후생, 근무 조건
        - salary: 기존 정보보다 더 구체적인 급여 정보가 있다면 추출
        - location: 기존 정보보다 더 구체적인 근무지가 있다면 추출 (단, 시/도, 구까지만 표시. 예: "서울시 도봉구", "경기도 일산시")
        - deadline: 지원 마감일 (YYYY-MM-DD 형식)
        
        중요한 규칙:
        1. 반드시 올바른 JSON 객체 형식으로만 응답할 것
        2. 다른 텍스트나 설명을 포함하지 말 것
        3. 없는 정보는 설명 없음으로 표시
        4. deadline은 반드시 YYYY-MM-DD 형식으로 출력
        5. 모든 텍스트는 한국어로 정리할 것
        6. location은 "서울시 강남구" → "서울시 강남구", "경기도 수원시" → "경기도 고양시"처럼 시/도 구와 1번째 세부 지역까지만 나타나게 해줘  
        7. 상세 내용이 없는 경우 "상세내용은 채용공고를 들어가서 확인해주세요."라고 해줘
        
        응답 예시:
        {
            "description": "Spring Boot 기반 백엔드 API 개발 및 데이터베이스 설계",
            "requirements": "Java 및 Spring Boot 3년 이상 경력 필수, AWS 경험 우대",
            "benefits": "4대 보험, 연차 15일, 사내 교육 지원, 중식 제공",
            "salary": "연 5,000만원 이상 (협의 가능)",
            "location": "서울",
            "deadline": "2025-12-31"
        }
        
        HTML 내용:
        %s
        """, baseJob.getTitle(), baseJob.getCompany(), html);
    }

    /**
     * 이미지 분석을 위한 전용 프롬프트 (JSON 형식을 강제합니다)
     */
    private String createJobDetailImagePrompt(JobPosting baseJob) {
        return String.format("""
            당신은 채용 공고 분석 전문가입니다.
            제공된 이미지들은 "%s" 직무, "%s" 회사의 채용 상세 페이지 이미지입니다.
            이 이미지들을 분석하여 상세 정보를 정확하게 추출하세요.
            
            추출 항목:
            - description: 주요 업무 및 역할 설명
            - requirements: 자격 요건, 필요 기술, 우대 사항
            - benefits: 복리후생, 근무 조건
            - salary: 구체적인 급여 정보 (없으면 "상세내용 확인 필요")
            - location: 근무지 (시/도, 구까지만 표시. 예: "서울시 강남구")
            - deadline: 지원 마감일 (YYYY-MM-DD 형식)
            
            규칙:
            1. 반드시 올바른 JSON 객체 형식으로만 응답할 것
            2. 다른 텍스트나 설명 없이 오직 JSON만 출력할 것
            3. 이미지에 정보가 없는 경우 "상세내용 확인 필요"라고 표시
            4. 모든 텍스트는 한국어로 정리할 것
            """, baseJob.getTitle(), baseJob.getCompany());
    }

    private List<JobPosting> parseJobListResponse(String response, String siteName) {
        if (response == null || response.isBlank()) {
            log.warn("Gemini response is empty for site: {}", siteName);
            return Collections.emptyList();
        }
        
        List<JobPosting> jobs = new ArrayList<>();
        try {
            String jsonStr = new PartialJsonParser().extractValidJson(response);
            JsonNode jsonArray = objectMapper.readTree(jsonStr);

            if (!jsonArray.isArray()) {
                log.warn("Gemini response is not a JSON array: {}", jsonStr.substring(0, Math.min(jsonStr.length(), 100)));
                return jobs;
            }

            for (JsonNode jobNode : jsonArray) {
                try {
                    JobPosting job = createJobPostingFromNode(jobNode, siteName);
                    if (isValidJob(job)) {
                        jobs.add(job);
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse individual job node: {}", jobNode.toString(), e);
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse Gemini's JSON response.", e);
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
        try {
            String jsonStr = new PartialJsonParser().extractValidJson(response);
            JsonNode jsonNode = objectMapper.readTree(jsonStr);
            updateJobField(job::setDescription, job.getDescription(), getTextValue(jsonNode, "description"));
            updateJobField(job::setRequirements, job.getRequirements(), getTextValue(jsonNode, "requirements"));
            updateJobField(job::setBenefits, job.getBenefits(), getTextValue(jsonNode, "benefits"));
            updateIfMoreDetailed(job::setSalary, job.getSalary(), getTextValue(jsonNode, "salary"));
            updateIfMoreDetailed(job::setLocation, job.getLocation(), getTextValue(jsonNode, "location"));
            parseAndSetDeadline(job, getTextValue(jsonNode, "deadline"));
        } catch (Exception e) {
            log.error("Failed to parse Gemini detail response", e);
        }
    }

    private String getTextValue(JsonNode node, String fieldName) {
        JsonNode fieldNode = node.get(fieldName);
        if (fieldNode == null || fieldNode.isNull()) return null;
        String value = fieldNode.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private String normalizeUrl(String url, String siteName) {
        if (url == null || url.isEmpty()) return null;
        if (url.startsWith("http")) return url;
        
        String baseUrl = switch (siteName) {
            case "사람인" -> "https://www.saramin.co.kr";
            case "잡코리아" -> "https://www.jobkorea.co.kr";
            case "원티드" -> "https://www.wanted.co.kr";
            case "프로그래머스" -> "https://career.programmers.co.kr";
            case "점프" -> "https://www.jumpit.co.kr";
            default -> "";
        };

        if (baseUrl.isEmpty()) return url;
        return url.startsWith("/") ? baseUrl + url : baseUrl + "/" + url;
    }

    private void updateJobField(java.util.function.Consumer<String> setter, String currentValue, String newValue) {
        if (newValue != null && !newValue.isEmpty() && (currentValue == null || currentValue.isEmpty())) {
            setter.accept(newValue);
        }
    }

    private void updateIfMoreDetailed(java.util.function.Consumer<String> setter, String currentValue, String newValue) {
        if (newValue != null && !newValue.isEmpty() && (currentValue == null || newValue.length() > currentValue.length())) {
            setter.accept(newValue);
        }
    }

    private void parseAndSetDeadline(JobPosting job, String deadlineStr) {
        if (deadlineStr != null && deadlineStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                job.setDeadline(LocalDateTime.parse(deadlineStr + "T23:59:59"));
            } catch (Exception e) {
                log.warn("Failed to parse deadline string: {}", deadlineStr, e);
            }
        }
    }

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
               job.getCompany() != null && !job.getCompany().trim().isEmpty() &&
               job.getSourceUrl() != null && !job.getSourceUrl().trim().isEmpty();
    }
    
    // Unused methods - can be removed
    @Override
    public boolean isModelAvailable() { return false; }
    @Override
    public double getExtractionConfidence(String html, String siteName) { return 0.0; }
    @Override
    public String getModelType() { return "gemini"; }
}
