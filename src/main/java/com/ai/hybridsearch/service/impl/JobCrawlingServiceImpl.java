package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.config.CrawlingApiProperties;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.ai.hybridsearch.service.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class JobCrawlingServiceImpl implements JobCrawlingService {

    // Dependencies
    @Autowired private JobPostingRepository jobPostingRepository;
    @Autowired private EmbeddingService embeddingService;
    @Autowired private AiExtractionService aiExtractionService;
    @Autowired private CrawlingApiService crawlingApiService;
    @Autowired private CrawlingApiProperties properties; // 설정 주입
    @Autowired private OcrService ocrService;

    // Constants
    private static final Map<String, String> SUPPORTED_SITES = Map.of("saramin", "사람인", "jobkorea", "잡코리아", "wanted", "원티드");
    private static final Map<String, String> SITE_URL_PATTERNS = Map.of(
            "saramin", "https://www.saramin.co.kr/zf_user/search/recruit?searchType=search&searchword=개발자&recruitPage=%d",
            "jobkorea", "https://www.jobkorea.co.kr/Search/?stext=개발자&Page_No=%d",
            "wanted", "https://www.wanted.co.kr/search?query=개발자&tab=position&page=%d",
            "programmers", "https://career.programmers.co.kr/job?page=%d",
            "jumpit", "https://www.jumpit.co.kr/positions?page=%d"
    );

    // Crawling settings
    private static final long MIN_DELAY = 3000;
    private static final long MAX_DELAY = 8000;
    private static final int MAX_PAGES_PER_SITE = 1;
    private static final long API_CALL_DELAY = 5000;
    private static final int MAX_CONCURRENT_AI_CALLS = 1;
    private static final int MAX_DAILY_API_CALLS = 30;
    private static final int DETAIL_PARALLELISM = 1;

    // Token limit settings
    private static final int MAX_HTML_SIZE_FOR_AI = 10000000;
    private static final int ESTIMATED_CHARS_PER_TOKEN = 4;
    private static final int SAFE_TOKEN_LIMIT = 1000000;

    // Selenium settings from user's code
    private static final int IMPLICIT_WAIT_SECONDS = 10;
    private static final int PAGE_LOAD_TIMEOUT_SECONDS = 30;

    // Concurrency & State Management
    private ExecutorService detailExecutor;
    private Semaphore aiCallSemaphore;
    private final AtomicInteger dailyApiCallCount = new AtomicInteger(0);
    private volatile LocalDateTime lastResetDate = LocalDateTime.now().toLocalDate().atStartOfDay();

    // Selenium WebDriver instance
    private WebDriver driver;

    @PostConstruct
    public void init() {
        try {
            detailExecutor = Executors.newFixedThreadPool(DETAIL_PARALLELISM);
            aiCallSemaphore = new Semaphore(MAX_CONCURRENT_AI_CALLS);
            log.info("JobCrawlingService 초기화 완료 - AI 호출 제한 적용, 병렬 스레드 {}개 준비", DETAIL_PARALLELISM);

            // Initialize WebDriver only if it's the selected method
            if (properties.getDetailFetchMethod() == CrawlingApiProperties.DetailFetchMethod.SELENIUM) {
                this.driver = createWebDriver();
            }
        } catch (Exception e) {
            log.error("초기화 실패", e);
        }
    }

    private WebDriver createWebDriver() {
        try {
            ChromeOptions options = new ChromeOptions();

            // 1. [핵심 변경] OS 환경에 따른 드라이버 경로 설정 (ARM 서버 이슈 해결)
            String os = System.getProperty("os.name").toLowerCase();

            if (os.contains("linux")) {
                // Linux(서버) 환경: apt로 설치한 시스템 브라우저/드라이버 강제 지정
                // 이렇게 하면 Selenium Manager가 실행되지 않아 "Syntax error"가 발생하지 않음
                System.setProperty("webdriver.chrome.driver", "/usr/bin/chromedriver");
                options.setBinary("/usr/bin/chromium-browser");

                // 리눅스 서버 필수 옵션
                options.addArguments("--headless=new"); // 최신 헤드리스 모드
                options.addArguments("--no-sandbox");
                options.addArguments("--disable-dev-shm-usage");
                options.addArguments("--disable-gpu");
                options.addArguments("--remote-allow-origins=*"); // 연결 거부 방지
            } else {
                // Windows(로컬 개발) 환경: 기존 방식 유지 (또는 Selenium Manager 자동 사용)
                // 로컬에 drivers 폴더가 있다면 유지, 없다면 아래 줄 주석 처리 시 자동 다운로드됨
                 System.setProperty("webdriver.chrome.driver", "drivers/chromedriver-win.exe");
            }

            // 2. 스마트 차단 회피 설정 (기존 유지 + 강화)
            options.addArguments("--disable-web-security");
            options.addArguments("--disable-features=VizDisplayCompositor");
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-plugins");
            options.addArguments("--disable-blink-features=AutomationControlled"); // 자동화 탐지 방지
            options.setExperimentalOption("useAutomationExtension", false);
            options.setExperimentalOption("excludeSwitches", Collections.singletonList("enable-automation"));

            // 3. 성능 최적화 (토큰/속도 절약) - 기존 로직 유지
            options.setPageLoadStrategy(PageLoadStrategy.EAGER); // DOM 로드 시점까지만 대기
            options.addArguments("--window-size=1920,1080");

            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.managed_default_content_settings.images", 2); // 이미지 차단
            prefs.put("profile.managed_default_content_settings.stylesheets", 2); // CSS 차단
            // 추가: 팝업, 위치 정보 등 불필요한 권한 요청 차단
            prefs.put("profile.default_content_setting_values.notifications", 2);
            prefs.put("profile.default_content_setting_values.geolocation", 2);
            options.setExperimentalOption("prefs", prefs);

            // 4. User-Agent 랜덤 설정 (기존 로직 유지)
            String[] userAgents = {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            };
            String userAgent = userAgents[ThreadLocalRandom.current().nextInt(userAgents.length)];
            options.addArguments("--user-agent=" + userAgent);

            // 5. WebDriver 인스턴스 생성
            ChromeDriver driver = new ChromeDriver(options);

            // 6. [스마트 기능 추가] CDP(Chrome DevTools Protocol)를 이용한 완벽한 위장
            // 기존 js.executeScript보다 훨씬 강력함. 페이지 로딩 '전'에 실행되어 탐지를 원천 봉쇄.
            Map<String, Object> cdpParams = new HashMap<>();
            cdpParams.put("source", "Object.defineProperty(navigator, 'webdriver', { get: () => undefined })");
            driver.executeCdpCommand("Page.addScriptToEvaluateOnNewDocument", cdpParams);

            // 타임아웃 설정
            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(IMPLICIT_WAIT_SECONDS));
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECONDS));

            log.info("스마트 WebDriver 생성 성공 (OS: {}, Path: /usr/bin/chromium-browser)", os);
            return driver;

        } catch (Exception e) {
            log.error("WebDriver 생성 실패: 드라이버 경로 및 설치 상태를 확인하세요.", e);
            throw new RuntimeException("WebDriver 생성 실패", e);
        }
    }

/*
    private WebDriver createWebDriver() {
        try {
            // OS 감지
            String os = System.getProperty("os.name").toLowerCase();
            String driverPath;
            if (os.contains("win")) {
                driverPath = "drivers/chromedriver-win.exe";
            } else if (os.contains("linux")) {
                driverPath = "drivers/chromedriver-linux";
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
            options.setExperimentalOption("excludeSwitches", Collections.singletonList("enable-automation"));
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
*/


    @PreDestroy
    public void cleanup() {
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
        // Cleanup WebDriver if it was initialized
        if (driver != null) {
            driver.quit();
            log.info("ChromeDriver shut down.");
        }
        log.info("JobCrawlingService 정리 완료");
    }

    private void crawlJobDetailWithAI(JobPosting job) {
        if (job.getSourceUrl() == null || !canMakeApiCall()) {
            if (!canMakeApiCall()) log.warn("API 호출 제한으로 상세 크롤링 스킵: {}", job.getSourceUrl());
            return;
        }
        try {
            log.info("AI 기반 상세 페이지 크롤링 시작: {}", job.getSourceUrl());
            String pageSource;
            String originalUrl = job.getSourceUrl();

            // 1. Get initial page source using the selected method (Selenium or ScrapingBee)
            CrawlingApiProperties.DetailFetchMethod method = properties.getDetailFetchMethod();
            log.info("상세 페이지 크롤링 방법: {}", method);

            if (method == CrawlingApiProperties.DetailFetchMethod.SELENIUM) {
                if (this.driver == null) throw new IllegalStateException("Selenium WebDriver is not initialized.");
                pageSource = crawlingApiService.fetchDetailHtmlWithSelenium(this.driver, originalUrl);
            } else { // SCRAPINGBEE
                pageSource = crawlingApiService.fetchDetailHtmlWithScrapingBee(originalUrl);
            }

            // 2. Use AI to find a potential iframe src from the initial page source
            String iframeSrc = aiExtractionService.findIframeSrc(pageSource);

            // 3. If iframe found, re-fetch content from the iframe's absolute URL
            if (iframeSrc != null && !iframeSrc.isBlank()) {
                URI baseUri = new URI(originalUrl);
                String absoluteIframeUrl = baseUri.resolve(iframeSrc).toString();
                log.info("AI가 iframe을 찾음. 최종 콘텐츠 URL로 이동/요청: {}", absoluteIframeUrl);

                if (method == CrawlingApiProperties.DetailFetchMethod.SELENIUM) {
                    pageSource = crawlingApiService.fetchDetailHtmlWithSelenium(this.driver, absoluteIframeUrl);
                } else { // SCRAPINGBEE
                    pageSource = crawlingApiService.fetchDetailHtmlWithScrapingBee(absoluteIframeUrl);
                }
            }

            // 4. Perform OCR check on the FINAL page source
            Document doc = Jsoup.parse(pageSource, originalUrl);
            String bodyText = doc.body().text();

            if (bodyText.length() < 300 && !doc.body().select("img[src~=/[^/]]").isEmpty()) {
                StringBuilder combinedOcrText = new StringBuilder();
                Elements images = doc.body().select("img[src~=/[^/]]"); // Select images with a path-like src
                log.info("이미지 기반 공고 발견. 처리할 이미지 수: {}", images.size());

                for (Element image : images) {
                    String imageUrl = image.absUrl("src");
                    if (imageUrl.isEmpty()) {
                        log.warn("이미지 src가 비어있습니다. 건너뜁니다.");
                        continue;
                    }
                    log.info("이미지 URL: {}", imageUrl);
                    try {
                        byte[] imageData = crawlingApiService.fetchImage(imageUrl);
                        log.info("이미지 다운로드 성공. 크기: {} bytes. OCR 시작...", imageData.length);

                        //String ocrText = ocrService.extractTextFromImage(imageData);
                        String ocrText = aiExtractionService.extractJobDetailFromImage(job, imageData);
                        if (ocrText != null && !ocrText.isBlank()) {
                            log.info("OCR 성공. 추출된 텍스트 길이: {}", ocrText.length());
                            combinedOcrText.append(ocrText).append("\n\n");
                        } else {
                            log.warn("OCR 결과 텍스트가 없습니다. 이미지: {}", imageUrl);
                        }
                    } catch (Exception e) {
                        log.error("이미지 OCR 처리 실패: {}. 오류: {}", imageUrl, e.getMessage());
                        // Continue to next image or use existing pageSource if all fail
                    }
                }
                // If any OCR text was successfully combined, replace the pageSource
                if (combinedOcrText.length() > 0) {
                    pageSource = combinedOcrText.toString();
                    log.info("이미지 공고 내용 OCR 텍스트로 대체 완료. 최종 크기: {} chars", pageSource.length());
                } else {
                    log.warn("이미지 공고 OCR을 시도했으나 유효한 텍스트를 얻지 못했습니다. 원본 HTML로 진행합니다.");
                }
            }

            // 5. Pass final content to AI for extraction
            log.info("AI 분석을 위한 최종 콘텐츠 크기: {} chars", pageSource.length());
            JobPosting updatedJob = callAiDetailExtractionWithLimits(job, pageSource);
            saveIndividualJob(updatedJob);
        } catch (Exception e) {
            log.error("AI 기반 상세 정보 크롤링 전체 과정 실패: {}, error: {}", job.getSourceUrl(), e.getMessage());
            // Ensure thread is interrupted if needed for async operations
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new RuntimeException("상세 정보 크롤링 및 AI 분석 실패: " + job.getSourceUrl(), e);
        }
    }

    private List<JobPosting> crawlSpecificSiteWithAI(String siteId) {
        List<JobPosting> allJobs = new ArrayList<>();
        String siteName = SUPPORTED_SITES.get(siteId);
        String urlPattern = SITE_URL_PATTERNS.get(siteId);

        if (urlPattern == null) {
            log.warn("URL 패턴이 없는 사이트: {}", siteId);
            return allJobs;
        }

        for (int page = 1; page <= MAX_PAGES_PER_SITE; page++) {
            if (!canMakeApiCall()) {
                log.warn("API 호출 제한으로 {}({}) {}페이지 크롤링 중단", siteName, siteId, page);
                break;
            }
            String url = String.format(urlPattern, page);
            log.info("{}({}) {}페이지 크롤링 시작: {}", siteName, siteId, page, url);

            try {
                // Use fetchListHtml for list pages
                String pageHtml = crawlingApiService.fetchListHtml(url);
                log.info("HTML 획득 완료: {}자, AI 추출 시작", pageHtml.length());

                List<JobPosting> pageJobs = callAiExtractionWithLimits(pageHtml, siteName);
                log.info("{}({}) {}페이지에서 {}개 채용공고 추출", siteName, siteId, page, pageJobs.size());

                if (pageJobs.isEmpty()) {
                    log.info("{}({}) {}페이지에서 채용공고를 찾을 수 없음", siteName, siteId, page);
                    break;
                }
                allJobs.addAll(pageJobs);
            } catch (IOException | InterruptedException e) {
                log.error("{}({}) {}페이지 로드 또는 처리 실패: {}", siteName, siteId, page, e.getMessage());
                Thread.currentThread().interrupt();
                break;
            }

            try {
                Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_DELAY, MAX_DELAY));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!allJobs.isEmpty()) {
            fetchDetailsWithAI(siteName, allJobs);
        }
        return allJobs;
    }

    public void testAiExtraction(String url, String siteName) {
        log.info("AI 추출 테스트 시작: {} (API 호출 가능: {})", url, canMakeApiCall());
        if (!canMakeApiCall()) {
            log.error("API 호출 제한으로 테스트를 수행할 수 없습니다. 사용량: {}/{}",
                    dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
            return;
        }
        try {
            log.info("페이지 HTML 로드 중...");
            // Use fetchListHtml for testing as well
            String html = crawlingApiService.fetchListHtml(url);
            log.info("HTML 크기: {}자, AI 추출 시작...", html.length());
            List<JobPosting> jobs = callAiExtractionWithLimits(html, siteName);
            log.info("AI 추출 결과: {}개 채용공고 (API 호출: {}/{})", jobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
            for (int i = 0; i < Math.min(3, jobs.size()); i++) {
                JobPosting job = jobs.get(i);
                log.info("채용공고 {}: {} - {} ({})", i + 1, job.getCompany(), job.getTitle(), job.getSourceUrl());
            }
        } catch (Exception e) {
            log.error("AI 추출 테스트 실패", e);
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    // =================================================================================
    //  BELOW: Unchanged methods from the original file, provided for completeness
    // =================================================================================

    private void checkAndResetDailyCounter() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime today = now.toLocalDate().atStartOfDay();

        if (today.isAfter(lastResetDate)) {
            dailyApiCallCount.set(0);
            lastResetDate = today;
            log.info("일일 API 호출 카운터 리셋");
        }
    }

    private boolean canMakeApiCall() {
        checkAndResetDailyCounter();
        return dailyApiCallCount.get() < MAX_DAILY_API_CALLS;
    }

    private boolean isHtmlSuitableForAI(String html) {
        if (html == null || html.isEmpty()) {
            return false;
        }
        int estimatedTokens = html.length() / ESTIMATED_CHARS_PER_TOKEN;
        boolean suitable = estimatedTokens <= SAFE_TOKEN_LIMIT;
        log.info("HTML 토큰 적합성 체크 - 크기: {}자, 예상 토큰: {}, 적합: {}", html.length(), estimatedTokens, suitable);
        return suitable;
    }

    private String preprocessHtmlForTokenLimit(String html) {
        if (html == null || html.isEmpty()) return "";
        try {
            log.info("HTML Jsoup 전처리 시작 - 원본 크기: {}자", html.length());
            Document doc = Jsoup.parse(html);
            doc.select("header, footer, nav, aside, .sidebar, #header, #footer, .header, .footer, script, style, link, meta, noscript, iframe, .advertisement, .banner").remove();
            Safelist safelist = Safelist.none()
                    .addTags("a", "b", "blockquote", "br", "caption", "cite", "code", "col", "colgroup", "dd", "div", "dl", "dt", "em", "h1", "h2", "h3", "h4", "h5", "h6", "i", "img", "li", "ol", "p", "pre", "q", "small", "span", "strike", "strong", "sub", "sup", "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul")
                    .addAttributes("a", "href", "title").addAttributes("img", "src", "alt", "title").addAttributes("blockquote", "cite");
            String cleanedHtml = Jsoup.clean(doc.body().html(), safelist);
            String finalHtml = cleanedHtml.replaceAll("\\s{2,}", " ").replaceAll("(?m)^[ \t]*\r?\n", "").trim();
            log.info("HTML Jsoup 전처리 완료 (Safelist 화이트리스트 방식) - 축소 후 크기: {}자", finalHtml.length());
            return finalHtml;
        } catch (Exception e) {
            log.error("HTML Jsoup 전처리 중 오류 발생", e);
            return html.replaceAll("<script[^>]*>.*?</script>", "").replaceAll("<style[^>]*>.*?</style>", "").replaceAll("<!--.*?-->", "").replaceAll("\\s{2,}", " ");
        }
    }

    private List<String> splitHtmlIntoChunks(String html) {
        List<String> chunks = new ArrayList<>();
        int chunkSize = (SAFE_TOKEN_LIMIT - 500) * ESTIMATED_CHARS_PER_TOKEN;
        for (int i = 0; i < html.length(); i += chunkSize) {
            chunks.add(html.substring(i, Math.min(i + chunkSize, html.length())));
        }
        log.info("{}개의 청크로 분할되었습니다.", chunks.size());
        return chunks;
    }

    private List<JobPosting> callAiExtractionForSingleBlock(String htmlBlock, String siteName) {
        if (!canMakeApiCall()) {
            log.warn("일일 API 호출 제한 도달: {}/{}", dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
            return Collections.emptyList();
        }
        try {
            aiCallSemaphore.acquire();
            try {
                Thread.sleep(API_CALL_DELAY);
                long startTime = System.currentTimeMillis();
                List<JobPosting> result = aiExtractionService.extractJobsFromHtml(htmlBlock, siteName);
                long endTime = System.currentTimeMillis();
                dailyApiCallCount.incrementAndGet();
                log.info("AI 추출 성공 - 호출 횟수: {}/{}, 응답시간: {}ms, 결과: {}개", dailyApiCallCount.get(), MAX_DAILY_API_CALLS, (endTime - startTime), result.size());
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("AI 호출 스레드 중단됨", e);
                return Collections.emptyList();
            } catch (RuntimeException e) {
                if (e.getMessage() != null && (e.getMessage().contains("429") || e.getMessage().contains("token") || e.getMessage().contains("context_length"))) {
                    log.error("API 할당량 또는 토큰 제한 초과 오류 발생. 잠시 대기 후 재시도합니다.");
                    try {
                        Thread.sleep(60000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                throw e;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AI 호출 세마포어 대기 중 중단됨", e);
            return Collections.emptyList();
        } finally {
            aiCallSemaphore.release();
        }
    }

    private List<JobPosting> callAiExtractionWithLimits(String html, String siteName) {
        String processedHtml = preprocessHtmlForTokenLimit(html);
        if (isHtmlSuitableForAI(processedHtml)) {
            return callAiExtractionForSingleBlock(processedHtml, siteName);
        } else {
            log.warn("HTML이 토큰 제한을 초과하여 청크로 분할 처리합니다. 크기: {}자", processedHtml.length());
            List<JobPosting> allJobs = new ArrayList<>();
            List<String> chunks = splitHtmlIntoChunks(processedHtml);
            for (String chunk : chunks) {
                if (!canMakeApiCall()) {
                    log.warn("API 호출 제한에 도달하여 청크 처리를 중단합니다.");
                    break;
                }
                allJobs.addAll(callAiExtractionForSingleBlock(chunk, siteName));
            }
            return allJobs;
        }
    }

    private JobPosting callAiDetailExtractionWithLimits(JobPosting job, String html) {
        if (!canMakeApiCall()) {
            log.warn("일일 API 호출 제한으로 상세 정보 추출 스킵: {}", job.getSourceUrl());
            return job;
        }
        String processedHtml = preprocessHtmlForTokenLimit(html);
        if (!isHtmlSuitableForAI(processedHtml)) {
            log.warn("상세 HTML이 토큰 제한에 적합하지 않음 - URL: {}, 크기: {}자. 추출을 건너뜁니다.", job.getSourceUrl(), processedHtml.length());
            return job;
        }
        try {
            aiCallSemaphore.acquire();
            try {
                Thread.sleep(API_CALL_DELAY);
                JobPosting result = aiExtractionService.extractJobDetailFromHtml(job, processedHtml);
                dailyApiCallCount.incrementAndGet();
                log.info("AI 상세 추출 성공 - 호출 횟수: {}/{}", dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return job;
            } catch (RuntimeException e) {
                if (e.getMessage() != null && (e.getMessage().contains("429") || e.getMessage().contains("token") || e.getMessage().contains("context_length"))) {
                    log.error("API 할당량 또는 토큰 제한 초과 오류 발생. 상세 정보 추출을 건너뜁니다: {}", job.getSourceUrl());
                    return job;
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

    private void fetchDetailsWithAI(String siteName, List<JobPosting> jobs) {
        int maxDetailJobs = Math.min(jobs.size(), Math.max(1, MAX_DAILY_API_CALLS - dailyApiCallCount.get() - 5));
        if (maxDetailJobs <= 0) {
            log.warn("API 호출 제한으로 상세 정보 수집 건너뜀");
            return;
        }
        List<JobPosting> limitedJobs = jobs.subList(0, Math.min(maxDetailJobs, jobs.size()));
        log.info("상세 정보 수집 대상: {}개", limitedJobs.size());
        List<Callable<Void>> tasks = new ArrayList<>();
        for (JobPosting job : limitedJobs) {
            if (job.getSourceUrl() == null || job.getSourceUrl().isBlank()) continue;
            tasks.add(() -> {
                try {
                    if (Boolean.TRUE.equals(jobPostingRepository.existsBySourceUrlAndIsActiveTrue(job.getSourceUrl()))) {
                        log.info("상세 스킵(기존 활성): {} - {}", job.getCompany(), job.getTitle());
                        return null;
                    }
                } catch (Exception e) {
                    log.warn("기존 공고 확인 실패, 상세 시도 진행: {}", job.getSourceUrl(), e);
                }
                try {
                    crawlJobDetailWithAI(job);
                } catch (Exception e) {
                    log.error("AI 기반 상세 크롤링 작업 실패: {} - {}", siteName, job.getSourceUrl(), e);
                }
                return null;
            });
        }
        if (tasks.isEmpty()) return;
        try {
            List<Future<Void>> futures = detailExecutor.invokeAll(tasks);
            for (Future<Void> f : futures) {
                try {
                    f.get(120, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.warn("AI 기반 상세 크롤링 태스크 예외", e);
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("AI 기반 상세 크롤링 병렬 처리 인터럽트", ie);
        }
    }

    @Async
    public CompletableFuture<List<JobPosting>> crawlAllSites() {
        return CompletableFuture.completedFuture(crawlAllSitesSync());
    }

    public List<JobPosting> crawlAllSitesSync() {
        log.info("전체 사이트 AI 크롤링 시작 - API 제한: {}", MAX_DAILY_API_CALLS);
        List<JobPosting> allJobs = new ArrayList<>();

        for (Map.Entry<String, String> site : SUPPORTED_SITES.entrySet()) {
            if (!canMakeApiCall()) {
                log.warn("일일 API 호출 제한으로 {}({}) 크롤링 중단", site.getValue(), site.getKey());
                break;
            }
            try {
                List<JobPosting> siteJobs = crawlSpecificSiteWithAI(site.getKey());
                allJobs.addAll(siteJobs);
                log.info("{} AI 크롤링 완료: {}개 (API 호출: {}/{})", site.getValue(), siteJobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
                Thread.sleep(10000);
            } catch (Exception e) {
                log.error("{} AI 크롤링 실패", site.getValue(), e);
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("전체 AI 크롤링 완료. 총 {}개 채용공고 수집 (API 호출: {}/{})", allJobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS);
        return allJobs;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveIndividualJob(JobPosting job) {
        if (!isValidJob(job)) {
            log.warn("필수 필드 누락된 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
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
                log.warn("임베딩 생성 실패, null로 설정: {} - {}", saved.getCompany(), saved.getTitle(), embeddingError);
                saved.setEmbedding(null);
            }
            log.info("새 채용공고 저장: {} - {}", saved.getCompany(), saved.getTitle());
        } else {
            log.info("중복 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
        }
    }

    // ... other methods are unchanged ...

    @Async
    public CompletableFuture<String> startCrawlingBySites(List<String> siteIds) {
        log.info("AI 기반 사이트별 크롤링 시작: {} (일일 API 제한: {})", siteIds, MAX_DAILY_API_CALLS);
        List<JobPosting> allJobs = new ArrayList<>();
        Map<String, Integer> siteResults = new HashMap<>();

        for (String siteId : siteIds) {
            if (!SUPPORTED_SITES.containsKey(siteId)) {
                log.warn("지원하지 않는 사이트: {}", siteId);
                continue;
            }
            if (!canMakeApiCall()) {
                log.warn("일일 API 호출 제한 도달로 {} 크롤링 중단", siteId);
                break;
            }

            String siteName = SUPPORTED_SITES.get(siteId);
            log.info("{}({}) AI 기반 크롤링 시작", siteName, siteId);
            try {
                List<JobPosting> siteJobs = crawlSpecificSiteWithAI(siteId);
                allJobs.addAll(siteJobs);
                siteResults.put(siteName, siteJobs.size());
                log.info("{}({}) AI 크롤링 완료: {}개 수집", siteName, siteId, siteJobs.size());
                Thread.sleep(10000); // 사이트 간 딜레이
            } catch (Exception e) {
                log.error("{}({}) AI 크롤링 실패", siteName, siteId, e);
                siteResults.put(siteName, 0);
            }
        }

        String resultMessage = String.format("AI 기반 사이트별 크롤링 완료 - 총 %d개 수집. API 호출: %d/%d. 결과: %s",
                allJobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS, siteResults);
        log.info(resultMessage);
        return CompletableFuture.completedFuture(resultMessage);
    }

    public CompletableFuture<String> startManualCrawling() {
        log.info("전체 사이트 크롤링 시작 (수동)");
        try {
            return crawlAllSites().thenApply(jobs ->
                    String.format("전체 크롤링 완료: %d개 채용공고 수집 (API 호출: %d/%d)",
                            jobs.size(), dailyApiCallCount.get(), MAX_DAILY_API_CALLS)
            );
        } catch (Exception e) {
            log.error("전체 크롤링 시작 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        }
    }

    private String floatArrayToVectorString(float[] array) {
        if (array == null) return null;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
                job.getCompany() != null && !job.getCompany().trim().isEmpty();
    }

    private void appendIfNotEmpty(StringBuilder sb, String text) {
        if (text != null && !text.trim().isEmpty()) {
            sb.append(text.trim()).append(" ");
        }
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

    public Map<String, String> getSupportedSites() {
        return new HashMap<>(SUPPORTED_SITES);
    }

    public Map<String, Object> getSiteStatus(String siteId) {
        String siteName = SUPPORTED_SITES.get(siteId);
        if (siteName == null) return Map.of("error", "지원하지 않는 사이트");
        long jobCount = jobPostingRepository.countBySourceSiteAndIsActiveTrue(siteName);
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        long recentJobs = jobPostingRepository.countBySourceSiteAndCreatedAtAfterAndIsActiveTrue(siteName, yesterday);
        return Map.of("siteId", siteId, "siteName", siteName, "totalJobs", jobCount, "recentJobs", recentJobs,
                "lastCrawled", getLastCrawledTime(siteName), "extractionMethod", "AI-based (Pure)",
                "dailyApiCallsUsed", dailyApiCallCount.get(), "dailyApiCallsLimit", MAX_DAILY_API_CALLS);
    }

    public List<Map<String, Object>> getAllSitesStatus() {
        return SUPPORTED_SITES.keySet().stream().map(this::getSiteStatus).toList();
    }

    public Map<String, Object> getSiteStatistics() {
        Map<String, Object> stats = new HashMap<>();
        for (Map.Entry<String, String> site : SUPPORTED_SITES.entrySet()) {
            String siteName = site.getValue();
            long totalJobs = jobPostingRepository.countBySourceSiteAndIsActiveTrue(siteName);
            LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
            long weeklyJobs = jobPostingRepository.countBySourceSiteAndCreatedAtAfterAndIsActiveTrue(siteName, weekAgo);
            stats.put(site.getKey(), Map.of("siteName", siteName, "totalJobs", totalJobs, "weeklyJobs", weeklyJobs,
                    "extractionMethod", "AI-based (Pure)"));
        }
        stats.put("apiUsage", Map.of("dailyCallsUsed", dailyApiCallCount.get(), "dailyCallsLimit", MAX_DAILY_API_CALLS,
                "remainingCalls", MAX_DAILY_API_CALLS - dailyApiCallCount.get(), "lastResetDate", lastResetDate.toString()));
        return stats;
    }

    private String getLastCrawledTime(String siteName) {
        return jobPostingRepository.findFirstBySourceSiteOrderByCreatedAtDesc(siteName)
                .map(JobPosting::getCreatedAt).map(LocalDateTime::toString).orElse("없음");
    }

    public void resetApiCallCounter() {
        dailyApiCallCount.set(0);
        lastResetDate = LocalDateTime.now().toLocalDate().atStartOfDay();
        log.info("API 호출 카운터가 수동으로 리셋되었습니다.");
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetDailyApiCounter() {
        checkAndResetDailyCounter();
        log.info("일일 API 호출 카운터 자동 리셋 완료");
    }

    @Scheduled(cron = "0 0 2 * * SUN")
    @Transactional
    public void cleanupOldJobs() {
        try {
            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(30);
            int deleted = jobPostingRepository.deleteByCreatedAtBefore(cutoffDate);
            log.info("30일 이전 채용공고 {}개 삭제", deleted);
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
            log.info("마감된 채용공고 {}개 비활성화", updated);
        } catch (Exception e) {
            log.error("마감된 채용공고 비활성화 실패", e);
        }
    }

    public Map<String, Object> analyzeTokenUsage(String html) {
        if (html == null) return Map.of("error", "HTML이 null입니다");
        int originalLength = html.length();
        int estimatedOriginalTokens = originalLength / ESTIMATED_CHARS_PER_TOKEN;
        String processed = preprocessHtmlForTokenLimit(html);
        int processedLength = processed.length();
        int estimatedProcessedTokens = processedLength / ESTIMATED_CHARS_PER_TOKEN;
        boolean suitable = isHtmlSuitableForAI(processed);
        return Map.of(
                "originalLength", originalLength, "processedLength", processedLength,
                "estimatedOriginalTokens", estimatedOriginalTokens, "estimatedProcessedTokens", estimatedProcessedTokens,
                "reductionRatio", (double) (originalLength - processedLength) / originalLength * 100,
                "suitableForAI", suitable, "maxAllowedTokens", SAFE_TOKEN_LIMIT,
                "maxAllowedHtmlSize", MAX_HTML_SIZE_FOR_AI
        );
    }

    public Map<String, Object> getTokenLimitSettings() {
        return Map.of(
                "maxInputTokens", SAFE_TOKEN_LIMIT, "maxHtmlSizeForAI", MAX_HTML_SIZE_FOR_AI,
                "estimatedCharsPerToken", ESTIMATED_CHARS_PER_TOKEN, "apiCallDelay", API_CALL_DELAY,
                "maxConcurrentCalls", MAX_CONCURRENT_AI_CALLS, "maxDailyApiCalls", MAX_DAILY_API_CALLS,
                "detailParallelism", DETAIL_PARALLELISM, "maxPagesPerSite", MAX_PAGES_PER_SITE,
                "minDelay", MIN_DELAY, "maxDelay", MAX_DELAY
        );
    }
}
