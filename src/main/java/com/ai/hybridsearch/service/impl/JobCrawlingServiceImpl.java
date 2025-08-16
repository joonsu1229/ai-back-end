package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.ai.hybridsearch.service.EmbeddingService;
import com.ai.hybridsearch.service.JobCrawlingService;
import com.ai.hybridsearch.service.impl.AiExtractionServiceImpl;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
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

@Service
@Slf4j
public class JobCrawlingServiceImpl implements JobCrawlingService {

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private AiExtractionServiceImpl aiExtractionServiceImpl;

    // 크롤링 가능한 사이트 목록
    private static final Map<String, String> SUPPORTED_SITES = Map.of(
            "saramin", "사람인",
            "jobkorea", "잡코리아",
            "wanted", "원티드",
            "programmers", "프로그래머스",
            "jumpit", "점프"
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
    private static final long MIN_DELAY = 1000;
    private static final long MAX_DELAY = 2000;
    private static final int MAX_RETRIES = 2;
    private static final int MAX_PAGES_PER_SITE = 3; // 페이지 수 제한

    // 상세 페이지 병렬 처리 개수
    private static final int DETAIL_PARALLELISM = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors()));

    // ThreadLocal로 각 스레드별 WebDriver 관리
    private final ThreadLocal<WebDriver> driverThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<WebDriverWait> waitThreadLocal = new ThreadLocal<>();

    // 상세 크롤링용 실행기
    private ExecutorService detailExecutor;

    @PostConstruct
    public void init() {
        try {
            detailExecutor = Executors.newFixedThreadPool(DETAIL_PARALLELISM);
            log.info("JobCrawlingService 초기화 완료 - AI 기반 추출 및 상세 병렬 스레드 {}개 준비", DETAIL_PARALLELISM);
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
        log.info("JobCrawlingService 정리 완료");
    }

    /**
     * 전체 사이트 크롤링 (기존 메소드)
     */
    public CompletableFuture<String> startManualCrawling() {
        log.info("전체 사이트 크롤링 시작");
        try {
            return crawlAllSites().thenApply(jobs ->
                    String.format("전체 크롤링 완료: %d개 채용공고 수집", jobs.size())
            );
        } catch (Exception e) {
            log.error("전체 크롤링 시작 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        }
    }

    /**
     * 특정 사이트들만 크롤링
     */
    @Async
    public CompletableFuture<String> startCrawlingBySites(List<String> siteIds) {
        try {
            log.info("AI 기반 사이트별 크롤링 시작: {}", siteIds);

            List<JobPosting> allJobs = new ArrayList<>();
            Map<String, Integer> siteResults = new HashMap<>();

            for (String siteId : siteIds) {
                if (!SUPPORTED_SITES.containsKey(siteId)) {
                    log.warn("지원하지 않는 사이트: {}", siteId);
                    continue;
                }

                String siteName = SUPPORTED_SITES.get(siteId);
                log.info("{}({}) AI 기반 크롤링 시작", siteName, siteId);

                try {
                    List<JobPosting> siteJobs = crawlSpecificSiteWithAI(siteId);
                    allJobs.addAll(siteJobs);
                    siteResults.put(siteName, siteJobs.size());

                    log.info("{}({}) AI 크롤링 완료: {}개 수집", siteName, siteId, siteJobs.size());

                    // 사이트 간 간격 (과부하 방지)
                    Thread.sleep(2000);

                } catch (Exception e) {
                    log.error("{}({}) AI 크롤링 실패", siteName, siteId, e);
                    siteResults.put(siteName, 0);
                }
            }

            String resultMessage = String.format(
                    "AI 기반 사이트별 크롤링 완료 - 총 %d개 수집. 사이트별 결과: %s",
                    allJobs.size(),
                    siteResults
            );

            log.info(resultMessage);
            return CompletableFuture.completedFuture(resultMessage);

        } catch (Exception e) {
            log.error("AI 기반 사이트별 크롤링 프로세스 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        } finally {
            closeDriver();
        }
    }

    /**
     * AI 기반 개별 사이트 크롤링
     */
    private List<JobPosting> crawlSpecificSiteWithAI(String siteId) {
        List<JobPosting> allJobs = new ArrayList<>();
        WebDriver driver = getDriver();

        try {
            String siteName = SUPPORTED_SITES.get(siteId);
            String urlPattern = SITE_URL_PATTERNS.get(siteId);

            if (urlPattern == null) {
                log.warn("URL 패턴이 없는 사이트: {}", siteId);
                return allJobs;
            }

            // 여러 페이지 크롤링
            for (int page = 1; page <= MAX_PAGES_PER_SITE; page++) {
                String url = String.format(urlPattern, page);

                log.info("{}({}) {}페이지 크롤링 시작: {}", siteName, siteId, page, url);

                if (!loadPage(driver, url, siteName)) {
                    log.warn("{}({}) {}페이지 로드 실패", siteName, siteId, page);
                    continue;
                }

                // 페이지 로딩 대기
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(MIN_DELAY, MAX_DELAY));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // 페이지 HTML 가져오기
                String pageHtml = driver.getPageSource();

                // AI를 사용하여 채용공고 목록 추출
                List<JobPosting> pageJobs = aiExtractionServiceImpl.extractJobsFromHtml(pageHtml, siteName);

                log.info("{}({}) {}페이지에서 AI로 {}개 채용공고 추출", siteName, siteId, page, pageJobs.size());

                if (pageJobs.isEmpty()) {
                    log.warn("{}({}) {}페이지에서 채용공고를 찾을 수 없음", siteName, siteId, page);
                    break; // 더 이상 채용공고가 없으면 중단
                }

                allJobs.addAll(pageJobs);
            }

            // 상세 정보 병렬 수집
            if (!allJobs.isEmpty()) {
                fetchDetailsWithAI(siteName, allJobs);
            }

        } catch (Exception e) {
            log.error("{}({}) AI 크롤링 실패", SUPPORTED_SITES.get(siteId), siteId, e);
        }

        return allJobs;
    }

    /**
     * AI 기반 상세 정보 병렬 수집
     */
    private void fetchDetailsWithAI(String siteName, List<JobPosting> jobs) {
        List<Callable<Void>> tasks = new ArrayList<>();

        for (JobPosting job : jobs) {
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
                    log.warn("AI 기반 상세 크롤링 작업 실패: {} - {}", siteName, job.getSourceUrl(), e);
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
                    f.get(60, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    log.warn("AI 기반 상세 크롤링 타임아웃");
                } catch (ExecutionException ee) {
                    log.warn("AI 기반 상세 크롤링 태스크 예외", ee.getCause());
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("AI 기반 상세 크롤링 병렬 처리 인터럽트", ie);
        }
    }

    /**
     * AI를 사용한 개별 채용공고 상세 정보 크롤링
     */
    private void crawlJobDetailWithAI(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null) return;

        String originalUrl = driver.getCurrentUrl();

        try {
            log.debug("AI 기반 상세 페이지 크롤링: {}", job.getSourceUrl());

            if (!loadPage(driver, job.getSourceUrl(), job.getSourceSite())) {
                log.warn("상세 페이지 로드 실패: {}", job.getSourceUrl());
                return;
            }

            // 페이지 로딩 완료 대기
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }

            String pageSource = driver.getPageSource();
            log.debug("페이지 소스 길이: {}, 제목: {}", pageSource.length(), driver.getTitle());

            if (isBlockedPage(pageSource, driver.getTitle())) {
                log.warn("봇 차단 페이지 감지: {}", job.getSourceUrl());
                return;
            }

            // AI를 사용하여 상세 정보 추출
            JobPosting updatedJob = aiExtractionServiceImpl.extractJobDetailFromHtml(job, pageSource);

            // 데이터베이스에 저장
            saveIndividualJob(updatedJob);

        } catch (Exception e) {
            log.warn("AI 기반 상세 정보 크롤링 실패: {}, error: {}", job.getSourceUrl(), e.getMessage());
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
     * 전체 사이트 크롤링 (AI 기반)
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
            // 모든 지원 사이트 크롤링
            for (Map.Entry<String, String> site : SUPPORTED_SITES.entrySet()) {
                try {
                    List<JobPosting> siteJobs = crawlSpecificSiteWithAI(site.getKey());
                    allJobs.addAll(siteJobs);
                    log.info("{} AI 크롤링 완료: {}개", site.getValue(), siteJobs.size());

                    // 사이트 간 간격
                    Thread.sleep(3000);

                } catch (Exception e) {
                    log.error("{} AI 크롤링 실패", site.getValue(), e);
                }
            }

            log.info("전체 AI 크롤링 완료. 총 {}개 채용공고 수집", allJobs.size());

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
                log.warn("WebDriver 종료 중 오류", e);
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
                driverPath = "drivers/chromedriver-linux-win.exe";
            } else if (os.contains("linux")) {
                driverPath = "drivers/chromedriver-linux-linux";
            } else {
                throw new RuntimeException("지원하지 않는 OS: " + os);
            }

            System.setProperty("webdriver.chrome.driver", driverPath);

            // ChromeOptions 설정
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

            // 성능 최적화를 위한 설정
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.managed_default_content_settings.images", 2); // 이미지 비활성화
            prefs.put("profile.managed_default_content_settings.stylesheets", 1); // CSS 활성화 (구조 유지)
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

            log.info("WebDriver 생성 성공");
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

                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(8));
                wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                        .executeScript("return document.readyState").equals("complete"));

                String pageSource = driver.getPageSource().toLowerCase();
                if (isBlockedPage(pageSource, driver.getTitle())) {
                    log.warn("{} 봇 차단 페이지 감지: {}", siteName, url);
                    continue;
                }

                log.debug("{} 페이지 로드 성공: {}", siteName, url);
                return true;

            } catch (Exception e) {
                log.warn("{} 페이지 로드 실패 (시도 {}/{}): {} - {}",
                        siteName, retry + 1, MAX_RETRIES, url, e.getMessage());

                if (retry < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(1000 * (retry + 1)); // 재시도 간격 증가
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

    private boolean isBlockedPage(String pageSource, String title) {
        String lowerPageSource = pageSource.toLowerCase();
        String lowerTitle = title.toLowerCase();

        return lowerPageSource.contains("robot") ||
                lowerPageSource.contains("captcha") ||
                lowerPageSource.contains("차단") ||
                lowerPageSource.contains("접근이 제한") ||
                lowerTitle.contains("error") ||
                lowerTitle.contains("blocked");
    }

    // ===== 데이터베이스 저장 관련 메서드들 =====

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
                "extractionMethod", "AI-based"
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
                    "extractionMethod", "AI-based"
            ));
        }

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

    // ===== 스케줄링 작업들 =====

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

    // ===== 테스트/디버깅 메서드들 =====

    public void testAiExtraction(String url, String siteName) {
        log.info("AI 추출 테스트 시작: {}", url);

        WebDriver driver = getDriver();
        try {
            if (loadPage(driver, url, siteName)) {
                log.info("페이지 로드 성공");

                String html = driver.getPageSource();
                List<JobPosting> jobs = aiExtractionServiceImpl.extractJobsFromHtml(html, siteName);

                log.info("AI 추출 결과: {}개 채용공고", jobs.size());

                for (int i = 0; i < Math.min(3, jobs.size()); i++) {
                    JobPosting job = jobs.get(i);
                    log.info("채용공고 {}: {} - {} ({})", i+1, job.getCompany(), job.getTitle(), job.getSourceUrl());
                }
            } else {
                log.error("페이지 로드 실패");
            }
        } finally {
            closeDriver();
        }
    }
}