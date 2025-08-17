package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.ai.hybridsearch.service.AiExtractionService;
import com.ai.hybridsearch.service.EmbeddingService;
import com.ai.hybridsearch.service.JobCrawlingService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class JobCrawlingServiceImpl implements JobCrawlingService {

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private AiExtractionService aiExtractionService;

    // 크롤링 가능한 사이트 목록
    private static final Map<String, String> SUPPORTED_SITES = Map.of(
            "saramin", "사람인",
            "jobkorea", "잡코리아",
            "wanted", "원티드"
    );

    // 사이트별 URL 패턴
    private static final Map<String, String> SITE_URL_PATTERNS = Map.of(
            "saramin", "https://www.saramin.co.kr/zf_user/search/recruit?searchType=search&searchword=개발자&recruitPage=%d",
            "jobkorea", "https://www.jobkorea.co.kr/Search/?stext=개발자&Page_No=%d",
            "wanted", "https://www.wanted.co.kr/search?query=개발자&tab=position&page=%d",
            "programmers", "https://career.programmers.co.kr/job?page=%d",
            "jumpit", "https://www.jumpit.co.kr/positions?page=%d"
    );

    // 대기/지연 설정
    private static final int IMPLICIT_WAIT_SECONDS = 3;
    private static final int EXPLICIT_WAIT_SECONDS = 10;
    private static final int PAGE_LOAD_TIMEOUT_SECONDS = 15;
    private static final long MIN_DELAY = 3000; // 3초로 증가 (토큰 제한 고려)
    private static final long MAX_DELAY = 8000; // 8초로 증가 (토큰 제한 고려)
    private static final int MAX_RETRIES = 3;
    private static final int MAX_PAGES_PER_SITE = 1; // 토큰 제한으로 페이지 수 제한
    private static final int MAX_CHUNK_SIZE = 4000;

    // API 제한 관련 설정 (토큰 제한 고려하여 더 보수적으로 설정)
    private static final long API_CALL_DELAY = 5000; // API 호출 간격 5초로 증가
    private static final int MAX_CONCURRENT_AI_CALLS = 1; // 동시 AI 호출 수 1개로 제한 (토큰 제한 고려)
    private static final int MAX_DAILY_API_CALLS = 30; // 일일 API 호출 제한 감소 (토큰 고려)

    // 상세 페이지 병렬 처리 개수 (토큰 제한 고려하여 더 감소)
    private static final int DETAIL_PARALLELISM = 1; // 병렬 처리 1개로 제한

    // 토큰 제한 관련 설정
    private static final int MAX_HTML_SIZE_FOR_AI = 100000; // AI 호출 시 HTML 크기 제한 (50KB)
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4; // 한국어 기준 토큰당 문자 수
    private static final int SAFE_TOKEN_LIMIT = 20000; // 안전한 토큰 제한

    // ThreadLocal로 각 스레드별 WebDriver 관리
    private final ThreadLocal<WebDriver> driverThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<WebDriverWait> waitThreadLocal = new ThreadLocal<>();

    // 상세 크롤링용 실행기
    private ExecutorService detailExecutor;

    // API 호출 제한용 세마포어와 카운터
    private Semaphore aiCallSemaphore;
    private final AtomicInteger dailyApiCallCount = new AtomicInteger(0);
    private volatile LocalDateTime lastResetDate = LocalDateTime.now().toLocalDate().atStartOfDay();

    @PostConstruct
    public void init() {
        try {
            detailExecutor = Executors.newFixedThreadPool(DETAIL_PARALLELISM);
            aiCallSemaphore = new Semaphore(MAX_CONCURRENT_AI_CALLS);
            log.debug("JobCrawlingService 초기화 완료 - AI 호출 제한 적용 (토큰 제한 최적화), 병렬 스레드 {}개 준비", DETAIL_PARALLELISM);
        } catch (Exception e) {
            log.error("초기화 실패", e);
        }
    }

    @PreDestroy
    public void cleanup() {
        closeDriver();
        if (detailExecutor != null) {
            try {
                detailExecutor.shutdown();
                if (!detailExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    detailExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                detailExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.debug("JobCrawlingService 정리 완료");
    }

    /**
     * 일일 API 호출 카운터 리셋 확인
     */
    private void checkAndResetDailyCounter() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime today = now.toLocalDate().atStartOfDay();

        if (today.isAfter(lastResetDate)) {
            dailyApiCallCount.set(0);
            lastResetDate = today;
            log.debug("일일 API 호출 카운터 리셋");
        }
    }

    /**
     * AI 호출 전 제한 확인
     */
    private boolean canMakeApiCall() {
        checkAndResetDailyCounter();
        return dailyApiCallCount.get() < MAX_DAILY_API_CALLS;
    }

    /**
     * HTML 크기가 토큰 제한에 적합한지 확인
     */
    private boolean isHtmlSuitableForAI(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }

        int estimatedTokens = html.length() / ESTIMATED_CHARS_PER_TOKEN;
        boolean suitable = html.length() <= MAX_HTML_SIZE_FOR_AI && estimatedTokens <= SAFE_TOKEN_LIMIT;

        log.debug("HTML 토큰 적합성 체크 - 크기: {}자, 예상 토큰: {}, 적합: {}",
                html.length(), estimatedTokens, suitable);

        return suitable;
    }

    /**
     * HTML 크기를 줄이는 전처리 (토큰 제한 대응)
     */
    private String preprocessHtmlForTokenLimit(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }

        try {
            // 기본 크기 확인
            if (html.length() <= MAX_HTML_SIZE_FOR_AI) {
                return html;
            }

            log.debug("HTML 크기 축소 필요 - 원본 크기: {}자", html.length());

            // 1단계: 불필요한 태그 제거 (스크립트, 스타일 등)
            String cleaned = html
                    .replaceAll("<script[^>]*>.*?</script>", "")
                    .replaceAll("<style[^>]*>.*?</style>", "")
                    .replaceAll("<!--.*?-->", "")
                    .replaceAll("<meta[^>]*>", "")
                    .replaceAll("<link[^>]*>", "");

            // 2단계: 여전히 크다면 앞부분만 사용
            if (cleaned.length() > MAX_HTML_SIZE_FOR_AI) {
                cleaned = cleaned.substring(0, MAX_HTML_SIZE_FOR_AI) + "...";
            }

            log.debug("HTML 크기 축소 완료 - 축소 후 크기: {}자", cleaned.length());
            return cleaned;

        } catch (Exception e) {
            log.debug("HTML 전처리 실패, 앞부분만 사용", e);
            return html.length() > MAX_HTML_SIZE_FOR_AI ?
                    html.substring(0, MAX_HTML_SIZE_FOR_AI) + "..." : html;
        }
    }

    /**
     * API 호출 제한이 있는 AI 추출 호출 (토큰 제한 고려)
     */
    private List<JobPosting> callAiExtractionWithLimits(String html, String siteName) {
        if (!canMakeApiCall()) {
            log.debug("일일 API 호출 제한 도달: {}/{}", dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
            return new ArrayList<>();
        }

        // HTML 크기 체크 및 전처리
        String processedHtml = preprocessHtmlForTokenLimit(html);
        if (!isHtmlSuitableForAI(processedHtml)) {
            log.debug("HTML이 토큰 제한에 적합하지 않음 - 사이트: {}, 크기: {}자", siteName, processedHtml.length());
            return new ArrayList<>();
        }

        try {
            aiCallSemaphore.acquire();

            try {
                Thread.sleep(API_CALL_DELAY); // API 호출 간격 대기

                long startTime = System.currentTimeMillis();
                List<JobPosting> result = aiExtractionService.extractJobsFromHtml(processedHtml, siteName);
                long endTime = System.currentTimeMillis();

                dailyApiCallCount.incrementAndGet();

                log.debug("AI 추출 성공 - 호출 횟수: {}/{}, 응답시간: {}ms, 결과: {}개",
                        dailyApiCallCount.get(), MAX_DAILY_API_CALLS, (endTime - startTime), result.size());

                return result;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ArrayList<>();
            } catch (RuntimeException e) {
                if (e.getMessage() != null &&
                        (e.getMessage().contains("429") ||
                                e.getMessage().contains("token") ||
                                e.getMessage().contains("context_length"))) {
                    log.error("API 할당량 또는 토큰 제한 초과 오류 발생. 잠시 대기 후 재시도합니다.");
                    try {
                        Thread.sleep(60000); // 60초 대기 (토큰 제한 고려)
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } finally {
            aiCallSemaphore.release();
        }
    }

    /**
     * API 호출 제한이 있는 상세 정보 추출 (토큰 제한 고려)
     */
    private JobPosting callAiDetailExtractionWithLimits(JobPosting job, String html) {
        if (!canMakeApiCall()) {
            log.debug("일일 API 호출 제한으로 상세 정보 추출 스킵: {}", job.getSourceUrl());
            return job;
        }

        // HTML 크기 체크 및 전처리
        String processedHtml = preprocessHtmlForTokenLimit(html);
/*        if (!isHtmlSuitableForAI(processedHtml)) {
            log.debug("상세 HTML이 토큰 제한에 적합하지 않음 - URL: {}, 크기: {}자",
                    job.getSourceUrl(), processedHtml.length());
            return job;
        }*/

        try {
            aiCallSemaphore.acquire();

            try {
                Thread.sleep(API_CALL_DELAY); // API 호출 간격 대기

                JobPosting result = aiExtractionService.extractJobDetailFromHtml(job, processedHtml);
                dailyApiCallCount.incrementAndGet();

                log.debug("AI 상세 추출 성공 - 호출 횟수: {}/{}", dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
                return result;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return job;
            } catch (RuntimeException e) {
                if (e.getMessage() != null &&
                        (e.getMessage().contains("429") ||
                                e.getMessage().contains("token") ||
                                e.getMessage().contains("context_length"))) {
                    log.error("API 할당량 또는 토큰 제한 초과 오류 발생. 상세 정보 추출을 건너뜁니다: {}", job.getSourceUrl());
                    return job; // 기본 정보만 반환
                }
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return job;
        } finally {
            aiCallSemaphore.release();
        }
    }

    /**
     * 전체 사이트 크롤링 (기존 메소드)
     */
    public CompletableFuture<String> startManualCrawling() {
        log.debug("전체 사이트 크롤링 시작 (토큰 제한 최적화 버전)");
        try {
            return crawlAllSites().thenApply(jobs ->
                    String.format("전체 크롤링 완료: %d개 채용공고 수집 (API 호출: %d/%d, 토큰 제한 최적화 적용)",
                            jobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS)
            );
        } catch (Exception e) {
            log.error("전체 크롤링 시작 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        }
    }

    /**
     * 특정 사이트들만 크롤링 (토큰 제한 최적화)
     */
    @Async
    public CompletableFuture<String> startCrawlingBySites(List<String> siteIds) {
        try {
            log.debug("AI 기반 사이트별 크롤링 시작: {} (일일 API 제한: {}, 토큰 최적화 적용)",
                    siteIds, MAX_DAILY_API_CALLS);

            List<JobPosting> allJobs = new ArrayList<>();
            Map<String, Integer> siteResults = new HashMap<>();

            for (String siteId : siteIds) {
                if (!SUPPORTED_SITES.containsKey(siteId)) {
                    log.debug("지원하지 않는 사이트: {}", siteId);
                    continue;
                }

                if (!canMakeApiCall()) {
                    log.debug("일일 API 호출 제한 도달로 {} 크롤링 중단", siteId);
                    break;
                }

                String siteName = SUPPORTED_SITES.get(siteId);
                log.debug("{}({}) AI 기반 크롤링 시작 (토큰 최적화 적용)", siteName, siteId);

                try {
                    List<JobPosting> siteJobs = crawlSpecificSiteWithAI(siteId);
                    allJobs.addAll(siteJobs);
                    siteResults.put(siteName, siteJobs.size());

                    log.debug("{}({}) AI 크롤링 완료: {}개 수집", siteName, siteId, siteJobs.size());

                    // 사이트 간 더 긴 간격 (토큰 제한 고려)
                    Thread.sleep(10000); // 10초로 증가

                } catch (Exception e) {
                    log.error("{}({}) AI 크롤링 실패", siteName, siteId, e);
                    siteResults.put(siteName, 0);
                }
            }

            String resultMessage = String.format(
                    "AI 기반 사이트별 크롤링 완료 (토큰 최적화) - 총 %d개 수집. API 호출: %d/%d. 사이트별 결과: %s",
                    allJobs.size(),
                    dailyApiCallCount.get(),
                    MAX_DAILY_API_CALLS,
                    siteResults
            );

            log.debug(resultMessage);
            return CompletableFuture.completedFuture(resultMessage);

        } catch (Exception e) {
            log.error("AI 기반 사이트별 크롤링 프로세스 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        } finally {
            closeDriver();
        }
    }

    private List<JobPosting> callAiExtractionWithImprovedMethod(String html, String siteName) {
        if (!canMakeApiCall()) {
            log.debug("일일 API 호출 제한 도달: {}/{}", dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
            return new ArrayList<>();
        }

        try {
            log.debug("개선된 텍스트 추출 방식 적용 - 사이트: {}, HTML 크기: {}자", siteName, html.length());

            // 1단계: 의미있는 텍스트만 추출
            String extractedText = extractMeaningfulJobText(html, siteName);

            if (extractedText.isEmpty() || extractedText.length() < 200) {
                log.debug("추출된 텍스트가 부족함 - 사이트: {}, 텍스트 길이: {}자", siteName, extractedText.length());
                return new ArrayList<>();
            }

            log.debug("텍스트 추출 완료 - 원본 {}자 → 추출 {}자 (압축률: {:.1f}%)",
                    html.length(), extractedText.length(),
                    (double)(html.length() - extractedText.length()) / html.length() * 100);

            // 2단계: 토큰 제한에 따른 처리 방식 결정
            List<JobPosting> jobs;

            if (isTextWithinTokenLimit(extractedText)) {
                // 한 번에 처리 가능
                jobs = processSingleTextRequest(extractedText, siteName);
            } else {
                // 텍스트 분할 후 여러 번 처리
                jobs = processMultipleTextRequests(extractedText, siteName);
            }

            log.debug("개선된 방식 AI 추출 완료 - 결과: {}개 채용공고 (API 사용: {}/{})",
                    jobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);

            return jobs;

        } catch (Exception e) {
            log.error("개선된 방식 AI 추출 실패 - 사이트: {}", siteName, e);
            handleApiError(e);
            return new ArrayList<>();
        }
    }

    /**
     * HTML에서 채용공고 관련 의미있는 텍스트만 추출
     */
    private String extractMeaningfulJobText(String html, String siteName) {
        try {
            Document doc = Jsoup.parse(html);

            // 1단계: 불필요한 요소 제거
            removeUnnecessaryElements(doc);

            // 2단계: 사이트별 채용공고 영역 식별
            List<JobTextInfo> jobTexts = extractJobTextInfos(doc, siteName);

            // 3단계: 구조화된 텍스트로 변환
            return buildStructuredJobText(jobTexts);

        } catch (Exception e) {
            log.debug("텍스트 추출 중 오류, 폴백 방식 사용 - 사이트: {}", siteName, e);
            return extractFallbackText(html);
        }
    }

    /**
     * 불필요한 HTML 요소 제거
     */
    private void removeUnnecessaryElements(Document doc) {
        // 스크립트, 스타일 등 완전 제거
        doc.select("script, style, noscript, iframe, embed, object, meta, link").remove();

        // 네비게이션, 헤더, 푸터 등 제거
        doc.select("header, nav, footer, aside, .header, .nav, .footer, .sidebar").remove();

        // 광고 관련 요소 제거
        doc.select(".advertisement, .ads, .banner, .popup, .ad-container").remove();
        doc.select("[id*='ad'], [class*='ad-'], [id*='banner'], [class*='banner']").remove();

        // 숨겨진 요소 제거
        doc.select("[style*='display:none'], [style*='visibility:hidden']").remove();

        // 빈 요소 제거
        //doc.select("div:empty, span:empty, p:empty, li:empty").remove();

        log.debug("불필요한 HTML 요소 제거 완료");
    }

    /**
     * 사이트별 채용공고 텍스트 정보 추출
     */
    private List<JobTextInfo> extractJobTextInfos(Document doc, String siteName) {
        List<JobTextInfo> jobTexts = new ArrayList<>();

        // 사이트별 선택자로 시도
        String[] selectors = getJobSelectorsForSite(siteName);

        for (String selector : selectors) {
            Elements elements = doc.select(selector);

            for (Element element : elements) {
                JobTextInfo jobInfo = extractJobInfoFromElement(element, siteName);
                if (jobInfo.isValid()) {
                    jobTexts.add(jobInfo);
                }

                // 너무 많으면 제한
                if (jobTexts.size() >= 30) break;
            }

            if (!jobTexts.isEmpty()) break; // 첫 번째 성공한 선택자 사용
        }

        // 선택자로 못 찾으면 키워드 기반 검색
        if (jobTexts.isEmpty()) {
            jobTexts = extractJobInfosByKeywords(doc);
        }

        log.debug("채용공고 텍스트 추출 완료 - {}개 발견", jobTexts.size());
        return jobTexts;
    }

    /**
     * 사이트별 선택자 배열 반환
     */
    private String[] getJobSelectorsForSite(String siteName) {
        return switch (siteName) {
            case "사람인" -> new String[]{
                    "div.item_recruit",
                    ".recruit_info",
                    ".job_tit",
                    "div[class*='recruit']",
                    ".list_item"
            };
            case "잡코리아" -> new String[]{
                    "div[data-sentry-component='CardCommon']",
                    ".h7nnv10"
            };
            case "원티드" -> new String[]{
                    // 0: 공고 전체 컨테이너
                    "div.JobCard_container__zQcZs",
                    // 1: 상세 URL
                    "a[href]",
                    // 2: 공고 제목
                    "strong.JobCard_title___kfvj",
                    // 3: 회사명
                    "span.CompanyNameWithLocationPeriod__company__ByVLu",
                    // 4: 경력/요건
                    "span.CompanyNameWithLocationPeriod__location__4_w0l",
                    // 5: 이미지
                    "div.JobCard_thumbnail__A1ieG img"
            };
            case "프로그래머스" -> new String[]{
                    ".job-card",
                    ".position-item",
                    "div[class*='position']",
                    ".company-job-card"
            };
            case "점프" -> new String[]{
                    ".position-item",
                    ".job-item",
                    "div[class*='position']",
                    ".position-card"
            };
            default -> new String[]{
                    "div[class*='job']",
                    "div[class*='recruit']",
                    "div[class*='position']",
                    "li[class*='job']",
                    "a[href*='job']"
            };
        };
    }

    /**
     * 개별 요소에서 채용공고 정보 추출
     */
// 6. 기존 구조에 맞춘 수정된 메인 추출 메소드
    private JobTextInfo extractJobInfoFromElement(Element element, String siteName) {
        JobTextInfo info = new JobTextInfo();

        // 제목 추출 (안정적인 선택자 사용)
        info.title = findTextBySelectors(element, new String[]{
                // 원티드 특화 - 안정적인 방법들
                "strong[class*='title']",              // title이 포함된 strong 태그
                "strong[class*='Title']",              // Title이 포함된 strong 태그
                "div[class*='content'] strong",        // content 안의 strong
                "a[href*='/wd/'] strong",              // 원티드 링크 안의 strong
                "a[data-position-name]",               // data 속성에서 추출
                // 일반적인 선택자들
                "h1, h2, h3",
                ".title",
                ".job-title",
                ".recruit-title",
                "strong"  // 마지막 fallback
        });

        // 회사명 추출 (안정적인 선택자 사용)
        info.company = findTextBySelectors(element, new String[]{
                // 원티드 특화 - 안정적인 방법들
                "span[class*='company']",              // company가 포함된 span
                "span[class*='Company']",              // Company가 포함된 span
                "[data-company-name]",                 // data 속성에서 직접 추출
                "a[data-company-name]",                // 링크의 data 속성
                // 일반적인 선택자들
                ".company",
                ".company-name",
                ".corp-name",
                "[class*='company']"
        });

        // 위치/경력 추출 (안정적인 선택자 사용)
        info.location = findTextBySelectors(element, new String[]{
                // 원티드 특화 - 안정적인 방법들
                "span[class*='location']",             // location이 포함된 span
                "span[class*='Location']",             // Location이 포함된 span
                "span[class*='Period']",               // Period가 포함된 span (경력 정보)
                "div[class*='content'] span:nth-child(3)", // content의 3번째 span (경력이 보통 3번째)
                // 일반적인 선택자들
                ".location",
                ".area",
                ".workplace",
                "[class*='location']"
        });

        // 급여/보상 추출 (안정적인 선택자 사용)
        info.salary = findTextBySelectors(element, new String[]{
                // 원티드 특화 - 안정적인 방법들
                "span[class*='reward']",               // reward가 포함된 span
                "span[class*='Reward']",               // Reward가 포함된 span
                "*:contains('합격보상금')",             // '합격보상금' 텍스트 포함
                "*:contains('연봉')",                  // '연봉' 텍스트 포함
                "*:contains('만원')",                  // '만원' 텍스트 포함
                // 일반적인 선택자들
                ".salary",
                ".pay",
                ".wage",
                "[class*='salary']"
        });
        // URL 추출 (수정된 메소드 사용)
        info.url = findJobUrlFromElement(element, siteName);

        // 추가 설명
        String description = element.text().trim();
        if (description.length() > 50 && description.length() < 800) {
            info.description = description;
        }

        return info;
    }

    /**
     * 여러 선택자로 텍스트 찾기
     */
    private String findTextBySelectors(Element element, String[] selectors) {
        for (String selector : selectors) {
            try {
                // :contains() 선택자 처리
                if (selector.contains(":contains(")) {
                    String searchText = selector.substring(
                            selector.indexOf("'") + 1,
                            selector.lastIndexOf("'")
                    );
                    Elements elements = element.select("*");
                    for (Element el : elements) {
                        String text = el.ownText(); // 자식 요소 텍스트 제외
                        if (text.contains(searchText)) {
                            return text.trim();
                        }
                    }
                    continue;
                }

                // data 속성 확인
                if (selector.startsWith("[data-")) {
                    Element el = element.selectFirst("a");
                    if (el != null) {
                        String attrName = selector.substring(1, selector.length() - 1);
                        String attrValue = el.attr(attrName);
                        if (!attrValue.isEmpty()) {
                            return attrValue.trim();
                        }
                    }
                    continue;
                }

                // 일반 선택자 처리
                Element el = element.selectFirst(selector);
                if (el != null) {
                    String text = el.text().trim();
                    if (!text.isEmpty()) {
                        return text;
                    }
                }
            } catch (Exception e) {
                // 선택자 오류 시 다음 선택자 시도
                continue;
            }
        }
        return "";
    }

    private String convertToAbsoluteUrl(String relativeUrl, String siteName) {
        if (relativeUrl == null || relativeUrl.isEmpty()) {
            return "";
        }

        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl;
        }

        String baseUrl = getBaseUrlForSite(siteName);

        if (relativeUrl.startsWith("/")) {
            return baseUrl + relativeUrl;
        } else {
            return baseUrl + "/" + relativeUrl;
        }
    }

    /**
     * 요소에서 채용공고 URL 찾기
     */
    private String findJobUrlFromElement(Element element, String siteName) {
        String url = "";

        // 1순위: data 속성에서 추출 (가장 안정적)
        Element linkElement = element.selectFirst("a[href]");
        if (linkElement != null) {
            // data 속성들 확인
            url = linkElement.attr("data-href");
            if (url.isEmpty()) {
                url = linkElement.attr("href");
            }
        }

        // 2순위: 다양한 선택자로 링크 찾기
        if (url.isEmpty()) {
            String[] urlSelectors = {
                    "a[href*='/wd/']",       // 원티드 특화
                    "a[href*='job']",        // job 포함 URL
                    "a[href*='position']",   // position 포함 URL
                    "a[href]"                // 모든 링크
            };

            for (String selector : urlSelectors) {
                Element el = element.selectFirst(selector);
                if (el != null) {
                    url = el.attr("href");
                    if (!url.isEmpty()) {
                        break;
                    }
                }
            }
        }

        // 상대 URL을 절대 URL로 변환
        return convertToAbsoluteUrl(url, siteName);
    }

    /**
     * URL 정규화
     */
    private String getBaseUrlForSite(String siteName) {
        return switch (siteName.toLowerCase()) {
            case "원티드", "wanted" -> "https://www.wanted.co.kr";
            case "사람인", "saramin" -> "https://www.saramin.co.kr";
            case "잡코리아", "jobkorea" -> "https://www.jobkorea.co.kr";
            case "인크루트", "incruit" -> "https://www.incruit.com";
            default -> "https://www.wanted.co.kr";
        };
    }
    /**
     * 키워드 기반 채용공고 추출 (폴백)
     */
    private List<JobTextInfo> extractJobInfosByKeywords(Document doc) {
        List<JobTextInfo> jobs = new ArrayList<>();
        String[] keywords = {"채용", "모집", "개발", "engineer", "developer"};

        for (String keyword : keywords) {
            Elements elements = doc.select(String.format("*:contains(%s)", keyword));

            for (Element elem : elements.subList(0, Math.min(10, elements.size()))) {
                String text = elem.text().trim();
                if (text.length() >= 100 && text.length() <= 1000) {
                    JobTextInfo info = new JobTextInfo();
                    info.description = text;
                    info.title = extractTitleFromText(text);
                    info.company = extractCompanyFromText(text);

                    if (info.isValid()) {
                        jobs.add(info);
                    }
                }
            }

            if (jobs.size() >= 10) break;
        }

        return jobs;
    }

    /**
     * 텍스트에서 제목 추출
     */
    private String extractTitleFromText(String text) {
        String[] lines = text.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.length() > 5 && line.length() < 100 &&
                    (line.contains("개발") || line.contains("engineer") || line.contains("developer"))) {
                return line;
            }
        }
        return null;
    }

    /**
     * 텍스트에서 회사명 추출
     */
    private String extractCompanyFromText(String text) {
        // 간단한 패턴으로 회사명 찾기
        Pattern pattern = Pattern.compile("([가-힣A-Za-z0-9]+(?:주식회사|\\(주\\)|Inc\\.|Corp\\.|회사))");
        Matcher matcher = pattern.matcher(text);

        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        return null;
    }

    /**
     * 구조화된 채용공고 텍스트 생성
     */
    private String buildStructuredJobText(List<JobTextInfo> jobInfos) {
        if (jobInfos.isEmpty()) {
            return "";
        }

        StringBuilder structured = new StringBuilder();

        for (int i = 0; i < jobInfos.size(); i++) {
            JobTextInfo info = jobInfos.get(i);

            structured.append("=== 채용공고 ").append(i + 1).append(" ===\n");

            if (info.title != null) {
                structured.append("제목: ").append(info.title).append("\n");
            }
            if (info.company != null) {
                structured.append("회사: ").append(info.company).append("\n");
            }
            if (info.location != null) {
                structured.append("위치: ").append(info.location).append("\n");
            }
            if (info.salary != null) {
                structured.append("급여: ").append(info.salary).append("\n");
            }
            if (info.url != null) {
                structured.append("링크: ").append(info.url).append("\n");
            }
            if (info.description != null && info.description.length() < 500) {
                structured.append("설명: ").append(info.description).append("\n");
            }

            structured.append("\n");
        }

        return structured.toString().trim();
    }

    /**
     * 폴백 텍스트 추출
     */
    private String extractFallbackText(String html) {
        try {
            Document doc = Jsoup.parse(html);
            String text = doc.text();

            // 너무 길면 앞부분만 사용
            if (text.length() > MAX_CHUNK_SIZE) {
                text = text.substring(0, MAX_CHUNK_SIZE) + "...";
            }

            return text;
        } catch (Exception e) {
            log.debug("폴백 텍스트 추출도 실패", e);
            return "";
        }
    }

    /**
     * 토큰 제한 확인
     */
    private boolean isTextWithinTokenLimit(String text) {
        int estimatedTokens = text.length() / ESTIMATED_CHARS_PER_TOKEN;
        boolean withinLimit = estimatedTokens <= SAFE_TOKEN_LIMIT;

        log.debug("토큰 제한 확인 - 텍스트: {}자, 예상 토큰: {}, 제한 내: {}",
                text.length(), estimatedTokens, withinLimit);

        return withinLimit;
    }

    /**
     * 단일 텍스트 요청 처리
     */
    private List<JobPosting> processSingleTextRequest(String text, String siteName) {
        try {
            aiCallSemaphore.acquire();

            try {
                Thread.sleep(API_CALL_DELAY);

                log.debug("단일 텍스트 API 호출 - 크기: {}자", text.length());
                List<JobPosting> jobs = aiExtractionService.extractJobsFromText(text, siteName);

                dailyApiCallCount.incrementAndGet();

                log.debug("단일 텍스트 API 호출 성공 - 결과: {}개", jobs.size());
                return jobs;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ArrayList<>();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } finally {
            aiCallSemaphore.release();
        }
    }

    /**
     * 다중 텍스트 요청 처리
     */
    private List<JobPosting> processMultipleTextRequests(String text, String siteName) {
        // 텍스트를 청크로 분할
        List<String> chunks = splitTextIntoSmartChunks(text);
        List<JobPosting> allJobs = new ArrayList<>();

        log.debug("텍스트 분할 완료 - {}개 청크로 분할", chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            if (!canMakeApiCall()) {
                log.debug("API 호출 제한으로 청크 {}/{} 부터 중단", i + 1, chunks.size());
                break;
            }

            String chunk = chunks.get(i);

            try {
                List<JobPosting> chunkJobs = processSingleChunkWithLimits(chunk, siteName, i + 1, chunks.size());
                allJobs.addAll(chunkJobs);

                // 청크 간 딜레이
                if (i < chunks.size() - 1) {
                    Thread.sleep(3000); // 3초 대기
                }

            } catch (Exception e) {
                log.error("청크 {}/{} 처리 실패", i + 1, chunks.size(), e);
                // 다음 청크 계속 처리
            }
        }

        log.debug("다중 텍스트 처리 완료 - 총 {}개 추출", allJobs.size());
        return allJobs;
    }

    /**
     * 개별 청크 처리 (제한 적용)
     */
    private List<JobPosting> processSingleChunkWithLimits(String chunk, String siteName, int chunkNum, int totalChunks) {
        try {
            aiCallSemaphore.acquire();

            try {
                Thread.sleep(API_CALL_DELAY);

                log.debug("청크 {}/{} API 호출 - 크기: {}자", chunkNum, totalChunks, chunk.length());

                List<JobPosting> jobs = aiExtractionService.extractJobsFromText(chunk, siteName);
                dailyApiCallCount.incrementAndGet();

                log.debug("청크 {}/{} 완료 - {}개 추출 (API: {}/{})",
                        chunkNum, totalChunks, jobs.size(),
                        dailyApiCallCount.get(), MAX_DAILY_API_CALLS);

                return jobs;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ArrayList<>();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        } finally {
            aiCallSemaphore.release();
        }
    }

    /**
     * 스마트 텍스트 분할 (의미 단위 고려)
     */
    private List<String> splitTextIntoSmartChunks(String text) {
        List<String> chunks = new ArrayList<>();

        // 채용공고 구분자로 먼저 분할
        String[] sections = text.split("=== 채용공고 \\d+ ===");

        StringBuilder currentChunk = new StringBuilder();
        int sectionCount = 0;

        for (String section : sections) {
            if (section.trim().isEmpty()) continue;

            // 현재 청크에 이 섹션을 추가했을 때 크기 확인
            if (currentChunk.length() + section.length() > MAX_CHUNK_SIZE) {
                // 현재 청크 저장
                if (currentChunk.length() > 0) {
                    chunks.add(currentChunk.toString().trim());
                }

                // 새 청크 시작
                currentChunk = new StringBuilder();
                currentChunk.append("=== 채용공고 ").append(++sectionCount).append(" ===\n");
                currentChunk.append(section.trim());
            } else {
                if (currentChunk.length() > 0) {
                    currentChunk.append("\n=== 채용공고 ").append(++sectionCount).append(" ===\n");
                }
                currentChunk.append(section.trim());
            }
        }

        // 마지막 청크 추가
        if (currentChunk.length() > 0) {
            chunks.add(currentChunk.toString().trim());
        }

        // 분할이 제대로 안되었으면 크기별 분할
        if (chunks.isEmpty() || chunks.size() == 1 && chunks.get(0).length() > MAX_CHUNK_SIZE) {
            chunks = splitTextBySize(text);
        }

        return chunks;
    }

    /**
     * 크기별 텍스트 분할 (폴백)
     */
    private List<String> splitTextBySize(String text) {
        List<String> chunks = new ArrayList<>();
        int overlap = 500; // 청크 간 겹치는 부분

        for (int i = 0; i < text.length(); i += MAX_CHUNK_SIZE - overlap) {
            int end = Math.min(i + MAX_CHUNK_SIZE, text.length());
            chunks.add(text.substring(i, end));
        }

        return chunks;
    }

    /**
     * API 오류 처리
     */
    private void handleApiError(Exception e) {
        if (e.getMessage() != null) {
            String errorMsg = e.getMessage().toLowerCase();

            if (errorMsg.contains("429") || errorMsg.contains("rate_limit")) {
                log.error("API 할당량 제한 오류. 잠시 대기 필요");
                try {
                    Thread.sleep(60000); // 1분 대기
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else if (errorMsg.contains("token") || errorMsg.contains("context_length")) {
                log.error("토큰 제한 초과 오류. 텍스트 분할 로직 재검토 필요");
            }
        }
    }

    /**
     * AI 기반 개별 사이트 크롤링 (토큰 제한 최적화)
     */
    private List<JobPosting> crawlSpecificSiteWithAI(String siteId) {
        List<JobPosting> allJobs = new ArrayList<>();
        WebDriver driver = getDriver();

        try {
            String siteName = SUPPORTED_SITES.get(siteId);
            String urlPattern = SITE_URL_PATTERNS.get(siteId);

            if (urlPattern == null) {
                log.debug("URL 패턴이 없는 사이트: {}", siteId);
                return allJobs;
            }

            for (int page = 1; page <= MAX_PAGES_PER_SITE; page++) {
                if (!canMakeApiCall()) {
                    log.debug("API 호출 제한으로 {}({}) {}페이지 크롤링 중단", siteName, siteId, page);
                    break;
                }

                String url = String.format(urlPattern, page);
                log.debug("{}({}) {}페이지 크롤링 시작 (개선된 방식): {}", siteName, siteId, page, url);

                if (!loadPage(driver, url, siteName)) {
                    log.debug("{}({}) {}페이지 로드 실패", siteName, siteId, page);
                    continue;
                }

                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_DELAY, MAX_DELAY));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                String pageHtml = driver.getPageSource();
                log.debug("HTML 획득 완료: {}자, 텍스트 추출 및 분할 처리 시작", pageHtml.length());

                // *** 여기가 핵심 변경 부분 ***
                // 기존: callAiExtractionWithLimits(pageHtml, siteName)
                // 개선: callAiExtractionWithImprovedMethod(pageHtml, siteName)
                List<JobPosting> pageJobs = callAiExtractionWithImprovedMethod(pageHtml, siteName);

                log.debug("{}({}) {}페이지에서 개선된 방식으로 {}개 채용공고 추출",
                        siteName, siteId, page, pageJobs.size());

                if (pageJobs.isEmpty()) {
                    log.debug("{}({}) {}페이지에서 채용공고를 찾을 수 없음", siteName, siteId, page);
                    break;
                }

                allJobs.addAll(pageJobs);
            }

            // 상세 정보 수집
            if (!allJobs.isEmpty()) {
                fetchDetailsWithAI(siteName, allJobs);
            }

        } catch (Exception e) {
            log.error("{}({}) 개선된 크롤링 실패", SUPPORTED_SITES.get(siteId), siteId, e);
        }

        return allJobs;
    }

    /**
     * AI 기반 상세 정보 병렬 수집 (토큰 제한 고려)
     */
    private void fetchDetailsWithAI(String siteName, List<JobPosting> jobs) {
        List<Callable<Void>> tasks = new ArrayList<>();

        // API 호출 제한을 고려하여 작업 수 제한 (더 보수적으로)
        int maxDetailJobs = Math.min(jobs.size(),
                Math.max(1, MAX_DAILY_API_CALLS - dailyApiCallCount.get() - 5)); // 여유분 확보

        if (maxDetailJobs <= 0) {
            log.debug("API 호출 제한으로 상세 정보 수집 건너뜀");
            return;
        }

        List<JobPosting> limitedJobs = jobs.subList(0, Math.min(maxDetailJobs, jobs.size()));
        log.debug("상세 정보 수집 대상: {}개 (토큰 제한 고려)", limitedJobs.size());

        for (JobPosting job : limitedJobs) {
            if (job.getSourceUrl() == null || job.getSourceUrl().isBlank()) continue;

            tasks.add(() -> {
                try {
                    // 중복 체크
                    Boolean exists = jobPostingRepository.existsBySourceUrlAndIsActiveTrue(job.getSourceUrl());
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("상세 스킵(기존 활성): {} - {}", job.getCompany(), job.getTitle());
                        return null;
                    }
                } catch (Exception e) {
                    log.debug("기존 여부 확인 실패, 상세 시도 진행: {}", job.getSourceUrl());
                }

                WebDriver localDriver = getDriver();
                try {
                    crawlJobDetailWithAI(job, localDriver);
                } catch (Exception e) {
                    log.debug("AI 기반 상세 크롤링 작업 실패: {} - {}", siteName, job.getSourceUrl(), e);
                } finally {
                    closeDriver();
                }
                return null;
            });
        }

        if (tasks.isEmpty()) return;

        try {
            List<Future<Void>> futures = detailExecutor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get(120, TimeUnit.SECONDS); // 타임아웃 증가 (토큰 제한으로 응답 시간 증가 고려)
                } catch (TimeoutException te) {
                    log.debug("AI 기반 상세 크롤링 타임아웃");
                } catch (ExecutionException ee) {
                    log.debug("AI 기반 상세 크롤링 태스크 예외", ee.getCause());
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("AI 기반 상세 크롤링 병렬 처리 인터럽트", ie);
        }
    }

    /**
     * AI를 사용한 개별 채용공고 상세 정보 크롤링 (토큰 제한 최적화)
     */
    private void crawlJobDetailWithAI(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null || !canMakeApiCall()) {
            if (!canMakeApiCall()) {
                log.debug("API 호출 제한으로 상세 크롤링 스킵: {}", job.getSourceUrl());
            }
            return;
        }

        String originalUrl = driver.getCurrentUrl();

        try {
            log.debug("AI 기반 상세 페이지 크롤링 (토큰 최적화): {}", job.getSourceUrl());

            if (!loadPage(driver, job.getSourceUrl(), job.getSourceSite())) {
                log.debug("상세 페이지 로드 실패: {}", job.getSourceUrl());
                return;
            }

            // 페이지 로딩 완료 대기 (토큰 제한 고려 더 긴 대기)
            try {
                Thread.sleep(5000); // 5초로 증가
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            String pageSource = driver.getPageSource();
            log.debug("상세 페이지 소스 크기: {}자, 토큰 제한 적합성 확인 중...", pageSource.length());

            // AI를 사용하여 상세 정보 추출 (토큰 제한 적용)
            JobPosting updatedJob = callAiDetailExtractionWithLimits(job, pageSource);

            // 데이터베이스에 저장
            saveIndividualJob(updatedJob);

        } catch (Exception e) {
            log.debug("AI 기반 상세 정보 크롤링 실패: {}, error: {}", job.getSourceUrl(), e.getMessage());
        } finally {
            try {
                if (!originalUrl.equals(driver.getCurrentUrl())) {
                    driver.navigate().back();
                }
            } catch (Exception e) {
                log.debug("원래 페이지로 돌아가기 실패", e);
            }
        }
    }

    /**
     * 전체 사이트 크롤링 (AI 기반, 토큰 최적화)
     */
    @Async
    public CompletableFuture<List<JobPosting>> crawlAllSites() {
        List<JobPosting> allJobs = crawlAllSitesSync();
        closeDriver();
        return CompletableFuture.completedFuture(allJobs);
    }

    public List<JobPosting> crawlAllSitesSync() {
        List<JobPosting> allJobs = new ArrayList<>();

        try {
            log.debug("전체 사이트 AI 크롤링 시작 - API 제한: {}, 토큰 최적화 적용", MAX_DAILY_API_CALLS);

            // 모든 지원 사이트 크롤링
            for (Map.Entry<String, String> site : SUPPORTED_SITES.entrySet()) {
                if (!canMakeApiCall()) {
                    log.debug("일일 API 호출 제한으로 {}({}) 크롤링 중단", site.getValue(), site.getKey());
                    break;
                }

                try {
                    List<JobPosting> siteJobs = crawlSpecificSiteWithAI(site.getKey());
                    allJobs.addAll(siteJobs);
                    log.debug("{} AI 크롤링 완료: {}개 (API 호출: {}/{}, 토큰 최적화 적용)",
                            site.getValue(), siteJobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);

                    // 사이트 간 더 긴 간격 (토큰 제한 고려)
                    Thread.sleep(10000); // 10초

                } catch (Exception e) {
                    log.error("{} AI 크롤링 실패", site.getValue(), e);
                }
            }

            log.debug("전체 AI 크롤링 완료. 총 {}개 채용공고 수집 (API 호출: {}/{}, 토큰 최적화 적용)",
                    allJobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);

        } catch (Exception e) {
            log.error("AI 크롤링 중 오류 발생", e);
        }

        return allJobs;
    }

    // ===== WebDriver 관련 헬퍼 메서드들 =====

    private WebDriver getDriver() {
        WebDriver driver = driverThreadLocal.get();
        if (driver == null) {
            driver = createWebDriver();
            driverThreadLocal.set(driver);

            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(EXPLICIT_WAIT_SECONDS));
            waitThreadLocal.set(wait);
        }
        return driver;
    }

    private WebDriverWait getWait() {
        return waitThreadLocal.get();
    }

    private void closeDriver() {
        WebDriver driver = driverThreadLocal.get();
        if (driver != null) {
            try {
                driver.quit();
            } catch (Exception e) {
                log.debug("WebDriver 종료 중 오류", e);
            } finally {
                driverThreadLocal.remove();
                waitThreadLocal.remove();
            }
        }
    }

    private WebDriver createWebDriver() {
        try {
            // OS 감지
            String os = System.getProperty("os.name").toLowerCase();
            String driverPath;

            if (os.contains("win")) {
                driverPath = "drivers/chromedriver-win.exe";
            } else if (os.contains("linux")) {
                driverPath = "drivers/chromedriver-linux-linux";
            } else {
                throw new RuntimeException("지원하지 않는 OS: " + os);
            }

            System.setProperty("webdriver.chrome.driver", driverPath);

            // ChromeOptions 설정 (토큰 제한 최적화)
            ChromeOptions options = new ChromeOptions();
            options.addArguments("--headless=new");
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--disable-web-security");
            options.addArguments("--disable-features=VizDisplayCompositor");
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-plugins");
            options.addArguments("--window-size=1920,1080");
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);

            // 성능 최적화를 위한 설정 (토큰 제한 고려)
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.managed_default_content_settings.images", 2); // 이미지 비활성화
            prefs.put("profile.managed_default_content_settings.stylesheets", 2); // CSS도 비활성화 (토큰 절약)
            prefs.put("profile.managed_default_content_settings.cookies", 1);
            prefs.put("profile.managed_default_content_settings.javascript", 1);
            prefs.put("profile.managed_default_content_settings.plugins", 2);
            prefs.put("profile.managed_default_content_settings.popups", 2);
            prefs.put("profile.managed_default_content_settings.geolocation", 2);
            options.setExperimentalOption("prefs", prefs);

            // User-Agent 랜덤 설정
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            };
            String userAgent = userAgents[ThreadLocalRandom.current().nextInt(userAgents.length)];
            options.addArguments("--user-agent=" + userAgent);

            options.setExperimentalOption("useAutomationExtension", false);
            options.setExperimentalOption("excludeSwitches", Arrays.asList("enable-automation"));
            options.addArguments("--disable-blink-features=AutomationControlled");

            // ChromeDriver 생성
            WebDriver driver = new ChromeDriver(options);

            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(IMPLICIT_WAIT_SECONDS));
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECONDS));

            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

            log.debug("WebDriver 생성 성공 (토큰 최적화 설정 적용)");
            return driver;

        } catch (Exception e) {
            log.error("WebDriver 생성 실패", e);
            throw new RuntimeException("WebDriver 생성 실패", e);
        }
    }

    private boolean loadPage(WebDriver driver, String url, String siteName) {
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            try {
                log.debug("{} 페이지 로드 시도 {}: {}", siteName, retry + 1, url);
                driver.get(url);

                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(10));
                wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                        .executeScript("return document.readyState").equals("complete"));

                log.debug("{} 페이지 로드 성공: {}", siteName, url);
                return true;

            } catch (Exception e) {
                log.debug("{} 페이지 로드 실패 (시도 {}/{}): {} - {}",
                        siteName, retry + 1, MAX_RETRIES, url, e.getMessage());

                if (retry < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(3000 * (retry + 1)); // 재시도 간격 증가
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        log.error("{} 페이지 로드 모든 시도 실패: {}", siteName, url);
        return false;
    }

    // ===== 데이터베이스 저장 관련 메서드들 =====

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveIndividualJob(JobPosting job) {
        if (!isValidJob(job)) {
            log.debug("필수 필드 누락된 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
            return;
        }

        if (!jobPostingRepository.existsBySourceUrlAndIsActiveTrue(job.getSourceUrl())) {
            JobPosting newJob = createCleanJobPosting(job);

            JobPosting saved = jobPostingRepository.saveAndFlush(newJob);

            try {
                String content = buildContentForEmbedding(saved);
                if (!content.trim().isEmpty()) {
                    float[] embeddingArray = embeddingService.embed(content);
                    saved.setEmbedding(embeddingArray);
                    String embeddingText = floatArrayToVectorString(embeddingArray);

                    jobPostingRepository.updateEmbedding(saved.getId(), embeddingText);
                }
            } catch (Exception embeddingError) {
                log.debug("임베딩 생성 실패, null로 설정: {} - {}", saved.getCompany(), saved.getTitle(), embeddingError);
                saved.setEmbedding(null);
            }

            log.debug("새 채용공고 저장: {} - {}", saved.getCompany(), saved.getTitle());
        } else {
            log.debug("중복 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
        }
    }

    private JobPosting createCleanJobPosting(JobPosting source) {
        JobPosting newJob = new JobPosting();

        newJob.setTitle(source.getTitle() != null ? source.getTitle().trim() : null);
        newJob.setCompany(source.getCompany() != null ? source.getCompany().trim() : null);
        newJob.setSourceSite(source.getSourceSite());
        newJob.setSourceUrl(source.getSourceUrl());
        newJob.setJobCategory(source.getJobCategory());
        newJob.setLocation(source.getLocation() != null ? source.getLocation().trim() : null);
        newJob.setDescription(source.getDescription() != null ? source.getDescription().trim() : null);
        newJob.setRequirements(source.getRequirements() != null ? source.getRequirements().trim() : null);
        newJob.setBenefits(source.getBenefits() != null ? source.getBenefits().trim() : null);
        newJob.setSalary(source.getSalary() != null ? source.getSalary().trim() : null);
        newJob.setEmploymentType(source.getEmploymentType() != null ? source.getEmploymentType().trim() : null);
        newJob.setExperienceLevel(source.getExperienceLevel() != null ? source.getExperienceLevel().trim() : null);
        newJob.setDeadline(source.getDeadline());

        newJob.setIsActive(true);
        newJob.setCreatedAt(LocalDateTime.now());
        newJob.setUpdatedAt(LocalDateTime.now());
        newJob.setId(null);

        return newJob;
    }

    private String buildContentForEmbedding(JobPosting job) {
        StringBuilder content = new StringBuilder();

        appendIfNotEmpty(content, job.getTitle());
        appendIfNotEmpty(content, job.getCompany());
        appendIfNotEmpty(content, job.getDescription());
        appendIfNotEmpty(content, job.getRequirements());
        appendIfNotEmpty(content, job.getLocation());
        appendIfNotEmpty(content, job.getJobCategory());
        appendIfNotEmpty(content, job.getEmploymentType());
        appendIfNotEmpty(content, job.getExperienceLevel());

        return content.toString().trim();
    }

    private void appendIfNotEmpty(StringBuilder sb, String text) {
        if (text != null && !text.trim().isEmpty()) {
            sb.append(text.trim()).append(" ");
        }
    }

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
                job.getCompany() != null && !job.getCompany().trim().isEmpty();
    }

    private String floatArrayToVectorString(float[] array) {
        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    // ===== 상태 관리 및 정보 제공 메서드들 =====

    public Map<String, String> getSupportedSites() {
        return new HashMap<>(SUPPORTED_SITES);
    }

    public Map<String, Object> getSiteStatus(String siteId) {
        String siteName = SUPPORTED_SITES.get(siteId);
        if (siteName == null) {
            return Map.of("error", "지원하지 않는 사이트");
        }

        long jobCount = jobPostingRepository.countBySourceSiteAndIsActiveTrue(siteName);

        // 최근 24시간 내 수집된 공고 수
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        long recentJobs = jobPostingRepository.countBySourceSiteAndCreatedAtAfterAndIsActiveTrue(
                siteName, yesterday);

        return Map.of(
                "siteId", siteId,
                "siteName", siteName,
                "totalJobs", jobCount,
                "recentJobs", recentJobs,
                "lastCrawled", getLastCrawledTime(siteName),
                "extractionMethod", "AI-based (토큰 최적화)",
                "dailyApiCallsUsed", dailyApiCallCount.get(),
                "dailyApiCallsLimit", MAX_DAILY_API_CALLS,
                "tokenOptimized", true
        );
    }

    public List<Map<String, Object>> getAllSitesStatus() {
        return SUPPORTED_SITES.entrySet().stream()
                .map(entry -> getSiteStatus(entry.getKey()))
                .toList();
    }

    public Map<String, Object> getSiteStatistics() {
        Map<String, Object> stats = new HashMap<>();

        for (Map.Entry<String, String> site : SUPPORTED_SITES.entrySet()) {
            String siteName = site.getValue();
            long totalJobs = jobPostingRepository.countBySourceSiteAndIsActiveTrue(siteName);

            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            long weeklyJobs = jobPostingRepository.countBySourceSiteAndCreatedAtAfterAndIsActiveTrue(
                    siteName, weekAgo);

            stats.put(site.getKey(), Map.of(
                    "siteName", siteName,
                    "totalJobs", totalJobs,
                    "weeklyJobs", weeklyJobs,
                    "extractionMethod", "AI-based (토큰 최적화)"
            ));
        }

        // API 사용량 정보 추가
        stats.put("apiUsage", Map.of(
                "dailyCallsUsed", dailyApiCallCount.get(),
                "dailyCallsLimit", MAX_DAILY_API_CALLS,
                "remainingCalls", MAX_DAILY_API_CALLS - dailyApiCallCount.get(),
                "lastResetDate", lastResetDate.toString(),
                "tokenOptimized", true,
                "maxHtmlSizeForAI", MAX_HTML_SIZE_FOR_AI,
                "estimatedTokenLimit", SAFE_TOKEN_LIMIT
        ));

        return stats;
    }

    private String getLastCrawledTime(String siteName) {
        try {
            Optional<LocalDateTime> lastCrawled = jobPostingRepository
                    .findFirstBySourceSiteOrderByCreatedAtDesc(siteName)
                    .map(JobPosting::getCreatedAt);

            return lastCrawled.map(LocalDateTime::toString).orElse("없음");
        } catch (Exception e) {
            return "확인 불가";
        }
    }

    /**
     * API 호출 카운터를 수동으로 리셋 (테스트 목적)
     */
    public void resetApiCallCounter() {
        dailyApiCallCount.set(0);
        lastResetDate = LocalDateTime.now().toLocalDate().atStartOfDay();
        log.debug("API 호출 카운터가 수동으로 리셋되었습니다. (토큰 최적화 버전)");
    }

    // ===== 스케줄링 작업들 =====

    @Scheduled(cron = "0 0 0 * * *") // 매일 자정
    public void resetDailyApiCounter() {
        checkAndResetDailyCounter();
        log.debug("일일 API 호출 카운터 자동 리셋 완료 (토큰 최적화 버전)");
    }

    @Scheduled(cron = "0 0 2 * * SUN")
    @Transactional
    public void cleanupOldJobs() {
        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
            int deleted = jobPostingRepository.deleteByCreatedAtBefore(cutoffDate);
            log.debug("30일 이전 채용공고 {}개 삭제", deleted);
        } catch (Exception e) {
            log.error("오래된 채용공고 정리 실패", e);
        }
    }

    @Scheduled(cron = "0 30 2 * * SUN")
    @Transactional
    public void deactivateExpiredJobs() {
        try {
            LocalDateTime now = LocalDateTime.now();
            int updated = jobPostingRepository.deactivateExpiredJobs(now);
            log.debug("마감된 채용공고 {}개 비활성화", updated);
        } catch (Exception e) {
            log.error("마감된 채용공고 비활성화 실패", e);
        }
    }

    // ===== 테스트/디버깅 메서드들 (토큰 최적화) =====

    public void testAiExtraction(String url, String siteName) {
        log.debug("AI 추출 테스트 시작 (토큰 최적화): {} (API 호출 가능: {})", url, canMakeApiCall());

        if (!canMakeApiCall()) {
            log.error("API 호출 제한으로 테스트를 수행할 수 없습니다. 사용량: {}/{}",
                    dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
            return;
        }

        WebDriver driver = getDriver();
        try {
            if (loadPage(driver, url, siteName)) {
                log.debug("페이지 로드 성공");

                String html = driver.getPageSource();
                log.debug("HTML 크기: {}자, 토큰 제한 적합성: {}", html.length(), isHtmlSuitableForAI(html));

                // 토큰 제한 고려한 AI 추출
                List<JobPosting> jobs = callAiExtractionWithLimits(html, siteName);

                log.debug("AI 추출 결과 (토큰 최적화): {}개 채용공고 (API 호출: {}/{})",
                        jobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);

                for (int i = 0; i < Math.min(3, jobs.size()); i++) {
                    JobPosting job = jobs.get(i);
                    log.debug("채용공고 {}: {} - {} ({})", i+1, job.getCompany(), job.getTitle(), job.getSourceUrl());
                }
            } else {
                log.error("페이지 로드 실패");
            }
        } finally {
            closeDriver();
        }
    }

    /**
     * 토큰 사용량 분석 메서드
     */
    public Map<String, Object> analyzeTokenUsage(String html) {
        if (html == null) {
            return Map.of("error", "HTML이 null입니다");
        }

        int originalLength = html.length();
        int estimatedOriginalTokens = originalLength / ESTIMATED_CHARS_PER_TOKEN;

        String processed = preprocessHtmlForTokenLimit(html);
        int processedLength = processed.length();
        int estimatedProcessedTokens = processedLength / ESTIMATED_CHARS_PER_TOKEN;

        boolean suitable = isHtmlSuitableForAI(processed);

        return Map.of(
                "originalLength", originalLength,
                "processedLength", processedLength,
                "estimatedOriginalTokens", estimatedOriginalTokens,
                "estimatedProcessedTokens", estimatedProcessedTokens,
                "reductionRatio", (double)(originalLength - processedLength) / originalLength * 100,
                "suitableForAI", suitable,
                "maxAllowedTokens", SAFE_TOKEN_LIMIT,
                "maxAllowedHtmlSize", MAX_HTML_SIZE_FOR_AI
        );
    }

    /**
     * 토큰 제한 설정 정보 반환
     */
    public Map<String, Object> getTokenLimitSettings() {
        return Map.of(
                "maxInputTokens", SAFE_TOKEN_LIMIT,
                "maxHtmlSizeForAI", MAX_HTML_SIZE_FOR_AI,
                "estimatedCharsPerToken", ESTIMATED_CHARS_PER_TOKEN,
                "apiCallDelay", API_CALL_DELAY,
                "maxConcurrentCalls", MAX_CONCURRENT_AI_CALLS,
                "maxDailyApiCalls", MAX_DAILY_API_CALLS,
                "detailParallelism", DETAIL_PARALLELISM,
                "maxPagesPerSite", MAX_PAGES_PER_SITE,
                "minDelay", MIN_DELAY,
                "maxDelay", MAX_DELAY
        );
    }

    /**
     * 토큰 제한 설정 업데이트 (런타임)
     */
    public void updateTokenLimitSettings(int maxHtmlSize, int safeTokenLimit, long apiDelay) {
        // 주의: 이 메서드는 상수를 직접 변경할 수 없으므로 로깅만 수행
        log.debug("토큰 제한 설정 업데이트 요청 - HTML 크기: {}, 토큰 제한: {}, API 지연: {}ms",
                maxHtmlSize, safeTokenLimit, apiDelay);
        log.debug("현재 설정은 상수로 정의되어 런타임 변경이 불가능합니다. 코드를 수정해주세요.");
    }

    // JobTextInfo 내부 클래스
    private static class JobTextInfo {
        String title;
        String company;
        String location;
        String salary;
        String url;
        String description;

        boolean isValid() {
            return (title != null && !title.trim().isEmpty()) ||
                    (company != null && !company.trim().isEmpty()) ||
                    (description != null && description.length() > 50);
        }

        @Override
        public String toString() {
            return String.format("JobTextInfo{title='%s', company='%s', url='%s'}",
                    title, company, url);
        }
    }

    /**
     * 개선된 방식의 효과 분석 메서드 (디버깅/모니터링용)
     */
    public Map<String, Object> analyzeExtractionEfficiency(String html, String siteName) {
        Map<String, Object> analysis = new HashMap<>();

        try {
            long startTime = System.currentTimeMillis();

            // 기존 방식 시뮬레이션
            String processedHtml = preprocessHtmlForTokenLimit(html);
            boolean oldMethodSuitable = isHtmlSuitableForAI(processedHtml);

            // 개선된 방식
            String extractedText = extractMeaningfulJobText(html, siteName);
            boolean newMethodSuitable = isTextWithinTokenLimit(extractedText);

            long endTime = System.currentTimeMillis();

            // 분석 결과
            analysis.put("processingTime", endTime - startTime);
            analysis.put("originalHtmlSize", html.length());
            analysis.put("oldMethodProcessedSize", processedHtml.length());
            analysis.put("oldMethodSuitable", oldMethodSuitable);
            analysis.put("newMethodExtractedSize", extractedText.length());
            analysis.put("newMethodSuitable", newMethodSuitable);
            analysis.put("compressionRatio", (double) extractedText.length() / html.length());
            analysis.put("improvementFactor", (double) processedHtml.length() / extractedText.length());

            // 토큰 추정
            int oldTokens = processedHtml.length() / ESTIMATED_CHARS_PER_TOKEN;
            int newTokens = extractedText.length() / ESTIMATED_CHARS_PER_TOKEN;
            analysis.put("oldMethodTokens", oldTokens);
            analysis.put("newMethodTokens", newTokens);
            analysis.put("tokenSavings", oldTokens - newTokens);

            // 분할 필요성
            if (!newMethodSuitable && extractedText.length() > 0) {
                List<String> chunks = splitTextIntoSmartChunks(extractedText);
                analysis.put("chunksNeeded", chunks.size());
                analysis.put("avgChunkSize", chunks.stream().mapToInt(String::length).average().orElse(0));
                analysis.put("estimatedApiCalls", chunks.size());
            } else {
                analysis.put("chunksNeeded", 1);
                analysis.put("estimatedApiCalls", newMethodSuitable ? 1 : 0);
            }

            // 텍스트 품질 평가
            analysis.put("textQuality", evaluateTextQuality(extractedText));
            analysis.put("jobKeywordCount", countJobKeywords(extractedText));

        } catch (Exception e) {
            analysis.put("error", e.getMessage());
        }

        return analysis;
    }

    private double evaluateTextQuality(String text) {
        if (text == null || text.isEmpty()) return 0.0;

        double quality = 0.0;

        // 기본 품질 점수
        if (text.length() > 500) quality += 0.2;
        if (text.length() > 2000) quality += 0.1;

        // 구조화 점수
        if (text.contains("제목:") || text.contains("회사:")) quality += 0.2;
        if (text.contains("=== 채용공고")) quality += 0.1;

        // 키워드 점수
        String lowerText = text.toLowerCase();
        if (lowerText.contains("개발") || lowerText.contains("developer")) quality += 0.1;
        if (lowerText.contains("채용") || lowerText.contains("모집")) quality += 0.1;
        if (lowerText.contains("회사") || lowerText.contains("company")) quality += 0.1;
        if (lowerText.contains("급여") || lowerText.contains("연봉")) quality += 0.1;

        // 링크 점수
        if (text.contains("http://") || text.contains("https://")) quality += 0.1;

        return Math.min(1.0, quality);
    }

    private long countJobKeywords(String text) {
        if (text == null) return 0;

        String[] keywords = {
                "채용", "모집", "개발자", "개발", "engineer", "developer",
                "회사", "company", "급여", "연봉", "salary", "경력", "신입",
                "정규직", "계약직", "프리랜서", "인턴"
        };

        String lowerText = text.toLowerCase();
        return Arrays.stream(keywords)
                .mapToLong(keyword -> {
                    String searchKeyword = keyword.toLowerCase();
                    int count = 0;
                    int index = 0;
                    while ((index = lowerText.indexOf(searchKeyword, index)) != -1) {
                        count++;
                        index += searchKeyword.length();
                    }
                    return count;
                })
                .sum();
    }

}