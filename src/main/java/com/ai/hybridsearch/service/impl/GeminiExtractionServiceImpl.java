package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.AiCrawlingConfig;
import com.ai.hybridsearch.config.EmbeddingConfig;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.service.AiExtractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini 기반 채용공고 추출 서비스
 * IAiExtractionService 인터페이스를 구현하여 확장 가능한 구조 제공
 */
@Service
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(value = "langchain.model-type", havingValue = "gemini", matchIfMissing = true)
public class GeminiExtractionServiceImpl implements AiExtractionService {

    private final EmbeddingConfig embeddingConfig;
    private final AiCrawlingConfig crawlingConfig;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatLanguageModel chatModel;
    private ChatLanguageModel detailChatModel; // 상세 추출용 별도 모델

    // HTML 정리를 위한 패턴들
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @PostConstruct
    public void init() {
        try {
            log.info("=== GeminiExtractionService 초기화 시작 ===");

            validateGeminiConfig();

            // 기본 채팅 모델 초기화 (목록 추출용)
            chatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(embeddingConfig.getGemini().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDefaultTemperature())
                    .maxOutputTokens(4000)
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            // 상세 추출용 모델 (더 정확하고 일관성 있게)
            detailChatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(embeddingConfig.getGemini().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDetailExtractionTemperature())
                    .maxOutputTokens(2000)
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            log.info("Gemini 모델 초기화 완료 - Model: {}", getModelName());

        } catch (Exception e) {
            log.error("GeminiExtractionService 초기화 실패", e);
            throw new RuntimeException("Gemini 추출 서비스 초기화 실패", e);
        }
    }

    @Override
    public List<JobPosting> extractJobsFromHtml(String html, String siteName) {
        try {
            log.info("Gemini를 이용한 채용공고 추출 시작 - 사이트: {}", siteName);

            if (!isModelAvailable()) {
                log.warn("Gemini 모델을 사용할 수 없음");
                return new ArrayList<>();
            }

            // HTML 전처리
            String cleanedHtml = preprocessHtml(html);

            // AI 프롬프트 생성 및 실행
            String prompt = createJobListExtractionPrompt(cleanedHtml, siteName);

            long startTime = System.currentTimeMillis();
            String response = chatModel.generate(prompt);
            long endTime = System.currentTimeMillis();

            log.debug("Gemini API 응답 시간: {}ms, 응답 길이: {}", (endTime - startTime), response.length());

            // AI 응답 파싱
            List<JobPosting> jobs = parseJobListResponse(response, siteName);

            log.info("Gemini 추출 완료 - {}개 채용공고 추출 (신뢰도: {:.2f})",
                    jobs.size(), getExtractionConfidence(html, siteName));
            return jobs;

        } catch (Exception e) {
            log.error("Gemini를 이용한 채용공고 추출 실패 - 사이트: {}", siteName, e);

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
            log.debug("Gemini를 이용한 채용공고 상세정보 추출 시작 - {}", baseJob.getTitle());

            if (!isModelAvailable()) {
                log.warn("Gemini 모델을 사용할 수 없음, 원본 반환");
                return baseJob;
            }

            // HTML 전처리
            String cleanedHtml = preprocessHtml(detailHtml);

            // AI 프롬프트 생성 및 실행 (상세 추출용 모델 사용)
            String prompt = createJobDetailExtractionPrompt(cleanedHtml, baseJob);

            long startTime = System.currentTimeMillis();
            String response = detailChatModel.generate(prompt);
            long endTime = System.currentTimeMillis();

            log.debug("Gemini 상세 추출 API 응답 시간: {}ms", (endTime - startTime));

            // AI 응답 파싱하여 기존 JobPosting 객체 업데이트
            updateJobFromDetailResponse(baseJob, response);

            log.debug("채용공고 상세정보 추출 완료 - {}", baseJob.getTitle());
            return baseJob;

        } catch (Exception e) {
            log.error("채용공고 상세정보 추출 실패 - {}", baseJob.getTitle(), e);
            return baseJob; // 실패시 원본 반환
        }
    }

    @Override
    public String getModelType() {
        return "gemini";
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
            log.warn("Gemini 모델 상태 확인 실패", e);
            return false;
        }
    }

    @Override
    public double getExtractionConfidence(String html, String siteName) {
        try {
            // HTML 품질 기반 신뢰도 계산
            double htmlQuality = calculateHtmlQuality(html);

            // 사이트별 신뢰도 가중치
            double siteWeight = getSiteConfidenceWeight(siteName);

            // 전체 신뢰도 = HTML 품질 * 사이트 가중치
            double confidence = htmlQuality * siteWeight;

            return Math.max(0.0, Math.min(1.0, confidence));

        } catch (Exception e) {
            log.debug("신뢰도 계산 실패", e);
            return 0.5; // 기본값
        }
    }

    // ===== 내부 헬퍼 메서드들 =====

    private void validateGeminiConfig() {
        if (embeddingConfig.getGemini() == null ||
                embeddingConfig.getGemini().getApiKey() == null ||
                embeddingConfig.getGemini().getApiKey().isBlank()) {
            throw new IllegalStateException("Gemini API Key가 설정되지 않았습니다.");
        }
        log.info("Gemini API Key 검증 완료");
    }

    private String getModelName() {
        String configuredModel = embeddingConfig.getGemini().getAiChatModel();
        return configuredModel != null ? configuredModel : "gemini-1.5-flash";
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

            // 불필요한 요소들 제거
            doc.select("script, style, noscript, iframe, embed, object, meta, link").remove();
            doc.select("[style*='display:none'], [style*='visibility:hidden']").remove();
            doc.select(".ads, .advertisement, .banner, .popup").remove();

            // 길이 제한 적용
            String result = doc.html();
            if (result.length() > crawlingConfig.getMaxHtmlLengthForAi()) {
                result = result.substring(0, crawlingConfig.getMaxHtmlLengthForAi()) + "...";
                log.debug("HTML 길이 제한 적용: {} -> {}", html.length(), result.length());
            }

            return result;

        } catch (Exception e) {
            log.warn("HTML 전처리 중 오류, 원본 반환", e);
            return html.length() > crawlingConfig.getMaxHtmlLengthForAi() ?
                    html.substring(0, crawlingConfig.getMaxHtmlLengthForAi()) : html;
        }
    }

    private String createJobListExtractionPrompt(String html, String siteName) {
        return String.format("""
            당신은 웹 스크래핑 전문가입니다. 다음 HTML에서 채용공고 정보들을 정확하게 추출해주세요.
            이는 %s 사이트의 채용공고 목록 페이지입니다.
            
            추출해야 할 정보:
            - title: 채용공고 제목 (필수)
            - company: 회사명 (필수)
            - location: 근무지역
            - salary: 연봉/급여 정보
            - employmentType: 고용형태 (정규직, 계약직, 인턴, 프리랜서 등)
            - experienceLevel: 경력 요구사항 (신입, 경력, 경력무관, 1년 이상 등)
            - sourceUrl: 채용공고 상세 페이지 링크 (완전한 URL로)
            
            중요한 규칙:
            1. 반드시 유효한 JSON 배열 형식으로만 응답하세요
            2. 다른 텍스트나 설명은 포함하지 마세요
            3. sourceUrl은 반드시 완전한 URL이어야 합니다 (http:// 또는 https://로 시작)
            4. 정보가 없으면 null을 사용하세요
            5. 광고, 배너, 무관한 내용은 제외하세요
            6. 최소 title과 company는 반드시 있어야 합니다
            
            응답 예시:
            [
                {
                    "title": "백엔드 개발자 채용",
                    "company": "ABC 테크",
                    "location": "서울 강남구",
                    "salary": "연봉 3000~5000만원",
                    "employmentType": "정규직",
                    "experienceLevel": "경력 3년 이상",
                    "sourceUrl": "https://example.com/job/123"
                }
            ]
            
            HTML 내용:
            %s
            """, siteName, html);
    }

    private String createJobDetailExtractionPrompt(String html, JobPosting baseJob) {
        return String.format("""
            당신은 채용공고 분석 전문가입니다. 다음 HTML은 "%s" 회사의 "%s" 채용공고 상세 페이지입니다.
            이 페이지에서 상세한 정보를 정확하게 추출해주세요.
            
            추출해야 할 정보:
            - description: 주요 업무 내용 및 직무 설명
            - requirements: 자격 요건, 필수 조건, 우대 사항
            - benefits: 복리후생, 혜택, 근무 조건
            - salary: 급여 정보 (기존보다 상세한 경우만)
            - location: 근무 위치 (기존보다 상세한 경우만)
            - deadline: 채용 마감일 (YYYY-MM-DD 형식)
            
            중요한 규칙:
            1. 반드시 유효한 JSON 객체 형식으로만 응답하세요
            2. 다른 텍스트나 설명은 포함하지 마세요
            3. 정보가 없으면 null을 사용하세요
            4. deadline은 정확한 날짜 형식(YYYY-MM-DD)으로만 제공하세요
            5. 모든 텍스트는 한국어로 정리해주세요
            
            응답 예시:
            {
                "description": "Spring Boot를 활용한 백엔드 API 개발 및 데이터베이스 설계 업무를 담당합니다.",
                "requirements": "Java, Spring Boot 3년 이상 경험 필수, AWS 클라우드 경험 우대",
                "benefits": "4대보험 완비, 연차 15일, 교육비 지원, 야근수당",
                "salary": "연봉 4500만원 협의",
                "location": "서울특별시 강남구 테헤란로 123번길",
                "deadline": "2024-12-31"
            }
            
            HTML 내용:
            %s
            """, baseJob.getCompany(), baseJob.getTitle(), html);
    }

    private List<JobPosting> parseJobListResponse(String response, String siteName) {
        List<JobPosting> jobs = new ArrayList<>();

        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonNode jsonArray = objectMapper.readTree(jsonStr);

            if (!jsonArray.isArray()) {
                log.warn("Gemini 응답이 배열이 아닙니다: {}", jsonStr);
                return jobs;
            }

            for (JsonNode jobNode : jsonArray) {
                try {
                    JobPosting job = createJobPostingFromNode(jobNode, siteName);
                    if (isValidJob(job)) {
                        jobs.add(job);
                    } else {
                        log.debug("유효하지 않은 채용공고 스킵: {} - {}",
                                getTextValue(jobNode, "company"), getTextValue(jobNode, "title"));
                    }
                } catch (Exception e) {
                    log.warn("개별 채용공고 파싱 실패", e);
                }
            }

        } catch (Exception e) {
            log.error("Gemini 응답 파싱 실패: {}", response, e);
        }

        return jobs;
    }

    private JobPosting createJobPostingFromNode(JsonNode jobNode, String siteName) {
        JobPosting job = new JobPosting();

        // 기본 정보 설정
        job.setTitle(getTextValue(jobNode, "title"));
        job.setCompany(getTextValue(jobNode, "company"));
        job.setLocation(getTextValue(jobNode, "location"));
        job.setSalary(getTextValue(jobNode, "salary"));
        job.setEmploymentType(getTextValue(jobNode, "employmentType"));
        job.setExperienceLevel(getTextValue(jobNode, "experienceLevel"));
        job.setSourceUrl(normalizeUrl(getTextValue(jobNode, "sourceUrl"), siteName));

        // 사이트 정보 및 기본값 설정
        job.setSourceSite(siteName);
        job.setJobCategory("개발");
        job.setIsActive(true);
        job.setCreatedAt(LocalDateTime.now());
        job.setUpdatedAt(LocalDateTime.now());

        return job;
    }

    private void updateJobFromDetailResponse(JobPosting job, String response) {
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonNode jsonNode = objectMapper.readTree(jsonStr);

            // 각 필드 업데이트 (기존 값이 없거나 더 상세한 경우만)
            updateJobField(job::setDescription, job.getDescription(), getTextValue(jsonNode, "description"));
            updateJobField(job::setRequirements, job.getRequirements(), getTextValue(jsonNode, "requirements"));
            updateJobField(job::setBenefits, job.getBenefits(), getTextValue(jsonNode, "benefits"));

            // 급여와 위치는 더 상세한 경우만 업데이트
            updateIfMoreDetailed(job::setSalary, job.getSalary(), getTextValue(jsonNode, "salary"));
            updateIfMoreDetailed(job::setLocation, job.getLocation(), getTextValue(jsonNode, "location"));

            // 마감일 파싱
            parseAndSetDeadline(job, getTextValue(jsonNode, "deadline"));

        } catch (Exception e) {
            log.error("Gemini 상세정보 응답 파싱 실패", e);
        }
    }

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

        // 중괄호나 대괄호로 시작하는 JSON 찾기
        Pattern directJsonPattern = Pattern.compile("([\\[\\{][\\s\\S]*[\\]\\}])");
        Matcher directMatcher = directJsonPattern.matcher(response.trim());

        if (directMatcher.find()) {
            return directMatcher.group(1);
        }

        // 아무것도 찾지 못한 경우 전체 응답 반환
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

        // 이미 완전한 URL인 경우
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }

        // 사이트별 베이스 URL 맵핑
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
                log.debug("마감일 파싱 실패: {}", deadlineStr);
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

        double quality = 0.5; // 기본값

        // HTML 구조 품질 평가
        if (html.contains("</div>") && html.contains("class=")) {
            quality += 0.2; // 구조화된 HTML
        }

        if (html.contains("job") || html.contains("채용") || html.contains("recruit")) {
            quality += 0.2; // 채용 관련 키워드 존재
        }

        if (html.length() > 1000) {
            quality += 0.1; // 충분한 내용량
        }

        return Math.min(1.0, quality);
    }

    private double getSiteConfidenceWeight(String siteName) {
        // 사이트별 신뢰도 가중치 (경험적 값)
        return switch (siteName) {
            case "사람인", "잡코리아" -> 0.9;
            case "원티드" -> 0.85;
            case "프로그래머스", "점프" -> 0.8;
            default -> 0.7;
        };
    }

    /**
     * 폴백 추출 메서드 (AI 실패시 사용)
     */
    private List<JobPosting> fallbackExtraction(String html, String siteName) {
        log.info("폴백 모드로 기본 추출 시도 - 사이트: {}", siteName);

        // 간단한 정규식이나 기본 파싱으로 최소한의 정보 추출
        List<JobPosting> fallbackJobs = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);

            // 간단한 휴리스틱으로 채용공고 링크 찾기
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

                if (fallbackJobs.size() >= 5) break; // 최대 5개까지만
            }

        } catch (Exception e) {
            log.warn("폴백 추출도 실패", e);
        }

        log.info("폴백 추출 완료: {}개", fallbackJobs.size());
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