// JobPostingController.java - 사이트별 크롤링 API 추가
package com.ai.hybridsearch.controller;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.ai.hybridsearch.service.impl.JobCrawlingServiceImpl;
import com.ai.hybridsearch.service.impl.JobSearchServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/jobs")
@CrossOrigin(origins = "*")
@Slf4j
public class JobPostingController {

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private JobCrawlingServiceImpl jobCrawlingServiceImpl;

    @Autowired
    private JobSearchServiceImpl jobSearchServiceImpl;

    // ===== 기존 API들 =====

    // 모든 채용공고 조회 (페이징)
    @GetMapping
    public ResponseEntity<Page<JobPosting>> getAllJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        Sort sort = sortDir.equalsIgnoreCase("desc")
                ? Sort.by(sortBy).descending()
                : Sort.by(sortBy).ascending();

        Pageable pageable = PageRequest.of(page, size, sort);
        Page<JobPosting> jobs = jobPostingRepository.findByIsActiveTrue(pageable);

        return ResponseEntity.ok(jobs);
    }

    // 채용공고 상세 조회
    @GetMapping("/{id}")
    public ResponseEntity<JobPosting> getJob(@PathVariable Long id) {
        Optional<JobPosting> job = jobPostingRepository.findById(id);
        return job.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // 카테고리별 채용공고 조회
    @GetMapping("/category/{category}")
    public ResponseEntity<Page<JobPosting>> getJobsByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("createdAt").descending());

        Page<JobPosting> jobs = jobPostingRepository
                .findByJobCategoryAndIsActiveTrue(category, pageable);

        return ResponseEntity.ok(jobs);
    }

    // 회사별 채용공고 조회
    @GetMapping("/company/{company}")
    public ResponseEntity<List<JobPosting>> getJobsByCompany(@PathVariable String company) {
        List<JobPosting> jobs = jobPostingRepository
                .findByCompanyContainingIgnoreCaseAndIsActiveTrue(company);
        return ResponseEntity.ok(jobs);
    }

    // 지역별 채용공고 조회
    @GetMapping("/location/{location}")
    public ResponseEntity<Page<JobPosting>> getJobsByLocation(
            @PathVariable String location,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("createdAt").descending());

        Page<JobPosting> jobs = jobPostingRepository
                .findByLocationContainingIgnoreCaseAndIsActiveTrue(location, pageable);

        return ResponseEntity.ok(jobs);
    }

    // 키워드 검색
    @GetMapping("/search")
    public ResponseEntity<Page<JobPosting>> searchJobs(
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size,
                Sort.by("createdAt").descending());

        Page<JobPosting> jobs = jobPostingRepository
                .findByTitleContainingIgnoreCaseOrCompanyContainingIgnoreCaseOrDescriptionContainingIgnoreCaseAndIsActiveTrue(
                        keyword, keyword, keyword, pageable);

        return ResponseEntity.ok(jobs);
    }

    // AI 기반 유사 채용공고 검색
    @PostMapping("/search/similar")
    public ResponseEntity<List<JobPosting>> searchSimilarJobs(
            @RequestBody Map<String, Object> request) {

        String query = (String) request.get("query");
        Integer limit = (Integer) request.getOrDefault("limit", 10);

        List<JobPosting> similarJobs = jobSearchServiceImpl.searchSimilarJobs(query, limit);
        return ResponseEntity.ok(similarJobs);
    }

    // 하이브리드 검색 (키워드 + 유사도)
    @PostMapping("/search/hybrid")
    public ResponseEntity<List<JobPosting>> hybridSearch(
            @RequestBody Map<String, Object> request) {

        String query = (String) request.get("query");
        Integer limit = (Integer) request.getOrDefault("limit", 20);

        List<JobPosting> results = jobSearchServiceImpl.hybridSearch(query, limit);
        return ResponseEntity.ok(results);
    }

    // ===== 크롤링 관련 API들 =====

    // 전체 크롤링 실행 (기존 메소드)
    @PostMapping("/crawl")
    public ResponseEntity<String> startCrawling() {
        try {
            CompletableFuture<String> result = jobCrawlingServiceImpl.startManualCrawling();
            return ResponseEntity.ok("전체 사이트 크롤링이 시작되었습니다. 백그라운드에서 실행됩니다.");
        } catch (Exception e) {
            log.error("크롤링 시작 실패", e);
            return ResponseEntity.internalServerError()
                    .body("크롤링 시작에 실패했습니다: " + e.getMessage());
        }
    }

    /**
     * 특정 사이트들만 크롤링
     */
    @PostMapping("/crawl/sites")
    public ResponseEntity<Map<String, Object>> startSiteSpecificCrawling(
            @RequestBody Map<String, Object> request) {
        try {
            @SuppressWarnings("unchecked")
            List<String> sites = (List<String>) request.get("sites");

            if (sites == null || sites.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "message", "크롤링할 사이트를 선택해주세요",
                                "error", "INVALID_SITES"
                        ));
            }

            // 지원하지 않는 사이트 체크
            Map<String, String> supportedSites = jobCrawlingServiceImpl.getSupportedSites();
            List<String> invalidSites = sites.stream()
                    .filter(site -> !supportedSites.containsKey(site))
                    .toList();

            if (!invalidSites.isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "message", "지원하지 않는 사이트가 포함되어 있습니다: " + String.join(", ", invalidSites),
                                "error", "UNSUPPORTED_SITES",
                                "invalidSites", invalidSites,
                                "supportedSites", supportedSites
                        ));
            }

            CompletableFuture<String> result = jobCrawlingServiceImpl.startCrawlingBySites(sites);

            List<String> siteNames = sites.stream()
                    .map(supportedSites::get)
                    .toList();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "선택된 사이트들의 크롤링이 시작되었습니다.",
                    "sites", sites,
                    "siteNames", siteNames,
                    "status", "STARTED"
            ));

        } catch (Exception e) {
            log.error("사이트별 크롤링 시작 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "message", "크롤링 시작에 실패했습니다: " + e.getMessage(),
                            "error", "INTERNAL_ERROR"
                    ));
        }
    }

    /**
     * 지원 가능한 크롤링 사이트 목록
     */
    @GetMapping("/crawl/sites")
    public ResponseEntity<Map<String, Object>> getSupportedSites() {
        try {
            Map<String, String> sites = jobCrawlingServiceImpl.getSupportedSites();

            // 사이트별 기본 정보 추가
            Map<String, Map<String, Object>> siteDetails = sites.entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            Map.Entry::getKey,
                            entry -> Map.of(
                                    "id", entry.getKey(),
                                    "name", entry.getValue(),
                                    "enabled", true,
                                    "description", getSiteDescription(entry.getKey())
                            )
                    ));

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sites", sites,
                    "siteDetails", siteDetails,
                    "totalSites", sites.size()
            ));
        } catch (Exception e) {
            log.error("지원 사이트 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "message", "지원 사이트 목록 조회에 실패했습니다",
                            "error", "FETCH_ERROR"
                    ));
        }
    }

    /**
     * 모든 사이트의 크롤링 상태
     */
    @GetMapping("/crawl/sites/status")
    public ResponseEntity<Map<String, Object>> getAllSitesStatus() {
        try {
            List<Map<String, Object>> sitesStatus = jobCrawlingServiceImpl.getAllSitesStatus();

            // 전체 통계 계산
            long totalJobs = sitesStatus.stream()
                    .mapToLong(site -> ((Number) site.get("totalJobs")).longValue())
                    .sum();

            long totalRecentJobs = sitesStatus.stream()
                    .mapToLong(site -> ((Number) site.getOrDefault("recentJobs", 0)).longValue())
                    .sum();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "sites", sitesStatus,
                    "summary", Map.of(
                            "totalJobs", totalJobs,
                            "totalRecentJobs", totalRecentJobs,
                            "activeSites", sitesStatus.size(),
                            "lastUpdated", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    )
            ));
        } catch (Exception e) {
            log.error("사이트 상태 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "message", "사이트 상태 조회에 실패했습니다",
                            "error", "STATUS_ERROR"
                    ));
        }
    }

    /**
     * 특정 사이트의 크롤링 상태
     */
    @GetMapping("/crawl/sites/{siteId}/status")
    public ResponseEntity<Map<String, Object>> getSiteStatus(@PathVariable String siteId) {
        try {
            Map<String, Object> status = jobCrawlingServiceImpl.getSiteStatus(siteId);

            if (status.containsKey("error")) {
                return ResponseEntity.badRequest()
                        .body(Map.of(
                                "success", false,
                                "message", status.get("error"),
                                "error", "INVALID_SITE_ID",
                                "siteId", siteId
                        ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "site", status
            ));

        } catch (Exception e) {
            log.error("사이트 상태 조회 실패: {}", siteId, e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "message", "사이트 상태 조회에 실패했습니다",
                            "error", "STATUS_ERROR",
                            "siteId", siteId
                    ));
        }
    }

    /**
     * 사이트별 채용공고 통계
     */
    @GetMapping("/stats/sites")
    public ResponseEntity<Map<String, Object>> getSiteStatistics() {
        try {
            Map<String, Object> statistics = jobCrawlingServiceImpl.getSiteStatistics();

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "statistics", statistics,
                    "generatedAt", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            ));

        } catch (Exception e) {
            log.error("사이트별 통계 조회 실패", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of(
                            "success", false,
                            "message", "통계 조회에 실패했습니다",
                            "error", "STATS_ERROR"
                    ));
        }
    }

    /**
     * 특정 사이트의 채용공고 조회
     */
    @GetMapping("/sites/{siteId}/jobs")
    public ResponseEntity<Page<JobPosting>> getJobsBySite(
            @PathVariable String siteId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "createdAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {

        try {
            Map<String, String> supportedSites = jobCrawlingServiceImpl.getSupportedSites();
            String siteName = supportedSites.get(siteId);

            if (siteName == null) {
                return ResponseEntity.badRequest().build();
            }

            Sort sort = sortDir.equalsIgnoreCase("desc")
                    ? Sort.by(sortBy).descending()
                    : Sort.by(sortBy).ascending();

            Pageable pageable = PageRequest.of(page, size, sort);
            Page<JobPosting> jobs = jobPostingRepository
                    .findBySourceSiteAndIsActiveTrue(siteName, pageable);

            return ResponseEntity.ok(jobs);

        } catch (Exception e) {
            log.error("사이트별 채용공고 조회 실패: {}", siteId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ===== 기존 API들 계속 =====

    // 크롤링 상태 확인 (기존 메소드 - 전체 통계)
    @GetMapping("/crawl/status")
    public ResponseEntity<Map<String, Object>> getCrawlingStatus() {
        long totalJobs = jobPostingRepository.countActiveJobs();

        LocalDateTime startOfDay = LocalDate.now().atStartOfDay();
        LocalDateTime endOfDay = startOfDay.plusDays(1);
        long todayJobs = jobPostingRepository.countTodayJobs(startOfDay, endOfDay);

        Map<String, Object> status = Map.of(
                "totalJobs", totalJobs,
                "todayJobs", todayJobs,
                "lastUpdate", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );

        return ResponseEntity.ok(status);
    }

    // 채용공고 통계 (기존 메소드)
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getJobStats() {
        long totalJobs = jobPostingRepository.countActiveJobs();

        List<Object[]> categoryStats = jobPostingRepository.getJobCountByCategory();
        List<Object[]> companyStats = jobPostingRepository.getTopCompanies();
        List<Object[]> locationStats = jobPostingRepository.getJobCountByLocation();

        Map<String, Object> stats = Map.of(
                "totalJobs", totalJobs,
                "categoryStats", categoryStats,
                "topCompanies", companyStats,
                "locationStats", locationStats
        );

        return ResponseEntity.ok(stats);
    }

    // 채용공고 삭제 (관리자용)
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        if (jobPostingRepository.existsById(id)) {
            jobPostingRepository.deleteById(id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    // 채용공고 비활성화
    @PutMapping("/{id}/deactivate")
    public ResponseEntity<JobPosting> deactivateJob(@PathVariable Long id) {
        Optional<JobPosting> jobOpt = jobPostingRepository.findById(id);
        if (jobOpt.isPresent()) {
            JobPosting job = jobOpt.get();
            job.setIsActive(false);
            JobPosting savedJob = jobPostingRepository.save(job);
            return ResponseEntity.ok(savedJob);
        }
        return ResponseEntity.notFound().build();
    }

    // ===== 헬퍼 메서드들 =====

    private String getSiteDescription(String siteId) {
        return switch (siteId) {
            case "saramin" -> "국내 대표 채용정보 사이트";
            case "jobkorea" -> "전문 채용정보 플랫폼";
            case "wanted" -> "개발자/디자이너 전문 채용 플랫폼";
            case "programmers" -> "프로그래밍 전문 채용 사이트";
            case "jumpit" -> "IT/개발자 전문 채용 플랫폼";
            default -> "채용정보 사이트";
        };
    }
}