package com.ai.hybridsearch.controller;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.ai.hybridsearch.service.JobCrawlingService;
import com.ai.hybridsearch.service.JobSearchService;
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
    private JobCrawlingService jobCrawlingService;

    @Autowired
    private JobSearchService jobSearchService;

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

        List<JobPosting> similarJobs = jobSearchService.searchSimilarJobs(query, limit);
        return ResponseEntity.ok(similarJobs);
    }

    // 하이브리드 검색 (키워드 + 유사도)
    @PostMapping("/search/hybrid")
    public ResponseEntity<List<JobPosting>> hybridSearch(
            @RequestBody Map<String, Object> request) {

        String query = (String) request.get("query");
        Integer limit = (Integer) request.getOrDefault("limit", 20);

        List<JobPosting> results = jobSearchService.hybridSearch(query, limit);
        return ResponseEntity.ok(results);
    }

    // 수동 크롤링 실행
    @PostMapping("/crawl")
    public ResponseEntity<String> startCrawling() {
        try {
            CompletableFuture<String> result = jobCrawlingService.startManualCrawling();
            return ResponseEntity.ok("크롤링이 시작되었습니다. 백그라운드에서 실행됩니다.");
        } catch (Exception e) {
            log.error("크롤링 시작 실패", e);
            return ResponseEntity.internalServerError()
                .body("크롤링 시작에 실패했습니다: " + e.getMessage());
        }
    }

    // 크롤링 상태 확인
    @GetMapping("/crawl/status")
    public ResponseEntity<Map<String, Object>> getCrawlingStatus() {
        long totalJobs = jobPostingRepository.count();

        // 오늘 날짜 범위 계산
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

    // 채용공고 통계
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getJobStats() {
        long totalJobs = jobPostingRepository.count();

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
}