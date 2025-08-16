package com.ai.hybridsearch.service.impl;

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
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiExtractionServiceImpl implements AiExtractionService {

    private final EmbeddingConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private ChatLanguageModel chatModel;

    // HTML 정리를 위한 패턴들
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("<script[^>]*>.*?</script>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_PATTERN = Pattern.compile("<style[^>]*>.*?</style>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COMMENT_PATTERN = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

    @PostConstruct
    public void init() {
        try {
            log.info("=== AiExtractionService 초기화 시작 ===");

            if (config.getGemini() == null || config.getGemini().getApiKey() == null) {
                throw new IllegalStateException("Gemini API 설정이 필요합니다.");
            }

            // Gemini 채팅 모델 초기화
            chatModel = GoogleAiGeminiChatModel.builder()
                    .apiKey(config.getGemini().getApiKey())
                    .modelName(config.getGemini().getAiChatModel() != null ?
                            config.getGemini().getAiChatModel() : "gemini-1.5-flash")
                    .temperature(0.1) // 일관성을 위해 낮은 온도 설정
                    .maxOutputTokens(4000)
                    .build();

            log.info("Gemini 채팅 모델 초기화 완료");

        } catch (Exception e) {
            log.error("AiExtractionService 초기화 실패", e);
            throw new RuntimeException("AI 추출 서비스 초기화 실패", e);
        }
    }

    /**
     * HTML에서 여러 채용공고 추출
     */
    public List<JobPosting> extractJobsFromHtml(String html, String siteName) {
        try {
            log.info("AI를 이용한 채용공고 추출 시작 - 사이트: {}", siteName);

            // HTML 전처리
            String cleanedHtml = preprocessHtml(html);

            // AI 프롬프트 생성 및 실행
            String prompt = createJobListExtractionPrompt(cleanedHtml, siteName);
            String response = chatModel.generate(prompt);

            log.debug("AI 응답 받음 - 길이: {}", response.length());

            // AI 응답 파싱
            List<JobPosting> jobs = parseJobListResponse(response, siteName);

            log.info("AI 추출 완료 - {}개 채용공고 추출", jobs.size());
            return jobs;

        } catch (Exception e) {
            log.error("AI를 이용한 채용공고 추출 실패 - 사이트: {}", siteName, e);
            return new ArrayList<>();
        }
    }

    /**
     * 단일 채용공고 상세정보 추출
     */
    public JobPosting extractJobDetailFromHtml(JobPosting baseJob, String detailHtml) {
        try {
            log.debug("AI를 이용한 채용공고 상세정보 추출 시작 - {}", baseJob.getTitle());

            // HTML 전처리
            String cleanedHtml = preprocessHtml(detailHtml);

            // AI 프롬프트 생성 및 실행
            String prompt = createJobDetailExtractionPrompt(cleanedHtml, baseJob);
            String response = chatModel.generate(prompt);

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
        return null;
    }

    @Override
    public boolean isModelAvailable() {
        return false;
    }

    @Override
    public double getExtractionConfidence(String html, String siteName) {
        return 0;
    }

    /**
     * HTML 전처리 - 불필요한 요소 제거 및 정리
     */
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

            // Jsoup을 사용한 추가 정리 (선택사항)
            Document doc = Jsoup.parse(cleaned);

            // 불필요한 요소들 제거
            doc.select("script, style, noscript, iframe, embed, object").remove();
            doc.select("[style*='display:none'], [style*='visibility:hidden']").remove();

            // 텍스트만 추출하되, 구조 정보는 유지
            return doc.html();

        } catch (Exception e) {
            log.warn("HTML 전처리 중 오류, 원본 반환", e);
            return html;
        }
    }

    /**
     * 채용공고 목록 추출을 위한 프롬프트 생성
     */
    private String createJobListExtractionPrompt(String html, String siteName) {
        return String.format("""
            다음 HTML에서 채용공고 정보들을 추출해주세요. 이는 %s 사이트의 채용공고 목록 페이지입니다.
            
            추출해야 할 정보:
            - title: 채용공고 제목
            - company: 회사명
            - location: 근무지역 (있는 경우)
            - salary: 연봉/급여 정보 (있는 경우)
            - employmentType: 고용형태 (정규직, 계약직, 인턴 등)
            - experienceLevel: 경력 요구사항 (신입, 경력, 경력무관 등)
            - sourceUrl: 채용공고 상세 페이지 링크 (상대 경로면 절대 경로로 변환)
            
            응답 형식: 반드시 유효한 JSON 배열로만 응답하세요.
            ```json
            [
                {
                    "title": "백엔드 개발자",
                    "company": "ABC 회사",
                    "location": "서울 강남구",
                    "salary": "3000-5000만원",
                    "employmentType": "정규직",
                    "experienceLevel": "신입",
                    "sourceUrl": "https://example.com/job/123"
                }
            ]
            ```
            
            주의사항:
            - JSON 형식만 응답하고 다른 텍스트는 포함하지 마세요
            - sourceUrl은 완전한 URL이어야 합니다
            - 정보가 없으면 null로 설정하세요
            - 광고나 무관한 내용은 제외하세요
            
            HTML:
            %s
            """, siteName, truncateHtml(html, 8000)); // HTML 길이 제한
    }

    /**
     * 채용공고 상세정보 추출을 위한 프롬프트 생성
     */
    private String createJobDetailExtractionPrompt(String html, JobPosting baseJob) {
        return String.format("""
            다음 HTML은 "%s" 회사의 "%s" 채용공고 상세 페이지입니다.
            이 페이지에서 상세한 채용 정보를 추출해주세요.
            
            추출해야 할 정보:
            - description: 직무 설명/업무 내용
            - requirements: 자격 요건/필수 조건
            - benefits: 복리후생/혜택
            - salary: 급여 정보 (기존 정보보다 상세한 경우)
            - location: 근무 위치 (기존 정보보다 상세한 경우)
            - deadline: 마감일 (있는 경우, YYYY-MM-DD 형식)
            
            응답 형식: 반드시 유효한 JSON 객체로만 응답하세요.
            ```json
            {
                "description": "백엔드 시스템 개발 및 운영...",
                "requirements": "Java, Spring Boot 경험 필수...",
                "benefits": "4대보험, 연차, 교육비 지원...",
                "salary": "연봉 3000-5000만원",
                "location": "서울특별시 강남구 테헤란로 123",
                "deadline": "2024-12-31"
            }
            ```
            
            주의사항:
            - JSON 형식만 응답하고 다른 텍스트는 포함하지 마세요
            - 정보가 없으면 null로 설정하세요
            - deadline은 정확한 날짜 형식(YYYY-MM-DD)으로만 제공하세요
            
            HTML:
            %s
            """, baseJob.getCompany(), baseJob.getTitle(), truncateHtml(html, 8000));
    }

    /**
     * AI 응답을 파싱하여 JobPosting 목록 생성
     */
    private List<JobPosting> parseJobListResponse(String response, String siteName) {
        List<JobPosting> jobs = new ArrayList<>();

        try {
            // JSON 추출 (백틱으로 감싸진 경우 처리)
            String jsonStr = extractJsonFromResponse(response);

            JsonNode jsonArray = objectMapper.readTree(jsonStr);

            if (!jsonArray.isArray()) {
                log.warn("AI 응답이 배열이 아닙니다: {}", jsonStr);
                return jobs;
            }

            for (JsonNode jobNode : jsonArray) {
                try {
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

                    // 유효성 검증
                    if (isValidJob(job)) {
                        jobs.add(job);
                    } else {
                        log.debug("유효하지 않은 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
                    }

                } catch (Exception e) {
                    log.warn("개별 채용공고 파싱 실패", e);
                }
            }

        } catch (Exception e) {
            log.error("AI 응답 파싱 실패", e);
        }

        return jobs;
    }

    /**
     * AI 응답을 파싱하여 기존 JobPosting 객체 업데이트
     */
    private void updateJobFromDetailResponse(JobPosting job, String response) {
        try {
            String jsonStr = extractJsonFromResponse(response);
            JsonNode jsonNode = objectMapper.readTree(jsonStr);

            // 각 필드 업데이트 (기존 값이 없거나 더 상세한 경우만)
            updateJobField(job::setDescription, job.getDescription(), getTextValue(jsonNode, "description"));
            updateJobField(job::setRequirements, job.getRequirements(), getTextValue(jsonNode, "requirements"));
            updateJobField(job::setBenefits, job.getBenefits(), getTextValue(jsonNode, "benefits"));

            // 급여와 위치는 더 상세한 경우만 업데이트
            String newSalary = getTextValue(jsonNode, "salary");
            if (newSalary != null && (job.getSalary() == null || newSalary.length() > job.getSalary().length())) {
                job.setSalary(newSalary);
            }

            String newLocation = getTextValue(jsonNode, "location");
            if (newLocation != null && (job.getLocation() == null || newLocation.length() > job.getLocation().length())) {
                job.setLocation(newLocation);
            }

            // 마감일 파싱
            String deadlineStr = getTextValue(jsonNode, "deadline");
            if (deadlineStr != null && deadlineStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                try {
                    job.setDeadline(LocalDateTime.parse(deadlineStr + "T23:59:59"));
                } catch (Exception e) {
                    log.debug("마감일 파싱 실패: {}", deadlineStr);
                }
            }

        } catch (Exception e) {
            log.error("상세정보 업데이트 실패", e);
        }
    }

    /**
     * 헬퍼 메서드들
     */

    private String truncateHtml(String html, int maxLength) {
        if (html == null || html.length() <= maxLength) {
            return html;
        }
        return html.substring(0, maxLength) + "...";
    }

    private String extractJsonFromResponse(String response) {
        // 백틱으로 감싸진 JSON 추출
        Pattern jsonPattern = Pattern.compile("```json\\s*([\\s\\S]*?)\\s*```", Pattern.MULTILINE);
        Matcher matcher = jsonPattern.matcher(response);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        // 백틱이 없으면 전체 응답을 JSON으로 간주
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

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
                job.getCompany() != null && !job.getCompany().trim().isEmpty();
    }
}