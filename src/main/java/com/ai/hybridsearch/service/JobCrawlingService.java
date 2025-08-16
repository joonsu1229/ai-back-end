// JobCrawlingService.java - 기존 로직 기반 사이트별 크롤링 기능 추가
package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.pgvector.PGvector;
import io.github.bonigarcia.wdm.WebDriverManager;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.PageLoadStrategy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.openqa.selenium.support.ui.ExpectedConditions;
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
public class JobCrawlingService {

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private EmbeddingService embeddingService;

    // 크롤링 가능한 사이트 목록
    private static final Map<String, String> SUPPORTED_SITES = Map.of(
            "saramin", "사람인",
            "jobkorea", "잡코리아",
            "wanted", "원티드"
//            "programmers", "프로그래머스",
//            "jumpit", "점핏"
    );

    // 대기/지연 설정 최적화
    private static final int IMPLICIT_WAIT_SECONDS = 3;
    private static final int EXPLICIT_WAIT_SECONDS = 10;
    private static final int PAGE_LOAD_TIMEOUT_SECONDS = 15;
    private static final long MIN_DELAY = 120;
    private static final long MAX_DELAY = 250;
    private static final int MAX_RETRIES = 1;

    // 상세 페이지 병렬 처리 개수
    private static final int DETAIL_PARALLELISM =
            Math.max(3, Math.min(8, Runtime.getRuntime().availableProcessors()));

    // ThreadLocal로 각 스레드별 WebDriver 관리
    private final ThreadLocal<WebDriver> driverThreadLocal = new ThreadLocal<>();
    private final ThreadLocal<WebDriverWait> waitThreadLocal = new ThreadLocal<>();

    // 상세 크롤링용 실행기
    private ExecutorService detailExecutor;

    @PostConstruct
    public void init() {
        try {
            detailExecutor = Executors.newFixedThreadPool(DETAIL_PARALLELISM);
            log.info("JobCrawlingService 초기화 완료 - ChromeDriver 설정 및 상세 병렬 스레드 {}개 준비", DETAIL_PARALLELISM);
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

    // ===== 새로 추가된 사이트별 크롤링 메서드들 =====

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
            log.info("사이트별 크롤링 시작: {}", siteIds);

            List<JobPosting> allJobs = new ArrayList<>();
            Map<String, Integer> siteResults = new HashMap<>();

            for (String siteId : siteIds) {
                if (!SUPPORTED_SITES.containsKey(siteId)) {
                    log.warn("지원하지 않는 사이트: {}", siteId);
                    continue;
                }

                String siteName = SUPPORTED_SITES.get(siteId);
                log.info("{}({}) 크롤링 시작", siteName, siteId);

                try {
                    List<JobPosting> siteJobs = crawlSpecificSite(siteId);
                    allJobs.addAll(siteJobs);
                    siteResults.put(siteName, siteJobs.size());

                    log.info("{}({}) 크롤링 완료: {}개 수집", siteName, siteId, siteJobs.size());

                    // 사이트 간 간격 (과부하 방지)
                    Thread.sleep(2000);

                } catch (Exception e) {
                    log.error("{}({}) 크롤링 실패", siteName, siteId, e);
                    siteResults.put(siteName, 0);
                }
            }

            String resultMessage = String.format(
                    "사이트별 크롤링 완료 - 총 %d개 수집. 사이트별 결과: %s",
                    allJobs.size(),
                    siteResults
            );

            log.info(resultMessage);
            return CompletableFuture.completedFuture(resultMessage);

        } catch (Exception e) {
            log.error("사이트별 크롤링 프로세스 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        } finally {
            closeDriver();
        }
    }

    /**
     * 개별 사이트 크롤링 실행
     */
    private List<JobPosting> crawlSpecificSite(String siteId) {
        switch (siteId) {
            case "saramin":
                return crawlSaramin();
            case "jobkorea":
                return crawlJobkorea();
            case "wanted":
                return crawlWanted();
            case "programmers":
                return crawlProgrammers();
            case "jumpit":
                return crawlJumpit();
            default:
                log.warn("알 수 없는 사이트 ID: {}", siteId);
                return new ArrayList<>();
        }
    }

    /**
     * 원티드 크롤링 (새로 추가)
     */
    private List<JobPosting> crawlWanted() {
        List<JobPosting> jobs = new ArrayList<>();
        WebDriver driver = getDriver();
        WebDriverWait wait = getWait();

        try {
            for (int page = 1; page <= 2; page++) {
                String url = String.format(
                        "https://www.wanted.co.kr/search?query=개발자&tab=position&page=%d",
                        page
                );

                if (!loadPage(driver, url, "원티드")) {
                    continue;
                }

                try {
                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(".JobCard, .job-card, .Card, .position-card")));
                } catch (Exception e) {
                    log.warn("원티드 페이지 {} 로딩 대기 실패", page);
                    continue;
                }

                List<WebElement> jobElements = findJobElements(driver, "원티드");
                log.info("원티드 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                for (WebElement jobElement : jobElements) {
                    try {
                        JobPosting job = parseWantedJob(jobElement);
                        if (job != null) {
                            jobs.add(job);
                        }
                    } catch (Exception e) {
                        log.warn("원티드 개별 채용공고 처리 실패", e);
                    }
                }
            }

            if (!jobs.isEmpty()) {
                fetchDetailsInParallel("원티드", jobs);
            }

        } catch (Exception e) {
            log.error("원티드 크롤링 실패", e);
        }

        return jobs;
    }

    /**
     * 프로그래머스 크롤링 (새로 추가)
     */
    private List<JobPosting> crawlProgrammers() {
        List<JobPosting> jobs = new ArrayList<>();
        WebDriver driver = getDriver();
        WebDriverWait wait = getWait();

        try {
            for (int page = 1; page <= 2; page++) {
                String url = String.format(
                        "https://career.programmers.co.kr/job?page=%d",
                        page
                );

                if (!loadPage(driver, url, "프로그래머스")) {
                    continue;
                }

                try {
                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(".job-item, .list-item, .card")));
                } catch (Exception e) {
                    log.warn("프로그래머스 페이지 {} 로딩 대기 실패", page);
                    continue;
                }

                List<WebElement> jobElements = findJobElements(driver, "프로그래머스");
                log.info("프로그래머스 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                for (WebElement jobElement : jobElements) {
                    try {
                        JobPosting job = parseProgrammersJob(jobElement);
                        if (job != null) {
                            jobs.add(job);
                        }
                    } catch (Exception e) {
                        log.warn("프로그래머스 개별 채용공고 처리 실패", e);
                    }
                }
            }

            if (!jobs.isEmpty()) {
                fetchDetailsInParallel("프로그래머스", jobs);
            }

        } catch (Exception e) {
            log.error("프로그래머스 크롤링 실패", e);
        }

        return jobs;
    }

    /**
     * 점핏 크롤링 (새로 추가)
     */
    private List<JobPosting> crawlJumpit() {
        List<JobPosting> jobs = new ArrayList<>();
        WebDriver driver = getDriver();
        WebDriverWait wait = getWait();

        try {
            for (int page = 1; page <= 2; page++) {
                String url = String.format(
                        "https://www.jumpit.co.kr/positions?page=%d",
                        page
                );

                if (!loadPage(driver, url, "점핏")) {
                    continue;
                }

                try {
                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(".position-item, .job-card, .card")));
                } catch (Exception e) {
                    log.warn("점핏 페이지 {} 로딩 대기 실패", page);
                    continue;
                }

                List<WebElement> jobElements = findJobElements(driver, "점핏");
                log.info("점핏 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                for (WebElement jobElement : jobElements) {
                    try {
                        JobPosting job = parseJumpitJob(jobElement);
                        if (job != null) {
                            jobs.add(job);
                        }
                    } catch (Exception e) {
                        log.warn("점핏 개별 채용공고 처리 실패", e);
                    }
                }
            }

            if (!jobs.isEmpty()) {
                fetchDetailsInParallel("점핏", jobs);
            }

        } catch (Exception e) {
            log.error("점핏 크롤링 실패", e);
        }

        return jobs;
    }

    // ===== 새로운 사이트 파싱 메서드들 =====

    private JobPosting parseWantedJob(WebElement jobElement) {
        try {
            JobPosting job = new JobPosting();

            WebElement titleElement = findElementBySelectors(jobElement,
                    ".JobCard_title", ".job-card-title", ".title", "h3 a", "h4 a");

            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.getText()));
                String href = titleElement.getAttribute("href");
                if (href != null && !href.startsWith("http")) {
                    href = "https://www.wanted.co.kr" + href;
                }
                job.setSourceUrl(href);
            }

            WebElement companyElement = findElementBySelectors(jobElement,
                    ".JobCard_companyName", ".company-name", ".company", ".corp-name");

            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.getText()));
            }

            setBasicJobInfo(jobElement, job);

            job.setSourceSite("원티드");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("원티드 개별 채용공고 파싱 실패", e);
            return null;
        }
    }

    private JobPosting parseProgrammersJob(WebElement jobElement) {
        try {
            JobPosting job = new JobPosting();

            WebElement titleElement = findElementBySelectors(jobElement,
                    ".job-title a", ".title a", "h3 a", "h4 a");

            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.getText()));
                String href = titleElement.getAttribute("href");
                if (href != null && !href.startsWith("http")) {
                    href = "https://career.programmers.co.kr" + href;
                }
                job.setSourceUrl(href);
            }

            WebElement companyElement = findElementBySelectors(jobElement,
                    ".company-name", ".company", ".corp-name");

            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.getText()));
            }

            setBasicJobInfo(jobElement, job);

            job.setSourceSite("프로그래머스");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("프로그래머스 개별 채용공고 파싱 실패", e);
            return null;
        }
    }

    private JobPosting parseJumpitJob(WebElement jobElement) {
        try {
            JobPosting job = new JobPosting();

            WebElement titleElement = findElementBySelectors(jobElement,
                    ".position-title a", ".job-title a", ".title a", "h3 a", "h4 a");

            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.getText()));
                String href = titleElement.getAttribute("href");
                if (href != null && !href.startsWith("http")) {
                    href = "https://www.jumpit.co.kr" + href;
                }
                job.setSourceUrl(href);
            }

            WebElement companyElement = findElementBySelectors(jobElement,
                    ".company-name", ".company", ".corp-name");

            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.getText()));
            }

            setBasicJobInfo(jobElement, job);

            job.setSourceSite("점핏");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("점핏 개별 채용공고 파싱 실패", e);
            return null;
        }
    }

    // ===== 상태 관리 및 정보 제공 메서드들 =====

    /**
     * 지원 가능한 크롤링 사이트 목록 반환
     */
    public Map<String, String> getSupportedSites() {
        return new HashMap<>(SUPPORTED_SITES);
    }

    /**
     * 특정 사이트의 크롤링 상태 확인
     */
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
                "lastCrawled", getLastCrawledTime(siteName)
        );
    }

    /**
     * 모든 사이트의 상태 정보
     */
    public List<Map<String, Object>> getAllSitesStatus() {
        return SUPPORTED_SITES.entrySet().stream()
                .map(entry -> getSiteStatus(entry.getKey()))
                .toList();
    }

    /**
     * 사이트별 통계 정보
     */
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
                    "weeklyJobs", weeklyJobs
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

    // ===== 기존 메서드들 (변경 없음) =====

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
            // 1. OS 감지
            String os = System.getProperty("os.name").toLowerCase();
            String driverPath;

            if (os.contains("win")) {
                driverPath = "drivers/chromedriver-linux-win.exe"; // Windows용
            } else if (os.contains("linux")) {
                driverPath = "drivers/chromedriver-linux-linux";   // Linux(Ubuntu)용
            } else {
                throw new RuntimeException("지원하지 않는 OS: " + os);
            }

            System.setProperty("webdriver.chrome.driver", driverPath);

            // 2. ChromeOptions 설정
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

            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.managed_default_content_settings.images", 2);
            prefs.put("profile.managed_default_content_settings.stylesheets", 2);
            prefs.put("profile.managed_default_content_settings.cookies", 1);
            prefs.put("profile.managed_default_content_settings.javascript", 1);
            prefs.put("profile.managed_default_content_settings.plugins", 2);
            prefs.put("profile.managed_default_content_settings.popups", 2);
            prefs.put("profile.managed_default_content_settings.geolocation", 2);
            options.setExperimentalOption("prefs", prefs);

            // 3. User-Agent 랜덤 설정
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

            // 4. ChromeDriver 생성
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

    @Async
    public CompletableFuture<List<JobPosting>> crawlAllSites() {
        List<JobPosting> allJobs = crawlAllSitesSync();
        closeDriver();
        return CompletableFuture.completedFuture(allJobs);
    }

    public List<JobPosting> crawlAllSitesSync() {
        List<JobPosting> allJobs = new ArrayList<>();

        try {
            List<JobPosting> saraminJobs = crawlSaramin();
            allJobs.addAll(saraminJobs);
            log.info("사람인 크롤링 완료: {}개", saraminJobs.size());

            List<JobPosting> jobkoreaJobs = crawlJobkorea();
            allJobs.addAll(jobkoreaJobs);
            log.info("잡코리아 크롤링 완료: {}개", jobkoreaJobs.size());

            log.info("전체 크롤링 완료. 총 {}개 채용공고 수집", allJobs.size());

        } catch (Exception e) {
            log.error("크롤링 중 오류 발생", e);
        }

        return allJobs;
    }

    private List<JobPosting> crawlSaramin() {
        List<JobPosting> jobs = new ArrayList<>();
        WebDriver driver = getDriver();
        WebDriverWait wait = getWait();

        try {
            for (int page = 1; page <= 1; page++) {
                String url = String.format(
                        "https://www.saramin.co.kr/zf_user/search/recruit?searchType=search&searchword=개발자&recruitPage=%d",
                        page
                );

                if (!loadPage(driver, url, "사람인")) {
                    continue;
                }

                try {
                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(".item_recruit, .list_item, .recruit_item, .job_item")));
                } catch (Exception e) {
                    log.warn("사람인 페이지 {} 로딩 대기 실패", page);
                    continue;
                }

                List<WebElement> jobElements = findJobElements(driver, "사람인");
                log.info("사람인 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                for (WebElement jobElement : jobElements) {
                    try {
                        JobPosting job = parseSaraminJob(jobElement);
                        if (job != null) {
                            jobs.add(job);
                        }
                    } catch (Exception e) {
                        log.warn("사람인 개별 채용공고 처리 실패", e);
                    }
                }
            }

            if (!jobs.isEmpty()) {
                fetchDetailsInParallel("사람인", jobs);
            }

        } catch (Exception e) {
            log.error("사람인 크롤링 실패", e);
        }

        return jobs;
    }

    private List<JobPosting> crawlJobkorea() {
        List<JobPosting> jobs = new ArrayList<>();
        WebDriver driver = getDriver();
        WebDriverWait wait = getWait();

        try {
            for (int page = 1; page <= 3; page++) {
                String url = String.format(
                        "https://www.jobkorea.co.kr/Search/?stext=개발자&Page_No=%d",
                        page
                );

                if (!loadPage(driver, url, "잡코리아")) {
                    continue;
                }

                try {
                    wait.until(ExpectedConditions.presenceOfElementLocated(
                            By.cssSelector(".list-default .list-post, .recruit-info, .list-item")));
                } catch (Exception e) {
                    log.warn("잡코리아 페이지 {} 로딩 대기 실패", page);
                    continue;
                }

                List<WebElement> jobElements = findJobElements(driver, "잡코리아");
                log.info("잡코리아 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                for (WebElement jobElement : jobElements) {
                    try {
                        JobPosting job = parseJobkoreaJob(jobElement);
                        if (job != null) {
                            jobs.add(job);
                        }
                    } catch (Exception e) {
                        log.warn("잡코리아 개별 채용공고 처리 실패", e);
                    }
                }
            }

            if (!jobs.isEmpty()) {
                fetchDetailsInParallel("잡코리아", jobs);
            }

        } catch (Exception e) {
            log.error("잡코리아 크롤링 실패", e);
        }

        return jobs;
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
                if (pageSource.contains("robot") || pageSource.contains("captcha") ||
                        pageSource.contains("차단") || pageSource.contains("접근이 제한")) {
                    log.warn("{} 봇 차단 페이지 감지: {}", siteName, url);
                    continue;
                }

                log.debug("{} 페이지 로드 성공: {}", siteName, url);
                return true;

            } catch (Exception e) {
                log.warn("{} 페이지 로드 실패 (시도 {}/{}): {} - {}",
                        siteName, retry + 1, MAX_RETRIES, url, e.getMessage());

                if (retry < MAX_RETRIES - 1) {
                    // 재시도 전 대기
                }
            }
        }

        log.error("{} 페이지 로드 모든 시도 실패: {}", siteName, url);
        return false;
    }

    private List<WebElement> findJobElements(WebDriver driver, String siteName) {
        String[][] selectors = {
                // 사람인 셀렉터들
                {".item_recruit", ".list_item", ".recruit_item", ".job_item",
                        "[data-result]", ".contents .item"},
                // 잡코리아 셀렉터들
                {".list-default .list-post", ".recruit-info", ".list-item",
                        ".recruit-item", "[data-gno]", ".post-list-item"},
                // 원티드 셀렉터들
                {".JobCard", ".job-card", ".Card", ".position-card"},
                // 프로그래머스 셀렉터들
                {".job-item", ".list-item", ".card"},
                // 점핏 셀렉터들
                {".position-item", ".job-card", ".card"}
        };

        int siteIndex = switch (siteName) {
            case "사람인" -> 0;
            case "잡코리아" -> 1;
            case "원티드" -> 2;
            case "프로그래머스" -> 3;
            case "점핏" -> 4;
            default -> 0;
        };

        String[] siteSelectors = selectors[Math.min(siteIndex, selectors.length - 1)];

        for (String selector : siteSelectors) {
            try {
                List<WebElement> elements = driver.findElements(By.cssSelector(selector));
                if (!elements.isEmpty()) {
                    log.debug("{} - 셀렉터 '{}' 로 {}개 요소 발견", siteName, selector, elements.size());
                    return elements;
                }
            } catch (Exception e) {
                log.debug("{} - 셀렉터 '{}' 검색 실패: {}", siteName, selector, e.getMessage());
            }
        }

        log.warn("{} - 채용공고 요소를 찾을 수 없음", siteName);
        return new ArrayList<>();
    }

    private JobPosting parseSaraminJob(WebElement jobElement) {
        try {
            JobPosting job = new JobPosting();

            WebElement titleElement = findElementBySelectors(jobElement,
                    ".job_tit a", ".area_job .job_tit a", ".recruit_info .job_tit a",
                    "a[title]", ".title a", "h2 a", "h3 a");

            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.getText()));
                String href = titleElement.getAttribute("href");
                if (href != null && href.startsWith("/")) {
                    href = "https://www.saramin.co.kr" + href;
                }
                job.setSourceUrl(href);
            }

            WebElement companyElement = findElementBySelectors(jobElement,
                    ".corp_name a", ".area_corp .corp_name a", ".company_nm a",
                    ".corp_nm a", ".company a", ".corp a");

            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.getText()));
            }

            setBasicJobInfo(jobElement, job);

            job.setSourceSite("사람인");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("사람인 개별 채용공고 파싱 실패", e);
            return null;
        }
    }

    private JobPosting parseJobkoreaJob(WebElement jobElement) {
        try {
            JobPosting job = new JobPosting();

            WebElement titleElement = findElementBySelectors(jobElement,
                    ".post-list-info .title a", "a[href*='/Recruit/']",
                    ".recruit-title a", ".title a", ".job-title a",
                    ".list-post-title a", "h3 a", "h4 a");

            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.getText()));
                String href = titleElement.getAttribute("href");
                if (href != null && !href.startsWith("http")) {
                    href = "https://www.jobkorea.co.kr" + href;
                }
                job.setSourceUrl(href);
            }

            WebElement companyElement = findElementBySelectors(jobElement,
                    ".post-list-corp .name a", ".company-name a",
                    ".corp-name a", ".company a", ".corp a",
                    ".list-post-corp a", ".recruit-corp a");

            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.getText()));
            }

            setBasicJobInfo(jobElement, job);

            job.setSourceSite("잡코리아");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("잡코리아 개별 채용공고 파싱 실패", e);
            return null;
        }
    }

    private void crawlSaraminDetails(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null) return;

        String originalUrl = driver.getCurrentUrl();

        try {
            log.debug("사람인 상세 페이지 크롤링: {}", job.getSourceUrl());

            if (!loadPage(driver, job.getSourceUrl(), "사람인")) {
                log.warn("사람인 상세 페이지 로드 실패: {}", job.getSourceUrl());
                return;
            }

            WebDriverWait wait = getWait();
            try {
                wait.until(ExpectedConditions.or(
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector(".user_content")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector(".content")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector(".job_description")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector(".recruit_content")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("main")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector(".container")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='content']")),
                        ExpectedConditions.presenceOfElementLocated(By.cssSelector("[class*='job']"))
                ));
                log.debug("사람인 상세 페이지 로딩 완료: {}", job.getSourceUrl());
            } catch (Exception e) {
                log.warn("사람인 상세 페이지 로딩 대기 실패: {}, message:{}", job.getSourceUrl(), e.getMessage());
            }

            String pageSource = driver.getPageSource();
            log.debug("페이지 소스 길이: {}, 제목: {}", pageSource.length(), driver.getTitle());

            if (pageSource.toLowerCase().contains("robot") ||
                    pageSource.toLowerCase().contains("captcha") ||
                    pageSource.toLowerCase().contains("차단") ||
                    pageSource.toLowerCase().contains("접근") ||
                    driver.getTitle().toLowerCase().contains("error")) {
                log.warn("사람인 봇 차단 페이지 감지: {}", job.getSourceUrl());
                return;
            }

            WebElement descElement = findElementBySelectors(driver,
                    ".user_content", ".content", ".job_description", ".recruit_content",
                    ".view_content", ".job_info .content", "#recrut_content",
                    ".summary .content", ".cont", ".detail_content", ".job_detail",
                    ".description", "main .content", ".container .content",
                    "[class*='content']", "[class*='description']", "[class*='detail']",
                    "article", "section", ".wrap_jview", ".section");

            if (descElement != null) {
                String description = cleanText(descElement.getText());
                if (description != null && description.length() > 20) {
                    job.setDescription(description);
                    log.debug("사람인 설명 추출 성공: {} characters", description.length());
                } else {
                    log.debug("사람인 설명이 너무 짧거나 비어있음: {}", description);
                }
            } else {
                log.debug("사람인 설명 요소를 찾을 수 없음: {}", job.getSourceUrl());

                if (log.isDebugEnabled()) {
                    debugPageStructure(driver, job.getSourceUrl());
                }
            }

            extractAdditionalJobInfo(driver, job);
            saveIndividualJob(job);

        } catch (Exception e) {
            log.warn("사람인 상세 정보 크롤링 실패: {}, error: {}", job.getSourceUrl(), e.getMessage());
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

    private void crawlJobkoreaDetails(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null) return;

        String originalUrl = driver.getCurrentUrl();

        try {
            if (!loadPage(driver, job.getSourceUrl(), "잡코리아")) {
                return;
            }

            WebDriverWait wait = getWait();
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector(".section-content, .content, .job-description")));
            } catch (Exception e) {
                log.warn("잡코리아 상세 페이지 로딩 대기 실패: {}", job.getSourceUrl());
            }

            WebElement descElement = findElementBySelectors(driver,
                    ".section-content", ".content", ".job-description",
                    ".recruit-content", ".view-content", ".detail-content",
                    ".job-detail", ".description");

            if (descElement != null) {
                job.setDescription(cleanText(descElement.getText()));
            }

            extractAdditionalJobInfo(driver, job);
            saveIndividualJob(job);

        } catch (Exception e) {
            log.warn("잡코리아 상세 정보 크롤링 실패: {}", job.getSourceUrl(), e);
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

    // 새로운 사이트들의 상세 크롤링 메서드들
    private void crawlWantedDetails(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null) return;

        try {
            if (!loadPage(driver, job.getSourceUrl(), "원티드")) {
                return;
            }

            WebDriverWait wait = getWait();
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector(".JobDescription, .job-description, .description")));
            } catch (Exception e) {
                log.warn("원티드 상세 페이지 로딩 대기 실패: {}", job.getSourceUrl());
            }

            WebElement descElement = findElementBySelectors(driver,
                    ".JobDescription", ".job-description", ".description",
                    ".content", ".detail-content");

            if (descElement != null) {
                job.setDescription(cleanText(descElement.getText()));
            }

            extractAdditionalJobInfo(driver, job);
            saveIndividualJob(job);

        } catch (Exception e) {
            log.warn("원티드 상세 정보 크롤링 실패: {}", job.getSourceUrl(), e);
        }
    }

    private void crawlProgrammersDetails(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null) return;

        try {
            if (!loadPage(driver, job.getSourceUrl(), "프로그래머스")) {
                return;
            }

            WebDriverWait wait = getWait();
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector(".job-content, .content, .description")));
            } catch (Exception e) {
                log.warn("프로그래머스 상세 페이지 로딩 대기 실패: {}", job.getSourceUrl());
            }

            WebElement descElement = findElementBySelectors(driver,
                    ".job-content", ".content", ".description",
                    ".detail-content", ".job-description");

            if (descElement != null) {
                job.setDescription(cleanText(descElement.getText()));
            }

            extractAdditionalJobInfo(driver, job);
            saveIndividualJob(job);

        } catch (Exception e) {
            log.warn("프로그래머스 상세 정보 크롤링 실패: {}", job.getSourceUrl(), e);
        }
    }

    private void crawlJumpitDetails(JobPosting job, WebDriver driver) {
        if (job.getSourceUrl() == null) return;

        try {
            if (!loadPage(driver, job.getSourceUrl(), "점핏")) {
                return;
            }

            WebDriverWait wait = getWait();
            try {
                wait.until(ExpectedConditions.presenceOfElementLocated(
                        By.cssSelector(".position-description, .content, .description")));
            } catch (Exception e) {
                log.warn("점핏 상세 페이지 로딩 대기 실패: {}", job.getSourceUrl());
            }

            WebElement descElement = findElementBySelectors(driver,
                    ".position-description", ".content", ".description",
                    ".detail-content", ".job-description");

            if (descElement != null) {
                job.setDescription(cleanText(descElement.getText()));
            }

            extractAdditionalJobInfo(driver, job);
            saveIndividualJob(job);

        } catch (Exception e) {
            log.warn("점핏 상세 정보 크롤링 실패: {}", job.getSourceUrl(), e);
        }
    }

    private WebElement findElementBySelectors(WebElement parent, String... selectors) {
        for (String selector : selectors) {
            try {
                WebElement element = parent.findElement(By.cssSelector(selector));
                if (element != null) {
                    return element;
                }
            } catch (Exception e) {
                // 계속 다음 셀렉터 시도
            }
        }
        return null;
    }

    private WebElement findElementBySelectors(WebDriver driver, String... selectors) {
        for (String selector : selectors) {
            try {
                WebElement element = driver.findElement(By.cssSelector(selector));
                if (element != null) {
                    return element;
                }
            } catch (Exception e) {
                // 계속 다음 셀렉터 시도
            }
        }
        return null;
    }

    private void setBasicJobInfo(WebElement jobElement, JobPosting job) {
        try {
            WebElement locationElement = findElementBySelectors(jobElement,
                    ".job_condition span", ".area_job .condition span",
                    ".job_meta span", ".location", ".work_place",
                    ".post-list-info .option .location", ".area", ".region");

            if (locationElement != null) {
                job.setLocation(cleanText(locationElement.getText()));
            }

            List<WebElement> conditionSpans = jobElement.findElements(
                    By.cssSelector(".job_condition span, .condition span"));

            if (conditionSpans.size() > 1) {
                job.setExperienceLevel(cleanText(conditionSpans.get(1).getText()));
            }
            if (conditionSpans.size() > 2) {
                job.setEmploymentType(cleanText(conditionSpans.get(2).getText()));
            }

        } catch (Exception e) {
            log.debug("기본 정보 설정 실패", e);
        }
    }

    private void extractAdditionalJobInfo(WebDriver driver, JobPosting job) {
        try {
            WebElement reqElement = findElementBySelectors(driver,
                    ".qualification", ".requirements", ".job_requirement",
                    ".recruit_requirement", ".requirement");

            if (reqElement != null) {
                job.setRequirements(cleanText(reqElement.getText()));
            }

            WebElement benefitElement = findElementBySelectors(driver,
                    ".welfare", ".benefits", ".benefit_info", ".benefit");

            if (benefitElement != null) {
                job.setBenefits(cleanText(benefitElement.getText()));
            }

            WebElement salaryElement = findElementBySelectors(driver,
                    ".salary", ".salary_info", ".pay_info", ".money", ".pay");

            if (salaryElement != null) {
                job.setSalary(cleanText(salaryElement.getText()));
            }

        } catch (Exception e) {
            log.debug("추가 정보 추출 실패", e);
        }
    }

    private String cleanText(String text) {
        if (text == null) return null;
        return text.replaceAll("\\s+", " ").trim();
    }

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
                job.getCompany() != null && !job.getCompany().trim().isEmpty();
    }

    private void debugPageStructure(WebDriver driver, String url) {
        try {
            log.info("=== 페이지 구조 분석: {} ===", url);
            log.info("페이지 제목: {}", driver.getTitle());
            log.info("현재 URL: {}", driver.getCurrentUrl());

            String[] containerSelectors = {
                    "main", "article", "section", ".container", ".content", ".wrap",
                    "[class*='content']", "[class*='job']", "[class*='detail']",
                    "body > div", ".inner", ".section"
            };

            for (String selector : containerSelectors) {
                List<WebElement> elements = driver.findElements(By.cssSelector(selector));
                if (!elements.isEmpty()) {
                    log.info("발견: {} - {}개", selector, elements.size());
                    if (elements.size() <= 3) {
                        for (int i = 0; i < elements.size(); i++) {
                            WebElement elem = elements.get(i);
                            String text = elem.getText();
                            if (text.length() > 100) {
                                text = text.substring(0, 100) + "...";
                            }
                            log.info("  [{}] {}", i, text.replaceAll("\\s+", " "));
                        }
                    }
                }
            }

            String pageSource = driver.getPageSource();
            if (pageSource.length() > 500) {
                log.info("페이지 소스 (처음 500자): {}", pageSource.substring(0, 500));
            }

        } catch (Exception e) {
            log.warn("페이지 구조 분석 실패", e);
        }
    }

    public void testSpecificUrl(String url, String siteName) {
        log.info("URL 테스트 시작: {}", url);

        WebDriver driver = getDriver();
        try {
            if (loadPage(driver, url, siteName)) {
                log.info("페이지 로드 성공");

                debugPageStructure(driver, url);

                List<WebElement> importantElements = driver.findElements(
                        By.cssSelector("h1, h2, h3, .title, .job_tit"));

                for (int i = 0; i < Math.min(5, importantElements.size()); i++) {
                    WebElement elem = importantElements.get(i);
                    log.info("발견된 요소: {} - {}", elem.getTagName(), elem.getText());
                }
            } else {
                log.error("페이지 로드 실패");
            }
        } finally {
            closeDriver();
        }
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

    private void fetchDetailsInParallel(String siteName, List<JobPosting> jobs) {
        List<Callable<Void>> tasks = new ArrayList<>();

        for (JobPosting job : jobs) {
            if (job.getSourceUrl() == null || job.getSourceUrl().isBlank()) continue;

            tasks.add(() -> {
                try {
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
                    switch (siteName) {
                        case "사람인" -> crawlSaraminDetails(job, localDriver);
                        case "잡코리아" -> crawlJobkoreaDetails(job, localDriver);
                        case "원티드" -> crawlWantedDetails(job, localDriver);
                        case "프로그래머스" -> crawlProgrammersDetails(job, localDriver);
                        case "점핏" -> crawlJumpitDetails(job, localDriver);
                    }
                } catch (Exception e) {
                    log.warn("상세 크롤링 작업 실패: {} - {}", siteName, job.getSourceUrl(), e);
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
                    log.warn("상세 크롤링 타임아웃");
                } catch (ExecutionException ee) {
                    log.warn("상세 크롤링 태스크 예외", ee.getCause());
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("상세 크롤링 병렬 처리 인터럽트", ie);
        }
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
}