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

    // 대기/지연 설정 최적화
    private static final int IMPLICIT_WAIT_SECONDS = 3;
    private static final int EXPLICIT_WAIT_SECONDS = 10;
    private static final int PAGE_LOAD_TIMEOUT_SECONDS = 15;
    private static final long MIN_DELAY = 120;   // 기존 2000 -> 120ms
    private static final long MAX_DELAY = 250;   // 기존 4000 -> 250ms
    private static final int MAX_RETRIES = 1;

    // 상세 페이지 병렬 처리 개수 (CPU/IO 혼합 워크로드 고려)
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
            WebDriverManager.chromedriver().setup();
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
            WebDriverManager.chromedriver().setup();

            ChromeOptions options = new ChromeOptions();

            // 최신 헤드리스
            options.addArguments("--headless=new");

            // 안정성 및 성능 옵션
            options.addArguments("--no-sandbox");
            options.addArguments("--disable-dev-shm-usage");
            options.addArguments("--disable-gpu");
            options.addArguments("--disable-web-security");
            options.addArguments("--disable-features=VizDisplayCompositor");
            options.addArguments("--disable-extensions");
            options.addArguments("--disable-plugins");
            options.addArguments("--window-size=1920,1080");
            options.setPageLoadStrategy(PageLoadStrategy.EAGER);

            // 리소스 절감(이미지/미디어 차단)
            Map<String, Object> prefs = new HashMap<>();
            prefs.put("profile.managed_default_content_settings.images", 2);
            prefs.put("profile.managed_default_content_settings.stylesheets", 2);
            prefs.put("profile.managed_default_content_settings.cookies", 1);
            prefs.put("profile.managed_default_content_settings.javascript", 1);
            prefs.put("profile.managed_default_content_settings.plugins", 2);
            prefs.put("profile.managed_default_content_settings.popups", 2);
            prefs.put("profile.managed_default_content_settings.geolocation", 2);
            options.setExperimentalOption("prefs", prefs);

            // User-Agent 무작위
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

            WebDriver driver = new ChromeDriver(options);

            driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(IMPLICIT_WAIT_SECONDS));
            driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(PAGE_LOAD_TIMEOUT_SECONDS));

            JavascriptExecutor js = (JavascriptExecutor) driver;
            js.executeScript("Object.defineProperty(navigator, 'webdriver', {get: () => undefined})");

            log.debug("WebDriver 생성 성공");
            return driver;

        } catch (Exception e) {
            log.error("WebDriver 생성 실패", e);
            throw new RuntimeException("WebDriver 생성 실패", e);
        }
    }

    public CompletableFuture<String> startManualCrawling() {
        log.info("수동 채용공고 크롤링 시작");
        try {
            return crawlAllSites().thenApply(jobs ->
                String.format("크롤링 완료: %d개 채용공고 수집", jobs.size())
            );
        } catch (Exception e) {
            log.error("수동 크롤링 시작 실패", e);
            return CompletableFuture.completedFuture("크롤링 실패: " + e.getMessage());
        }
    }

//    @Scheduled(cron = "0 0 9 * * *")
//    public void scheduledCrawling() {
//        log.info("정기 채용공고 크롤링 시작");
//        try {
//            List<JobPosting> allJobs = crawlAllSitesSync();
//            saveJobPostings(allJobs);
//            log.info("정기 크롤링 완료. 총 {}개 채용공고 처리", allJobs.size());
//        } catch (Exception e) {
//            log.error("정기 크롤링 실패", e);
//        } finally {
//            closeDriver();
//        }
//    }

    @Async
    public CompletableFuture<List<JobPosting>> crawlAllSites() {
        List<JobPosting> allJobs = crawlAllSitesSync();
        //saveJobPostings(allJobs);
        closeDriver();
        return CompletableFuture.completedFuture(allJobs);
    }

    public List<JobPosting> crawlAllSitesSync() {
        List<JobPosting> allJobs = new ArrayList<>();

        try {
            // 사람인 크롤링
            List<JobPosting> saraminJobs = crawlSaramin();
            allJobs.addAll(saraminJobs);
            log.info("사람인 크롤링 완료: {}개", saraminJobs.size());

            // 잡코리아 크롤링
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

                // 페이지 로딩 대기
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

            // 상세 정보 병렬 크롤링
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

            // 상세 정보 병렬 크롤링
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

                // 페이지가 완전히 로드될 때까지 대기
                WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(8));
                wait.until(webDriver -> ((JavascriptExecutor) webDriver)
                    .executeScript("return document.readyState").equals("complete"));

                // 봇 차단 페이지 감지
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
             ".recruit-item", "[data-gno]", ".post-list-item"}
        };

        String[] siteSelectors = siteName.equals("사람인") ? selectors[0] : selectors[1];

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

            // 제목과 URL
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

            // 회사명
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

            // 짧은 대기 후 페이지 상태 확인
            // 상세 내용이 로드될 때까지 대기 (더 유연한 셀렉터 사용)
            WebDriverWait wait = getWait();
            try {
                // 더 일반적인 요소부터 확인
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
                // 페이지가 로드되지 않아도 계속 진행 (일부 정보라도 추출 시도)
            }

            // 페이지 소스 확인 및 디버깅
            String pageSource = driver.getPageSource();
            log.debug("페이지 소스 길이: {}, 제목: {}", pageSource.length(), driver.getTitle());

            // 봇 차단 감지
            if (pageSource.toLowerCase().contains("robot") ||
                pageSource.toLowerCase().contains("captcha") ||
                pageSource.toLowerCase().contains("차단") ||
                pageSource.toLowerCase().contains("접근") ||
                driver.getTitle().toLowerCase().contains("error")) {
                log.warn("사람인 봇 차단 페이지 감지: {}", job.getSourceUrl());
                return;
            }

            // 상세 설명 추출 (더 많은 셀렉터 시도)
            WebElement descElement = findElementBySelectors(driver,
                ".user_content", ".content", ".job_description", ".recruit_content",
                ".view_content", ".job_info .content", "#recrut_content",
                ".summary .content", ".cont", ".detail_content", ".job_detail",
                ".description", "main .content", ".container .content",
                "[class*='content']", "[class*='description']", "[class*='detail']",
                "article", "section", ".wrap_jview", ".section");

            if (descElement != null) {
                String description = cleanText(descElement.getText());
                if (description != null && description.length() > 20) {  // 최소 길이 체크
                    job.setDescription(description);
                    log.debug("사람인 설명 추출 성공: {} characters", description.length());
                } else {
                    log.debug("사람인 설명이 너무 짧거나 비어있음: {}", description);
                }
            } else {
                log.debug("사람인 설명 요소를 찾을 수 없음: {}", job.getSourceUrl());

                // 디버깅을 위해 페이지 구조 분석
                if (log.isDebugEnabled()) {
                    debugPageStructure(driver, job.getSourceUrl());
                }
            }

            // 추가 정보 추출
            extractAdditionalJobInfo(driver, job);

            saveIndividualJob(job);

        } catch (Exception e) {
            log.warn("사람인 상세 정보 크롤링 실패: {}, error: {}", job.getSourceUrl(), e.getMessage());
        } finally {
            // 원래 페이지로 돌아가기 (선택사항)
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
            // 지역
            WebElement locationElement = findElementBySelectors(jobElement,
                ".job_condition span", ".area_job .condition span",
                ".job_meta span", ".location", ".work_place",
                ".post-list-info .option .location", ".area", ".region");

            if (locationElement != null) {
                job.setLocation(cleanText(locationElement.getText()));
            }

            // 경력, 고용형태 등
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
            // 요구사항
            WebElement reqElement = findElementBySelectors(driver,
                ".qualification", ".requirements", ".job_requirement",
                ".recruit_requirement", ".requirement");

            if (reqElement != null) {
                job.setRequirements(cleanText(reqElement.getText()));
            }

            // 혜택
            WebElement benefitElement = findElementBySelectors(driver,
                ".welfare", ".benefits", ".benefit_info", ".benefit");

            if (benefitElement != null) {
                job.setBenefits(cleanText(benefitElement.getText()));
            }

            // 급여
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

    // 디버깅용: 페이지 구조 분석
    private void debugPageStructure(WebDriver driver, String url) {
        try {
            log.info("=== 페이지 구조 분석: {} ===", url);
            log.info("페이지 제목: {}", driver.getTitle());
            log.info("현재 URL: {}", driver.getCurrentUrl());

            // 주요 컨테이너 요소들 확인
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

            // 페이지 소스의 일부 출력 (처음 500자)
            String pageSource = driver.getPageSource();
            if (pageSource.length() > 500) {
                log.info("페이지 소스 (처음 500자): {}", pageSource.substring(0, 500));
            }

        } catch (Exception e) {
            log.warn("페이지 구조 분석 실패", e);
        }
    }

    // 테스트용 메서드
    public void testSpecificUrl(String url, String siteName) {
        log.info("URL 테스트 시작: {}", url);

        WebDriver driver = getDriver();
        try {
            if (loadPage(driver, url, siteName)) {
                log.info("페이지 로드 성공");

                // 디버깅 정보 출력
                debugPageStructure(driver, url);

                // 주요 요소들 확인
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

    // 기존 메서드들 (saveJobPostings, createCleanJobPosting 등)
    private void saveJobPostings(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            saveIndividualJob(job);
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

        // 1) 먼저 저장 + 즉시 flush
        JobPosting saved = jobPostingRepository.saveAndFlush(newJob);

        try {
          String content = buildContentForEmbedding(saved);
          if (!content.trim().isEmpty()) {
            float[] embeddingArray = embeddingService.embed(content);
            saved.setEmbedding(embeddingArray); // 애플리케이션 보관용(@Transient라면 DB엔 영향 없음)
            String embeddingText = floatArrayToVectorString(embeddingArray);

            // 2) 같은 트랜잭션에서 네이티브 UPDATE (flushAutomatically로 안전)
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

    // ------------------------------
    // 성능 개선: 상세 페이지 병렬 크롤링 유틸리티
    // ------------------------------
    private void fetchDetailsInParallel(String siteName, List<JobPosting> jobs) {
        List<Callable<Void>> tasks = new ArrayList<>();

        for (JobPosting job : jobs) {
            // 소스 URL 없으면 상세 스킵
            if (job.getSourceUrl() == null || job.getSourceUrl().isBlank()) continue;

            tasks.add(() -> {
                // 이미 저장된 활성 공고면 상세 스킵 (네트워크 비용 절감)
                try {
                    Boolean exists = jobPostingRepository.existsBySourceUrlAndIsActiveTrue(job.getSourceUrl());
                    if (Boolean.TRUE.equals(exists)) {
                        log.debug("상세 스킵(기존 활성): {} - {}", job.getCompany(), job.getTitle());
                        return null;
                    }
                } catch (Exception e) {
                    // 저장소 조회 실패 시 상세 시도는 계속
                    log.debug("기존 여부 확인 실패, 상세 시도 진행: {}", job.getSourceUrl());
                }

                WebDriver localDriver = getDriver();
                try {
                    if ("사람인".equals(siteName)) {
                        crawlSaraminDetails(job, localDriver);
                    } else {
                        crawlJobkoreaDetails(job, localDriver);
                    }
                } catch (Exception e) {
                    log.warn("상세 크롤링 작업 실패: {} - {}", siteName, job.getSourceUrl(), e);
                } finally {
                    // 각 스레드별 드라이버 정리
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
                    f.get(60, TimeUnit.SECONDS); // 태스크 타임아웃
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