package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.AiCrawlingConfig;
import com.ai.hybridsearch.config.AiModelConfig;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.service.AiExtractionService;
import com.ai.hybridsearch.util.PartialJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gemini 기반 채용공고 추출 서비스 - 토큰 제한 대응 최적화 버전
 */
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

    // 토큰 제한 관련 상수
    private static final int MAX_INPUT_TOKENS = 25000; // Gemini 1.5 Flash 기본 제한보다 여유있게
    private static final int CHARS_PER_TOKEN = 4; // 한국어 기준 대략적인 비율
    private static final int MAX_CHUNK_SIZE = MAX_INPUT_TOKENS * CHARS_PER_TOKEN / 2; // 안전 마진
    private static final int MIN_JOB_CONTENT_LENGTH = 50; // 최소 채용공고 내용 길이

    // 재시도 설정
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long DEFAULT_RETRY_DELAY_MS = 30000;
    private static final long MAX_RETRY_DELAY_MS = 300000;

    // HTML 정리를 위한 패턴들
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
    private static final Pattern RETRY_DELAY_PATTERN = Pattern.compile("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"");

    // 사이트별 채용공고 선택자
    private static final Map<String, String> JOB_SELECTORS = Map.of(
            "사람인", "div.item_recruit, .recruit_info, .job_tit, .job_sector",
            "잡코리아", ".recruit-info, .post-list-info, .list-post, .recruit-item",
            "원티드", ".Job_className, .job-card, .JobCard_className",
            "프로그래머스", ".job-card, .position-item",
            "점프", ".position-item, .job-item"
    );

    @PostConstruct
    public void init() {
        try {
            log.info("=== Gemini 추출 서비스 초기화 시작 (토큰 제한 최적화 버전) ===");

            validateGeminiConfig();

            // 기본 채팅 모델 초기화 (목록 추출용)
            chatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(aiModelConfig.getGemini().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDefaultTemperature())
                    .maxOutputTokens(100000)
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            // 상세 추출용 모델
            detailChatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(aiModelConfig.getGemini().getApiKey())
                    .modelName(getModelName())
                    .temperature(crawlingConfig.getSiteSpecific().getDetailExtractionTemperature())
                    .maxOutputTokens(2000)
                    .timeout(Duration.ofSeconds(crawlingConfig.getAiResponseTimeoutSeconds()))
                    .build();

            log.info("Gemini 모델 초기화 완료 - Model: {}, Max Input Tokens: {}",
                    getModelName(), MAX_INPUT_TOKENS);

        } catch (Exception e) {
            log.error("Gemini 추출 서비스 초기화 실패", e);
            throw new RuntimeException("Gemini 추출 서비스 초기화 실패", e);
        }
    }

    @Override
    public List<JobPosting> extractJobsFromHtml(String html, String siteName) {
        try {
            log.info("Gemini를 이용한 채용공고 추출 시작 - 사이트: {} (HTML 크기: {})", siteName, html.length());

            // HTML 전처리 및 토큰 제한 처리
            List<String> processedChunks = preprocessAndChunkHtml(html, siteName);

            if (processedChunks.isEmpty()) {
                log.warn("처리된 HTML 청크가 없습니다 - 사이트: {}", siteName);
                return new ArrayList<>();
            }

            List<JobPosting> allJobs = new ArrayList<>();

            // 각 청크별로 AI 추출 수행
            for (int i = 0; i < processedChunks.size(); i++) {
                String chunk = processedChunks.get(i);
                log.info("청크 {}/{} 처리 중 - 크기: {}", i + 1, processedChunks.size(), chunk.length());

                try {
                    String prompt = createJobListExtractionPrompt(chunk, siteName);
                    String response = generateChatResponseWithRetry(prompt, chatModel);

                    List<JobPosting> chunkJobs = parseJobListResponse(response, siteName);
                    allJobs.addAll(chunkJobs);

                    log.info("청크 {}/{} 완료 - {}개 채용공고 추출", i + 1, processedChunks.size(), chunkJobs.size());

                    // 청크 간 딜레이
                    if (i < processedChunks.size() - 1) {
                        Thread.sleep(1000);
                    }

                } catch (Exception e) {
                    log.error("청크 {}/{} 처리 실패", i + 1, processedChunks.size(), e);
                    // 다음 청크 계속 처리
                }
            }

            // 중복 제거
            List<JobPosting> uniqueJobs = removeDuplicateJobs(allJobs);

            log.info("Gemini 추출 완료 - {}개 채용공고 추출 (중복 제거 후: {}개, 신뢰도: {:.2f})",
                    allJobs.size(), uniqueJobs.size(), getExtractionConfidence(html, siteName));

            return uniqueJobs;

        } catch (Exception e) {
            log.error("Gemini를 이용한 채용공고 추출 실패 - 사이트: {}", siteName, e);

            if (crawlingConfig.isEnableFallback()) {
                log.info("폴백 모드로 전환하여 기본 추출 시도");
                return fallbackExtraction(html, siteName);
            }

            return new ArrayList<>();
        }
    }

    /**
     * HTML을 전처리하고 토큰 제한에 맞게 청크로 분할
     */
    private List<String> preprocessAndChunkHtml(String html, String siteName) {
        if (html == null || html.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 1단계: 기본 전처리 (스크립트, 스타일 등 제거)
            String cleaned = basicHtmlCleaning(html);

            // 2단계: 채용공고 관련 요소만 추출
            List<String> jobElements = extractJobRelatedElements(cleaned, siteName);

            // 3단계: 토큰 제한에 맞게 청크 분할
            List<String> chunks = createOptimalChunks(jobElements);

            log.info("HTML 전처리 완료 - 원본: {}자, 청크 수: {}", html.length(), chunks.size());

            return chunks;

        } catch (Exception e) {
            log.warn("HTML 전처리 중 오류, 기본 청크 분할로 대체", e);
            return fallbackChunking(html);
        }
    }

    /**
     * 기본 HTML 정리 (스크립트, 스타일 등 제거)
     */
    private String basicHtmlCleaning(String html) {
        String cleaned = html;

        // 패턴 기반 제거
        cleaned = SCRIPT_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = STYLE_PATTERN.matcher(cleaned).replaceAll("");
        cleaned = COMMENT_PATTERN.matcher(cleaned).replaceAll("");

        // Jsoup을 사용한 추가 정리
        Document doc = Jsoup.parse(cleaned);

        // 불필요한 태그 제거
        doc.select("script, style, noscript, iframe, embed, object, meta, link").remove();
        doc.select("header, footer, nav, sidebar, .advertisement, .ads, .banner").remove();
        doc.select("[style*='display:none'], [style*='visibility:hidden']").remove();

        // 광고 관련 클래스/ID 제거
        doc.select(".ads, .advertisement, .banner, .popup, #ads, #advertisement").remove();

        return doc.html();
    }

    /**
     * 채용공고 관련 요소만 추출
     */
    private List<String> extractJobRelatedElements(String html, String siteName) {
        List<String> jobElements = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(html);

            // 사이트별 선택자 사용
            String selector = JOB_SELECTORS.getOrDefault(siteName,
                    "div[class*='job'], div[class*='recruit'], div[class*='position'], " +
                            "li[class*='job'], li[class*='recruit'], li[class*='position'], " +
                            "a[href*='job'], a[href*='recruit'], a[href*='position']");

            Elements elements = doc.select(selector);

            // 선택자가 결과를 찾지 못한 경우 일반적인 패턴으로 시도
            if (elements.isEmpty()) {
                elements = doc.select("div:contains(개발), div:contains(채용), div:contains(모집), " +
                        "li:contains(개발), li:contains(채용), li:contains(모집)");
            }

            for (Element element : elements) {
                String elementHtml = element.outerHtml();
                if (elementHtml.length() >= MIN_JOB_CONTENT_LENGTH) {
                    jobElements.add(elementHtml);
                }
            }

            // 여전히 비어있다면 전체 body 내용을 작은 청크로 분할
            if (jobElements.isEmpty()) {
                Element body = doc.body();
                if (body != null) {
                    String bodyHtml = body.html();
                    jobElements.add(bodyHtml);
                }
            }

            log.info("채용공고 요소 추출 완료 - {}개 요소 (사이트: {})", jobElements.size(), siteName);

        } catch (Exception e) {
            log.warn("채용공고 요소 추출 실패, 원본 HTML 사용", e);
            jobElements.add(html);
        }

        return jobElements;
    }

    /**
     * 토큰 제한에 맞게 최적 청크 생성
     */
    private List<String> createOptimalChunks(List<String> jobElements) {
        List<String> chunks = new ArrayList<>();
        StringBuilder currentChunk = new StringBuilder();

        for (String element : jobElements) {
            // 현재 청크에 추가했을 때 크기 확인
            if (currentChunk.length() + element.length() > MAX_CHUNK_SIZE) {
                // 현재 청크가 비어있지 않다면 저장
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString());
                    currentChunk = new StringBuilder();
                }

                // 개별 요소가 너무 크다면 분할
                if (element.length() > MAX_CHUNK_SIZE) {
                    List<String> subChunks = splitLargeElement(element);
                    chunks.addAll(subChunks);
                } else {
                    currentChunk.append(element);
                }
            } else {
                currentChunk.append(element);
            }
        }

        // 마지막 청크 추가
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString());
        }

        return chunks;
    }

    /**
     * 큰 요소를 작은 청크로 분할
     */
    private List<String> splitLargeElement(String element) {
        List<String> subChunks = new ArrayList<>();

        try {
            Document doc = Jsoup.parse(element);
            Elements children = doc.body().children();

            StringBuilder currentSubChunk = new StringBuilder();

            for (Element child : children) {
                String childHtml = child.outerHtml();

                if (currentSubChunk.length() + childHtml.length() > MAX_CHUNK_SIZE) {
                    if (currentSubChunk.length() > 0) {
                        subChunks.add(currentSubChunk.toString());
                        currentSubChunk = new StringBuilder();
                    }

                    // 개별 자식 요소도 너무 크다면 텍스트만 추출
                    if (childHtml.length() > MAX_CHUNK_SIZE) {
                        String text = child.text();
                        if (text.length() <= MAX_CHUNK_SIZE) {
                            currentSubChunk.append("<div>").append(text).append("</div>");
                        }
                    } else {
                        currentSubChunk.append(childHtml);
                    }
                } else {
                    currentSubChunk.append(childHtml);
                }
            }

            if (currentSubChunk.length() > 0) {
                subChunks.add(currentSubChunk.toString());
            }

        } catch (Exception e) {
            log.warn("큰 요소 분할 실패, 단순 텍스트 분할 시도", e);
            subChunks.addAll(fallbackTextSplit(element));
        }

        return subChunks;
    }

    /**
     * 폴백 텍스트 분할
     */
    private List<String> fallbackTextSplit(String text) {
        List<String> chunks = new ArrayList<>();

        for (int i = 0; i < text.length(); i += MAX_CHUNK_SIZE) {
            int end = Math.min(i + MAX_CHUNK_SIZE, text.length());
            chunks.add(text.substring(i, end));
        }

        return chunks;
    }

    /**
     * 폴백 청킹 (기본 분할)
     */
    private List<String> fallbackChunking(String html) {
        List<String> chunks = new ArrayList<>();

        // 기본 정리만 수행
        String cleaned = basicHtmlCleaning(html);

        // 단순히 크기별로 분할
        for (int i = 0; i < cleaned.length(); i += MAX_CHUNK_SIZE) {
            int end = Math.min(i + MAX_CHUNK_SIZE, cleaned.length());
            chunks.add(cleaned.substring(i, end));
        }

        return chunks;
    }

    /**
     * 중복된 채용공고 제거
     */
    private List<JobPosting> removeDuplicateJobs(List<JobPosting> jobs) {
        Map<String, JobPosting> uniqueJobs = new LinkedHashMap<>();

        for (JobPosting job : jobs) {
            if (job.getSourceUrl() != null && !job.getSourceUrl().trim().isEmpty()) {
                String key = job.getSourceUrl().trim();
                if (!uniqueJobs.containsKey(key)) {
                    uniqueJobs.put(key, job);
                }
            } else {
                // URL이 없는 경우 제목+회사명으로 중복 체크
                String key = (job.getTitle() + "_" + job.getCompany()).replaceAll("\\s+", "");
                if (!uniqueJobs.containsKey(key)) {
                    uniqueJobs.put(key, job);
                }
            }
        }

        return new ArrayList<>(uniqueJobs.values());
    }

    @Override
    public JobPosting extractJobDetailFromHtml(JobPosting baseJob, String detailHtml) {
        try {
            log.info("Gemini를 이용한 채용공고 상세정보 추출 시작 - {}", baseJob.getTitle());

            // HTML 전처리 (상세 페이지용)
            String cleanedHtml = preprocessDetailHtml(detailHtml);

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

    /**
     * 상세 페이지 HTML 전처리 (더 간단하게)
     */
    private String preprocessDetailHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            String cleaned = basicHtmlCleaning(html);

            // 상세 페이지는 더 적극적으로 불필요한 부분 제거
            Document doc = Jsoup.parse(cleaned);

            // 메인 컨텐츠 영역만 추출 시도
            Element mainContent = doc.selectFirst("main, #main, .main, .content, .job-detail, .recruit-detail");
            if (mainContent != null) {
                cleaned = mainContent.html();
            } else {
                // body 내용만 사용
                Element body = doc.body();
                if (body != null) {
                    cleaned = body.html();
                }
            }

            // 토큰 제한에 맞게 길이 조정 (상세 페이지는 더 관대하게)
            int maxLength = MAX_CHUNK_SIZE;
            if (cleaned.length() > maxLength) {
                cleaned = cleaned.substring(0, maxLength) + "...";
                log.info("상세 HTML 길이 제한 적용: {} -> {}", html.length(), cleaned.length());
            }

            return cleaned;

        } catch (Exception e) {
            log.warn("상세 HTML 전처리 중 오류, 원본 반환", e);
            // 원본이 너무 크면 일부만 반환
            if (html.length() > MAX_CHUNK_SIZE) {
                return html.substring(0, MAX_CHUNK_SIZE) + "...";
            }
            return html;
        }
    }

    // 기존의 다른 메서드들은 그대로 유지...
    // (generateChatResponseWithRetry, createJobListExtractionPrompt, etc.)

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
            double htmlQuality = calculateHtmlQuality(html);
            double siteWeight = getSiteConfidenceWeight(siteName);
            double modelWeight = 0.88;

            double confidence = htmlQuality * siteWeight * modelWeight;
            return Math.max(0.0, Math.min(1.0, confidence));

        } catch (Exception e) {
            log.info("신뢰도 계산 실패", e);
            return 0.85;
        }
    }

    // === 나머지 기존 메서드들 유지 ===

    private void validateGeminiConfig() {
        if (aiModelConfig.getGemini() == null ||
                aiModelConfig.getGemini().getApiKey() == null ||
                aiModelConfig.getGemini().getApiKey().isBlank()) {
            throw new IllegalStateException("Gemini API Key가 설정되지 않았습니다.");
        }
        log.info("Gemini API Key 검증 완료");
    }

    private String getModelName() {
        String configuredModel = aiModelConfig.getGemini().getAiChatModel();
        return configuredModel != null ? configuredModel : "gemini-1.5-flash";
    }

    private Integer getOutputMaxTokens() {
        String maxTokens = aiModelConfig.getGemini().getOutputMaxToken();
        return maxTokens != null ? Integer.parseInt(maxTokens) : 4000;
    }

    private String generateChatResponseWithRetry(String prompt, ChatLanguageModel model) {
        int attempt = 0;
        long currentDelay = DEFAULT_RETRY_DELAY_MS;

        while (attempt < MAX_RETRY_ATTEMPTS) {
            try {
                attempt++;
                log.info("Gemini 모델 호출 시도 {}/{} (프롬프트 크기: {}자)", attempt, MAX_RETRY_ATTEMPTS, prompt.length());

                long startTime = System.currentTimeMillis();
                String response = model.generate(prompt);
                long endTime = System.currentTimeMillis();

                log.info("Gemini API 응답 성공 - 시도: {}, 응답시간: {}ms", attempt, (endTime - startTime));
                return response;

            } catch (Exception e) {
                log.warn("Gemini 모델 호출 실패 - 시도: {}/{}, 오류: {}",
                        attempt, MAX_RETRY_ATTEMPTS, e.getMessage());

                if (isRateLimitError(e) && attempt < MAX_RETRY_ATTEMPTS) {
                    long retryDelay = extractRetryDelay(e.getMessage());
                    currentDelay = retryDelay > 0 ? Math.min(retryDelay, MAX_RETRY_DELAY_MS) : currentDelay;

                    log.info("Rate limit 오류 감지, {}초 후 재시도 (시도: {}/{})",
                            currentDelay / 1000, attempt, MAX_RETRY_ATTEMPTS);

                    try {
                        Thread.sleep(currentDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Gemini 모델 호출이 중단되었습니다.", ie);
                    }

                    currentDelay = Math.min((long) (currentDelay * 1.5), MAX_RETRY_DELAY_MS);

                } else if (attempt >= MAX_RETRY_ATTEMPTS) {
                    log.error("최대 재시도 횟수 도달");
                    throw new RuntimeException("Gemini 모델 응답 생성에 실패했습니다. 최대 재시도 횟수를 초과했습니다.", e);
                } else {
                    log.error("비 Rate limit 오류 발생");
                    throw new RuntimeException("Gemini 모델 응답 생성에 실패했습니다.", e);
                }
            }
        }

        throw new RuntimeException("Gemini 모델 응답 생성에 실패했습니다. 모든 재시도가 실패했습니다.");
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
                lowerMessage.contains("exceed") ||
                lowerMessage.contains("token") ||
                lowerMessage.contains("context_length");
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

    private String createJobListExtractionPrompt(String html, String siteName) {
        return String.format("""
        당신은 채용 공고 정보를 추출하는 전문 웹 스크래퍼입니다.
        아래의 %s 채용 목록 페이지 HTML에서 채용 공고 정보를 정확하게 추출하세요.
        
        추출해야 할 정보:
        - title: 채용 직무명 (필수)
        - company: 회사명 (필수)  
        - location: 근무지 (시/도 단위까지만, 예: "서울", "경기도")
        - salary: 급여/보상 정보
        - employmentType: 고용 형태 (정규직, 계약직, 인턴, 프리랜서 등)
        - experienceLevel: 경력 요구사항 (신입, 경력, 무관 등)
        - sourceUrl: 채용 상세 페이지 링크 (반드시 전체 URL, http:// 또는 https://로 시작)
        
        중요한 규칙:
        1. 반드시 올바른 JSON 배열 형식으로만 응답할 것
        2. 다른 텍스트나 설명을 포함하지 말 것
        3. sourceUrl은 반드시 전체 URL이어야 함
        4. 없는 정보는 null로 표시
        5. 광고, 배너, 무관한 콘텐츠는 제외
        6. title과 company는 반드시 포함되어야 함
        7. location은 "서울시", "서울특별시", "서울 강남구"처럼 상세 주소가 있어도 반드시 "서울"까지만 표시  
           "경기도 수원시" → "경기도" 로 변환
        
        응답 예시:
        [
            {
                "title": "백엔드 개발자",
                "company": "네이버",
                "location": "서울",
                "salary": "연 4,000~6,000만원",
                "employmentType": "정규직",
                "experienceLevel": "경력 2년 이상",
                "sourceUrl": "https://example.com/job/123"
            },
            {
                "title": "데이터 분석가",
                "company": "카카오",
                "location": "경기도",
                "salary": null,
                "employmentType": "계약직",
                "experienceLevel": "신입",
                "sourceUrl": "https://example.com/job/456"
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
        - location: 기존 정보보다 더 구체적인 근무지가 있다면 추출 (단, 시/도까지만 표시. 예: "서울", "경기도")
        - deadline: 지원 마감일 (YYYY-MM-DD 형식)
        
        중요한 규칙:
        1. 반드시 올바른 JSON 객체 형식으로만 응답할 것
        2. 다른 텍스트나 설명을 포함하지 말 것
        3. 없는 정보는 설명 없음으로 표시
        4. deadline은 반드시 YYYY-MM-DD 형식으로 출력
        5. 모든 텍스트는 한국어로 정리할 것
        6. location은 "서울시 강남구" → "서울", "경기도 수원시" → "경기도" 로 변환
        7. 상세 내용이 없는 경우 전체 정보를 요약해서 상세내용(description)으로 작성해줘
        
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

    private List<JobPosting> parseJobListResponse(String response, String siteName) {
        List<JobPosting> jobs = new ArrayList<>();
        PartialJsonParser partialJsonParser = new PartialJsonParser();
        try {
            String jsonStr = partialJsonParser.extractValidJson(response);
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
                    }
                } catch (Exception e) {
                    log.warn("개별 채용공고 파싱 실패", e);
                }
            }

        } catch (Exception e) {
            log.error("Gemini 응답 파싱 실패", e);
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
            log.error("Gemini 상세정보 응답 파싱 실패", e);
        }
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
        log.info("Gemini 폴백 모드로 기본 추출 시도 - 사이트: {}", siteName);

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
            log.warn("Gemini 폴백 추출도 실패", e);
        }

        log.info("Gemini 폴백 추출 완료: {}개", fallbackJobs.size());
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

    /**
     * 토큰 사용량 추정 메서드
     */
    private int estimateTokenCount(String text) {
        if (text == null) return 0;
        return text.length() / CHARS_PER_TOKEN;
    }

    /**
     * 현재 설정된 최대 입력 토큰 수 반환
     */
    public int getMaxInputTokens() {
        return MAX_INPUT_TOKENS;
    }

    /**
     * 토큰 제한 확인 메서드
     */
    public boolean isWithinTokenLimit(String text) {
        return estimateTokenCount(text) <= MAX_INPUT_TOKENS;
    }

    @Override
    public List<JobPosting> extractJobsFromText(String text, String siteName) {
        try {
            log.info("전처리된 텍스트에서 Gemini 추출 시작 - 사이트: {}, 텍스트 크기: {}자", siteName, text.length());

            if (text == null || text.trim().isEmpty()) {
                log.warn("추출할 텍스트가 비어있습니다");
                return new ArrayList<>();
            }

            // 토큰 제한 확인
            if (!isWithinTokenLimit(text)) {
                log.warn("텍스트가 여전히 토큰 제한을 초과합니다 - 크기: {}자, 예상 토큰: {}",
                        text.length(), estimateTokenCount(text));
                return new ArrayList<>();
            }

            // 텍스트 기반 프롬프트 생성
            String prompt = createJobExtractionPromptForPreprocessedText(text, siteName);

            // AI 호출
            long startTime = System.currentTimeMillis();
            String response = generateChatResponseWithRetry(prompt, chatModel);
            long endTime = System.currentTimeMillis();

            // 응답 파싱
            List<JobPosting> jobs = parseJobListResponse(response, siteName);

            // 중복 제거
            List<JobPosting> uniqueJobs = removeDuplicateJobs(jobs);

            log.info("텍스트 기반 Gemini 추출 완료 - 응답시간: {}ms, 결과: {}개 (중복제거 후: {}개)",
                    (endTime - startTime), jobs.size(), uniqueJobs.size());

            return uniqueJobs;

        } catch (Exception e) {
            log.error("텍스트 기반 Gemini 추출 실패 - 사이트: {}", siteName, e);

            // 폴백: 간단한 패턴 매칭
            if (crawlingConfig.isEnableFallback()) {
                return extractJobsFromTextFallback(text, siteName);
            }

            return new ArrayList<>();
        }
    }

    /**
     * 전처리된 텍스트용 프롬프트 생성
     */
    private String createJobExtractionPromptForPreprocessedText(String text, String siteName) {
        return String.format("""
    당신은 채용 공고 정보를 추출하는 전문가입니다.
    아래는 %s 사이트에서 전처리하여 추출한 구조화된 텍스트입니다.
    
    이 텍스트에서 채용 공고 정보를 정확하게 추출해주세요.
    텍스트는 이미 "=== 채용공고 N ===" 형태로 구분되어 있을 수 있습니다.
    
    추출할 정보:
    - title: 채용 직무명 (필수)
    - company: 회사명 (필수)  
    - location: 근무지 (시/도 단위까지만 표시. 예: "서울시 강남구" → "서울", "경기도 성남시" → "경기도")
    - salary: 급여/보상 정보
    - employmentType: 고용 형태 (정규직, 계약직, 인턴, 프리랜서 등)
    - experienceLevel: 경력 요구사항 (신입, 경력, 무관 등)
    - sourceUrl: 채용 상세 페이지 링크 (반드시 전체 URL, http:// 또는 https://로 시작)
    
    중요한 규칙:
    1. 반드시 올바른 JSON 배열 형식으로만 응답할 것
    2. 다른 텍스트나 설명을 포함하지 말 것
    3. sourceUrl은 반드시 완전한 URL이어야 함
    4. 없는 정보는 null로 표시
    5. title과 company는 반드시 포함되어야 함
    6. 중복된 채용공고는 제외할 것
    7. 텍스트에 "제목:", "회사:", "링크:" 등의 라벨이 있다면 해당 정보를 추출
    
    응답 예시:
    [
        {
            "title": "백엔드 개발자",
            "company": "네이버",
            "location": "서울",
            "salary": "연 4,000~6,000만원",
            "employmentType": "정규직", 
            "experienceLevel": "경력 2년 이상",
            "sourceUrl": "https://recruit.navercorp.com/job/123"
        },
        {
            "title": "프론트엔드 개발자",
            "company": "카카오",
            "location": "경기도",
            "salary": null,
            "employmentType": "정규직",
            "experienceLevel": "신입",
            "sourceUrl": "https://careers.kakao.com/jobs/456"
        }
    ]
    
    전처리된 텍스트:
    %s
    """, siteName, text);
    }

    /**
     * 텍스트 기반 폴백 추출 (패턴 매칭)
     */
    private List<JobPosting> extractJobsFromTextFallback(String text, String siteName) {
        log.info("텍스트 기반 폴백 추출 시도 - 사이트: {}", siteName);

        List<JobPosting> jobs = new ArrayList<>();

        try {
            // 채용공고 구분자로 분할
            String[] sections = text.split("=== 채용공고 \\d+ ===");

            for (String section : sections) {
                if (section.trim().isEmpty() || section.length() < 50) continue;

                JobPosting job = extractJobFromTextSection(section, siteName);
                if (job != null && isValidJob(job)) {
                    jobs.add(job);
                }

                // 너무 많으면 제한
                if (jobs.size() >= 20) break;
            }

            // 구분자가 없다면 라인별 분석
            if (jobs.isEmpty()) {
                jobs = extractJobsFromTextLines(text, siteName);
            }

        } catch (Exception e) {
            log.warn("텍스트 기반 폴백 추출 실패", e);
        }

        log.info("텍스트 기반 폴백 추출 완료: {}개", jobs.size());
        return jobs;
    }

    /**
     * 텍스트 섹션에서 채용공고 추출
     */
    private JobPosting extractJobFromTextSection(String section, String siteName) {
        try {
            JobPosting job = new JobPosting();

            // 라인별 분석
            String[] lines = section.split("\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) continue;

                if (line.startsWith("제목:")) {
                    job.setTitle(line.substring(3).trim());
                } else if (line.startsWith("회사:")) {
                    job.setCompany(line.substring(3).trim());
                } else if (line.startsWith("위치:")) {
                    String location = line.substring(3).trim();
                    job.setLocation(normalizeLocation(location));
                } else if (line.startsWith("급여:")) {
                    job.setSalary(line.substring(3).trim());
                } else if (line.startsWith("링크:")) {
                    String url = line.substring(3).trim();
                    job.setSourceUrl(normalizeUrl(url, siteName));
                } else if (line.startsWith("설명:")) {
                    job.setDescription(line.substring(3).trim());
                }
            }

            // 제목이나 회사명이 없다면 텍스트에서 추출 시도
            if ((job.getTitle() == null || job.getTitle().isEmpty()) &&
                    (job.getCompany() == null || job.getCompany().isEmpty())) {

                extractTitleAndCompanyFromText(section, job);
            }

            // 기본값 설정
            job.setSourceSite(siteName);
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return job;

        } catch (Exception e) {
            log.warn("텍스트 섹션 분석 실패", e);
            return null;
        }
    }

    /**
     * 라인별 텍스트 분석으로 채용공고 추출
     */
    private List<JobPosting> extractJobsFromTextLines(String text, String siteName) {
        List<JobPosting> jobs = new ArrayList<>();

        String[] lines = text.split("\n");
        JobPosting currentJob = null;

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;

            // 새로운 채용공고 시작 감지
            if (isLikelyJobTitle(line)) {
                // 이전 채용공고 저장
                if (currentJob != null && isValidJob(currentJob)) {
                    jobs.add(currentJob);
                }

                // 새 채용공고 시작
                currentJob = new JobPosting();
                currentJob.setTitle(line);
                currentJob.setSourceSite(siteName);
                currentJob.setJobCategory("개발");
                currentJob.setIsActive(true);
                currentJob.setCreatedAt(LocalDateTime.now());
                currentJob.setUpdatedAt(LocalDateTime.now());

            } else if (currentJob != null) {
                // 기존 채용공고에 정보 추가
                if (isLikelyCompanyName(line) && currentJob.getCompany() == null) {
                    currentJob.setCompany(line);
                } else if (isLikelyLocation(line) && currentJob.getLocation() == null) {
                    currentJob.setLocation(normalizeLocation(line));
                } else if (isLikelySalary(line) && currentJob.getSalary() == null) {
                    currentJob.setSalary(line);
                } else if (isLikelyUrl(line) && currentJob.getSourceUrl() == null) {
                    currentJob.setSourceUrl(normalizeUrl(line, siteName));
                }
            }
        }

        // 마지막 채용공고 저장
        if (currentJob != null && isValidJob(currentJob)) {
            jobs.add(currentJob);
        }

        return jobs;
    }

    /**
     * 텍스트에서 제목과 회사명 추출
     */
    private void extractTitleAndCompanyFromText(String text, JobPosting job) {
        String[] lines = text.split("\n");

        for (String line : lines) {
            line = line.trim();
            if (line.length() < 5 || line.length() > 100) continue;

            if (job.getTitle() == null && isLikelyJobTitle(line)) {
                job.setTitle(line);
            } else if (job.getCompany() == null && isLikelyCompanyName(line)) {
                job.setCompany(line);
            }

            if (job.getTitle() != null && job.getCompany() != null) {
                break;
            }
        }
    }

    /**
     * 채용공고 제목일 가능성 판단
     */
    private boolean isLikelyJobTitle(String text) {
        if (text == null || text.length() < 5 || text.length() > 150) return false;

        String lower = text.toLowerCase();
        return lower.contains("개발자") || lower.contains("developer") ||
                lower.contains("engineer") || lower.contains("프로그래머") ||
                lower.contains("백엔드") || lower.contains("프론트엔드") ||
                lower.contains("풀스택") || lower.contains("데이터") ||
                lower.contains("AI") || lower.contains("머신러닝");
    }

    /**
     * 회사명일 가능성 판단
     */
    private boolean isLikelyCompanyName(String text) {
        if (text == null || text.length() < 2 || text.length() > 50) return false;

        return text.contains("주식회사") || text.contains("(주)") ||
                text.contains("Inc.") || text.contains("Corp.") ||
                text.contains("Co.") || text.contains("Ltd.") ||
                (!text.contains(" ") && text.matches("^[가-힣A-Za-z0-9]+$"));
    }

    /**
     * 위치 정보일 가능성 판단
     */
    private boolean isLikelyLocation(String text) {
        if (text == null || text.length() < 2 || text.length() > 30) return false;

        String[] locations = {"서울", "경기", "부산", "대구", "인천", "광주", "대전", "울산", "세종"};
        String lower = text.toLowerCase();

        return Arrays.stream(locations).anyMatch(loc -> lower.contains(loc.toLowerCase())) ||
                text.contains("시") || text.contains("도") || text.contains("구") || text.contains("동");
    }

    /**
     * 급여 정보일 가능성 판단
     */
    private boolean isLikelySalary(String text) {
        if (text == null || text.length() < 3) return false;

        return text.contains("만원") || text.contains("원") ||
                text.contains("연봉") || text.contains("월급") ||
                text.matches(".*\\d+.*") && (text.contains("천") || text.contains("억"));
    }

    /**
     * URL일 가능성 판단
     */
    private boolean isLikelyUrl(String text) {
        if (text == null) return false;

        return text.startsWith("http://") || text.startsWith("https://") ||
                text.contains(".co.kr") || text.contains(".com") ||
                text.contains("/job") || text.contains("/recruit");
    }

    /**
     * 위치 정보 정규화 (시/도 단위로)
     */
    private String normalizeLocation(String location) {
        if (location == null || location.isEmpty()) return null;

        String normalized = location.trim();

        // 서울 관련
        if (normalized.contains("서울")) {
            return "서울";
        }
        // 경기도 관련
        else if (normalized.contains("경기")) {
            return "경기도";
        }
        // 기타 광역시/도
        else if (normalized.contains("부산")) {
            return "부산";
        } else if (normalized.contains("대구")) {
            return "대구";
        } else if (normalized.contains("인천")) {
            return "인천";
        } else if (normalized.contains("광주")) {
            return "광주";
        } else if (normalized.contains("대전")) {
            return "대전";
        } else if (normalized.contains("울산")) {
            return "울산";
        } else if (normalized.contains("세종")) {
            return "세종";
        }
        // 기타 도 단위
        else if (normalized.contains("강원")) {
            return "강원도";
        } else if (normalized.contains("충북") || normalized.contains("충청북도")) {
            return "충청북도";
        } else if (normalized.contains("충남") || normalized.contains("충청남도")) {
            return "충청남도";
        } else if (normalized.contains("전북") || normalized.contains("전라북도")) {
            return "전라북도";
        } else if (normalized.contains("전남") || normalized.contains("전라남도")) {
            return "전라남도";
        } else if (normalized.contains("경북") || normalized.contains("경상북도")) {
            return "경상북도";
        } else if (normalized.contains("경남") || normalized.contains("경상남도")) {
            return "경상남도";
        } else if (normalized.contains("제주")) {
            return "제주";
        }

        // 정규화할 수 없으면 원본 반환
        return normalized;
    }

}