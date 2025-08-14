package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class JobCrawlingService {

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private EmbeddingService embeddingService;

    private static final int CONNECTION_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 15000;
    private static final int MAX_RETRIES = 3;
    private static final long DELAY_BETWEEN_REQUESTS = 1000; // 1초

    // 날짜 파싱을 위한 패턴들
    private static final Pattern DATE_PATTERN_1 = Pattern.compile("(\\d{4})\\.(\\d{2})\\.(\\d{2})");
    private static final Pattern DATE_PATTERN_2 = Pattern.compile("(\\d{2})/(\\d{2})");
    private static final Pattern DATE_PATTERN_3 = Pattern.compile("~\\s*(\\d{2})/(\\d{2})");

    @PostConstruct
    public void init() {
        setupSSLContext();
    }

    private void setupSSLContext() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return null; }
                    public void checkClientTrusted(X509Certificate[] certs, String authType) { }
                    public void checkServerTrusted(X509Certificate[] certs, String authType) { }
                }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);

        } catch (Exception e) {
            log.error("SSL 설정 실패", e);
        }
    }

    @Scheduled(cron = "0 0 9 * * *")
    public void scheduledCrawling() {
        log.info("정기 채용공고 크롤링 시작");
        try {
            List<JobPosting> allJobs = crawlAllSitesSync();
            saveJobPostings(allJobs);
            log.info("정기 크롤링 완료. 총 {}개 채용공고 처리", allJobs.size());
        } catch (Exception e) {
            log.error("정기 크롤링 실패", e);
        }
    }

    @Async
    public CompletableFuture<List<JobPosting>> crawlAllSites() {
        List<JobPosting> allJobs = crawlAllSitesSync();
        saveJobPostings(allJobs);
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

            // 원티드 크롤링
            List<JobPosting> wantedJobs = crawlWanted();
            allJobs.addAll(wantedJobs);
            log.info("원티드 크롤링 완료: {}개", wantedJobs.size());

            // 인크루트 크롤링 추가
            List<JobPosting> incrutJobs = crawlIncrut();
            allJobs.addAll(incrutJobs);
            log.info("인크루트 크롤링 완료: {}개", incrutJobs.size());

            log.info("전체 크롤링 완료. 총 {}개 채용공고 수집", allJobs.size());

        } catch (Exception e) {
            log.error("크롤링 중 오류 발생", e);
        }

        return allJobs;
    }

    private List<JobPosting> crawlSaramin() {
        List<JobPosting> jobs = new ArrayList<>();

        try {
            // 여러 페이지 크롤링
            for (int page = 1; page <= 5; page++) {
                String url = String.format(
                    "https://www.saramin.co.kr/zf_user/search/recruit?searchType=search&searchword=개발자&recruitPage=%d",
                    page
                );

                Document doc = connectWithRetry(url);
                if (doc == null) continue;

                Elements jobElements = doc.select(".item_recruit");
                log.info("사람인 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                if (jobElements.isEmpty()) break;

                for (Element jobElement : jobElements) {
                    JobPosting job = parseSaraminJob(jobElement);
                    if (job != null) {
                        jobs.add(job);
                    }

                    // 요청 간격 조절
                    Thread.sleep(DELAY_BETWEEN_REQUESTS);
                }
            }

        } catch (Exception e) {
            log.error("사람인 크롤링 실패", e);
        }

        return jobs;
    }

    private JobPosting parseSaraminJob(Element jobElement) {
        try {
            JobPosting job = new JobPosting();

            // 제목과 URL
            Element titleElement = jobElement.selectFirst(".job_tit a");
            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.text()));
                job.setSourceUrl("https://www.saramin.co.kr" + titleElement.attr("href"));
            }

            // 회사명
            Element companyElement = jobElement.selectFirst(".corp_name a");
            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.text()));
            }

            // 지역
            Element locationElement = jobElement.selectFirst(".job_condition span");
            if (locationElement != null) {
                job.setLocation(cleanText(locationElement.text()));
            }

            // 경력
            Element expElement = jobElement.selectFirst(".job_condition span:nth-child(2)");
            if (expElement != null) {
                job.setExperienceLevel(cleanText(expElement.text()));
            }

            // 고용형태
            Element empTypeElement = jobElement.selectFirst(".job_condition span:nth-child(3)");
            if (empTypeElement != null) {
                job.setEmploymentType(cleanText(empTypeElement.text()));
            }

            // 마감일
            Element deadlineElement = jobElement.selectFirst(".job_date .date");
            if (deadlineElement != null) {
                job.setDeadline(parseDeadline(deadlineElement.text()));
            }

            // 기본 설정
            job.setSourceSite("사람인");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            // 필수 필드 검증
            if (isValidJob(job)) {
                // 상세 정보 크롤링
                crawlSaraminDetails(job);
                return job;
            }

        } catch (Exception e) {
            log.warn("사람인 개별 채용공고 파싱 실패", e);
        }
        return null;
    }

    private void crawlSaraminDetails(JobPosting job) {
        if (job.getSourceUrl() == null) return;

        try {
            Document detailDoc = connectWithRetry(job.getSourceUrl());
            if (detailDoc == null) return;

            // 상세 설명
            Element descElement = detailDoc.selectFirst(".user_content");
            if (descElement == null) {
                descElement = detailDoc.selectFirst(".content");
            }
            if (descElement != null) {
                job.setDescription(cleanText(descElement.text()));
            }

            // 요구사항
            Element reqElement = detailDoc.selectFirst(".qualification");
            if (reqElement == null) {
                reqElement = detailDoc.selectFirst(".requirements");
            }
            if (reqElement != null) {
                job.setRequirements(cleanText(reqElement.text()));
            }

            // 혜택
            Element benefitElement = detailDoc.selectFirst(".welfare");
            if (benefitElement == null) {
                benefitElement = detailDoc.selectFirst(".benefits");
            }
            if (benefitElement != null) {
                job.setBenefits(cleanText(benefitElement.text()));
            }

            // 급여
            Element salaryElement = detailDoc.selectFirst(".salary");
            if (salaryElement == null) {
                salaryElement = detailDoc.selectFirst(".condition .salary");
            }
            if (salaryElement != null) {
                job.setSalary(cleanText(salaryElement.text()));
            }

        } catch (Exception e) {
            log.warn("사람인 상세 정보 크롤링 실패: {}", job.getSourceUrl(), e);
        }
    }

    private List<JobPosting> crawlJobkorea() {
        List<JobPosting> jobs = new ArrayList<>();

        try {
            for (int page = 1; page <= 5; page++) {
                String url = String.format(
                    "https://www.jobkorea.co.kr/Search/?stext=개발자&Page_No=%d",
                    page
                );

                Document doc = connectWithRetry(url);
                if (doc == null) continue;

                Elements jobElements = doc.select(".list-default .list-post");
                if (jobElements.isEmpty()) {
                    jobElements = doc.select(".recruit-info");
                }

                log.info("잡코리아 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                if (jobElements.isEmpty()) break;

                for (Element jobElement : jobElements) {
                    JobPosting job = parseJobkoreaJob(jobElement);
                    if (job != null) {
                        jobs.add(job);
                    }

                    Thread.sleep(DELAY_BETWEEN_REQUESTS);
                }
            }

        } catch (Exception e) {
            log.error("잡코리아 크롤링 실패", e);
        }

        return jobs;
    }

    private JobPosting parseJobkoreaJob(Element jobElement) {
        try {
            JobPosting job = new JobPosting();

            // 제목과 URL
            Element titleElement = jobElement.selectFirst(".post-list-info .title a");
            if (titleElement == null) {
                titleElement = jobElement.selectFirst("a[href*='/Recruit/']");
            }
            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.text()));
                String href = titleElement.attr("href");
                if (!href.startsWith("http")) {
                    href = "https://www.jobkorea.co.kr" + href;
                }
                job.setSourceUrl(href);
            }

            // 회사명
            Element companyElement = jobElement.selectFirst(".post-list-corp .name a");
            if (companyElement == null) {
                companyElement = jobElement.selectFirst(".company-name a");
            }
            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.text()));
            }

            // 지역
            Element locationElement = jobElement.selectFirst(".post-list-info .option .location");
            if (locationElement == null) {
                locationElement = jobElement.selectFirst(".location");
            }
            if (locationElement != null) {
                job.setLocation(cleanText(locationElement.text()));
            }

            // 경력
            Element expElement = jobElement.selectFirst(".experience");
            if (expElement != null) {
                job.setExperienceLevel(cleanText(expElement.text()));
            }

            // 마감일
            Element deadlineElement = jobElement.selectFirst(".date");
            if (deadlineElement != null) {
                job.setDeadline(parseDeadline(deadlineElement.text()));
            }

            job.setSourceSite("잡코리아");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            if (isValidJob(job)) {
                crawlJobkoreaDetails(job);
                return job;
            }

        } catch (Exception e) {
            log.warn("잡코리아 개별 채용공고 파싱 실패", e);
        }
        return null;
    }

    private void crawlJobkoreaDetails(JobPosting job) {
        if (job.getSourceUrl() == null) return;

        try {
            Document detailDoc = connectWithRetry(job.getSourceUrl());
            if (detailDoc == null) return;

            // 상세 설명
            Element descElement = detailDoc.selectFirst(".section-content");
            if (descElement == null) {
                descElement = detailDoc.selectFirst(".content");
            }
            if (descElement != null) {
                job.setDescription(cleanText(descElement.text()));
            }

            // 급여
            Element salaryElement = detailDoc.selectFirst(".salary-info");
            if (salaryElement != null) {
                job.setSalary(cleanText(salaryElement.text()));
            }

        } catch (Exception e) {
            log.warn("잡코리아 상세 정보 크롤링 실패: {}", job.getSourceUrl(), e);
        }
    }

    private List<JobPosting> crawlWanted() {
        List<JobPosting> jobs = new ArrayList<>();

        try {
            for (int offset = 0; offset < 100; offset += 20) {
                String url = String.format(
                    "https://www.wanted.co.kr/search?query=개발자&offset=%d",
                    offset
                );

                Document doc = connectWithRetry(url);
                if (doc == null) continue;

                // 원티드는 React 기반이므로 기본 HTML에서 데이터를 찾기 어려움
                // API 엔드포인트나 다른 방법 필요
                Elements jobElements = doc.select("[data-cy='job-card']");
                if (jobElements.isEmpty()) {
                    jobElements = doc.select(".JobCard_container__FqCHh");
                }

                log.info("원티드에서 {}개 채용공고 발견", jobElements.size());

                if (jobElements.isEmpty()) break;

                for (Element jobElement : jobElements) {
                    JobPosting job = parseWantedJob(jobElement);
                    if (job != null) {
                        jobs.add(job);
                    }

                    Thread.sleep(DELAY_BETWEEN_REQUESTS);
                }
            }

        } catch (Exception e) {
            log.error("원티드 크롤링 실패", e);
        }

        return jobs;
    }

    private JobPosting parseWantedJob(Element jobElement) {
        try {
            JobPosting job = new JobPosting();

            // 원티드는 클래스명이 자주 변경되므로 여러 선택자 시도
            Element titleElement = jobElement.selectFirst("h2");
            if (titleElement == null) {
                titleElement = jobElement.selectFirst(".job-title");
            }
            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.text()));
            }

            Element companyElement = jobElement.selectFirst(".company-name");
            if (companyElement == null) {
                companyElement = jobElement.selectFirst(".company");
            }
            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.text()));
            }

            job.setSourceSite("원티드");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("원티드 개별 채용공고 파싱 실패", e);
        }
        return null;
    }

    private List<JobPosting> crawlIncrut() {
        List<JobPosting> jobs = new ArrayList<>();

        try {
            for (int page = 1; page <= 3; page++) {
                String url = String.format(
                    "https://job.incruit.com/jobdb_info/jobpost.asp?job_cd=146&page=%d",
                    page
                );

                Document doc = connectWithRetry(url);
                if (doc == null) continue;

                Elements jobElements = doc.select(".list_default li");
                log.info("인크루트 페이지 {}에서 {}개 채용공고 발견", page, jobElements.size());

                if (jobElements.isEmpty()) break;

                for (Element jobElement : jobElements) {
                    JobPosting job = parseIncrutJob(jobElement);
                    if (job != null) {
                        jobs.add(job);
                    }

                    Thread.sleep(DELAY_BETWEEN_REQUESTS);
                }
            }

        } catch (Exception e) {
            log.error("인크루트 크롤링 실패", e);
        }

        return jobs;
    }

    private JobPosting parseIncrutJob(Element jobElement) {
        try {
            JobPosting job = new JobPosting();

            Element titleElement = jobElement.selectFirst(".tit a");
            if (titleElement != null) {
                job.setTitle(cleanText(titleElement.text()));
                job.setSourceUrl("https://job.incruit.com" + titleElement.attr("href"));
            }

            Element companyElement = jobElement.selectFirst(".company a");
            if (companyElement != null) {
                job.setCompany(cleanText(companyElement.text()));
            }

            Element locationElement = jobElement.selectFirst(".location");
            if (locationElement != null) {
                job.setLocation(cleanText(locationElement.text()));
            }

            job.setSourceSite("인크루트");
            job.setJobCategory("개발");
            job.setIsActive(true);
            job.setCreatedAt(LocalDateTime.now());
            job.setUpdatedAt(LocalDateTime.now());

            return isValidJob(job) ? job : null;

        } catch (Exception e) {
            log.warn("인크루트 개별 채용공고 파싱 실패", e);
        }
        return null;
    }

    private Document connectWithRetry(String url) {
        for (int retry = 0; retry < MAX_RETRIES; retry++) {
            try {
                return Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        .timeout(CONNECTION_TIMEOUT)
                        .followRedirects(true)
                        .ignoreHttpErrors(true)
                        .get();

            } catch (Exception e) {
                log.warn("연결 실패 (시도 {}/{}): {}", retry + 1, MAX_RETRIES, url, e);

                if (retry < MAX_RETRIES - 1) {
                    try {
                        Thread.sleep(2000 * (retry + 1)); // 지수 백오프
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return null;
    }

    private LocalDateTime parseDeadline(String deadlineText) {
        if (deadlineText == null || deadlineText.trim().isEmpty()) {
            return null;
        }

        deadlineText = deadlineText.trim().replace("~", "").replace("까지", "").trim();

        try {
            // YYYY.MM.DD 형식
            Matcher matcher = DATE_PATTERN_1.matcher(deadlineText);
            if (matcher.find()) {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                return LocalDateTime.of(year, month, day, 23, 59, 59);
            }

            // MM/DD 형식 (올해 기준)
            matcher = DATE_PATTERN_2.matcher(deadlineText);
            if (matcher.find()) {
                int month = Integer.parseInt(matcher.group(1));
                int day = Integer.parseInt(matcher.group(2));
                int currentYear = LocalDateTime.now().getYear();
                return LocalDateTime.of(currentYear, month, day, 23, 59, 59);
            }

            // "상시채용", "수시채용" 등의 경우 null 반환
            if (deadlineText.contains("상시") || deadlineText.contains("수시") ||
                deadlineText.contains("채용시") || deadlineText.contains("마감")) {
                return null;
            }

        } catch (Exception e) {
            log.warn("날짜 파싱 실패: {}", deadlineText, e);
        }

        return null;
    }

    private String cleanText(String text) {
        if (text == null) return null;
        return text.replaceAll("\\s+", " ").trim();
    }

    private boolean isValidJob(JobPosting job) {
        return job.getTitle() != null && !job.getTitle().trim().isEmpty() &&
               job.getCompany() != null && !job.getCompany().trim().isEmpty();
    }

    private void saveJobPostings(List<JobPosting> jobs) {
        for (JobPosting job : jobs) {
            saveIndividualJob(job);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveIndividualJob(JobPosting job) {
        try {
            if (!isValidJob(job)) {
                log.warn("필수 필드 누락된 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
                return;
            }

            // 중복 체크 (제목, 회사명, 사이트 기준)
            Boolean existingJobs = jobPostingRepository.existsBySourceUrlAndIsActiveTrue(job.getSourceUrl());

            if (!existingJobs) {
                JobPosting newJob = createCleanJobPosting(job);

                // 임베딩 생성
                try {
                    String content = buildContentForEmbedding(newJob);
                    if (!content.trim().isEmpty()) {
                        float[] embedding = embeddingService.embed(content);
                        newJob.setEmbedding(embedding);
                    }
                } catch (Exception embeddingError) {
                    log.warn("임베딩 생성 실패, null로 설정: {} - {}",
                            newJob.getCompany(), newJob.getTitle(), embeddingError);
                    newJob.setEmbedding(null);
                }

                jobPostingRepository.save(newJob);
                log.debug("새 채용공고 저장: {} - {}", newJob.getCompany(), newJob.getTitle());

            } else {
                log.debug("중복 채용공고 스킵: {} - {}", job.getCompany(), job.getTitle());
            }

        } catch (Exception e) {
            log.error("개별 채용공고 저장 실패: {} - {}",
                    job.getCompany() != null ? job.getCompany() : "Unknown",
                    job.getTitle() != null ? job.getTitle() : "Unknown", e);
        }
    }

    private JobPosting createCleanJobPosting(JobPosting source) {
        JobPosting newJob = new JobPosting();

        // 모든 필드 복사 (테이블 스키마에 맞춰)
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

        // 기본값 설정
        newJob.setIsActive(true);
        newJob.setCreatedAt(LocalDateTime.now());
        newJob.setUpdatedAt(LocalDateTime.now());

        // ID는 null로 두어서 새로운 엔티티임을 보장
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

    public CompletableFuture<String> startManualCrawling() {
        log.info("수동 채용공고 크롤링 시작");
        return crawlAllSites().thenApply(jobs ->
            String.format("크롤링 완료: %d개 채용공고 수집", jobs.size())
        );
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

    // 비활성화된 채용공고 정리
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
}