package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.repository.JobPostingRepository;
import com.ai.hybridsearch.service.EmbeddingService;
import com.ai.hybridsearch.service.JobSearchService;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(readOnly = true)
public class JobSearchServiceImpl implements JobSearchService {

    @Autowired
    private JobPostingRepository jobPostingRepository;

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private RerankerServiceImpl rerankerServiceImpl;

    /**
     * AI 기반 유사도 검색
     */
    public List<JobPosting> searchSimilarJobs(String query, int limit) {
        try {
            // 쿼리 임베딩 생성
            float[] queryEmbedding = embeddingService.embed(query);
            String vectorStr = floatArrayToVectorString(queryEmbedding);

            // 유사도 검색 실행
            List<Object[]> results = jobPostingRepository.findSimilarJobs(vectorStr, limit);

            List<JobPosting> jobs = new ArrayList<>();
            for (Object[] result : results) {
                JobPosting job = mapToJobPosting(result);
                if (job != null) {
                    jobs.add(job);
                }
            }

            log.info("유사도 검색 완료: 쿼리='{}', 결과={}개", query, jobs.size());
            return jobs;

        } catch (Exception e) {
            log.error("유사도 검색 실패: {}", query, e);
            return new ArrayList<>();
        }
    }

    /**
     * 하이브리드 검색 (키워드 + 유사도)
     */
    public List<JobPosting> hybridSearch(String query, int limit) {
        try {
            // 쿼리 임베딩 생성
            float[] queryEmbedding = embeddingService.embed(query);
            String vectorStr = floatArrayToVectorString(queryEmbedding);

            // 하이브리드 검색 실행
            List<Object[]> results = jobPostingRepository.hybridSearch(vectorStr, query, limit);

            List<JobPosting> jobs = new ArrayList<>();
            for (Object[] result : results) {
                JobPosting job = mapToJobPosting(result);
                if (job != null) {
                    jobs.add(job);
                }
            }

            log.info("하이브리드 검색 완료: 쿼리='{}', 결과={}개", query, jobs.size());

            // 4. 재정렬 (유사도 기준 reranking)
            List<JobPosting> finalResults = new ArrayList<>(jobs);
            return rerankerServiceImpl.rerank(finalResults, query, limit);

        } catch (Exception e) {
            log.error("하이브리드 검색 실패: {}", query, e);
            // 실패 시 일반 키워드 검색으로 fallback
            return fallbackKeywordSearch(query, limit);
        }
    }

    /**
     * 필터 기반 검색
     */
    public Page<JobPosting> searchWithFilters(
            String keyword,
            String jobCategory,
            String location,
            String sourceSite,
            String experienceLevel,
            Pageable pageable) {

        return jobPostingRepository.findWithFilters(
            jobCategory, location, sourceSite, experienceLevel, keyword, pageable);
    }

    /**
     * 추천 채용공고 (사용자 프로필 기반)
     */
    public List<JobPosting> getRecommendedJobs(String userProfile, int limit) {
        try {
            // 사용자 프로필을 기반으로 유사한 채용공고 검색
            return searchSimilarJobs(userProfile, limit);
        } catch (Exception e) {
            log.error("추천 채용공고 조회 실패", e);
            return jobPostingRepository.findByIsActiveTrueOrderByCreatedAtDesc()
                .stream()
                .limit(limit)
                .collect(Collectors.toList());
        }
    }

    /**
     * 트렌딩 채용공고 (최근 인기)
     */
    public List<JobPosting> getTrendingJobs(int days, int limit) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return jobPostingRepository.findRecentlyUpdated(since)
            .stream()
            .limit(limit)
            .collect(Collectors.toList());
    }

    /**
     * 회사별 채용공고 통계
     */
    public Map<String, Long> getCompanyJobCounts() {
        List<Object[]> results = jobPostingRepository.getTopCompanies();
        return results.stream()
            .collect(Collectors.toMap(
                result -> (String) result[0],
                result -> ((Number) result[1]).longValue()
            ));
    }

    /**
     * 직무 카테고리별 통계
     */
    public Map<String, Long> getCategoryJobCounts() {
        List<Object[]> results = jobPostingRepository.getJobCountByCategory();
        return results.stream()
            .collect(Collectors.toMap(
                result -> (String) result[0],
                result -> ((Number) result[1]).longValue()
            ));
    }

    /**
     * 지역별 채용공고 통계
     */
    public Map<String, Long> getLocationJobCounts() {
        List<Object[]> results = jobPostingRepository.getJobCountByLocation();
        return results.stream()
            .collect(Collectors.toMap(
                result -> (String) result[0],
                result -> ((Number) result[1]).longValue()
            ));
    }

    /**
     * 마감 임박 채용공고
     */
    public List<JobPosting> getJobsNearDeadline(int days) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime deadline = now.plusDays(days);
        return jobPostingRepository.findJobsDeadlineBetween(now, deadline);
    }

    /**
     * 신규 채용공고
     */
    public List<JobPosting> getRecentJobs(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return jobPostingRepository.findByCreatedAtAfterAndIsActiveTrue(since);
    }

    /**
     * 인기 회사 목록
     */
    public List<Object[]> getPopularCompanies(int minJobs) {
        return jobPostingRepository.findPopularCompanies(minJobs);
    }

    /**
     * 검색 제안 (자동완성용)
     */
    public List<String> getSearchSuggestions(String prefix, int limit) {
        try {
            // 회사명에서 제안
            List<String> companySuggestions = entityManager
                .createQuery("SELECT DISTINCT j.company FROM JobPosting j " +
                           "WHERE j.isActive = true AND LOWER(j.company) LIKE LOWER(:prefix) " +
                           "ORDER BY j.company", String.class)
                .setParameter("prefix", prefix + "%")
                .setMaxResults(limit / 2)
                .getResultList();

            // 직무 카테고리에서 제안
            List<String> categorySuggestions = entityManager
                .createQuery("SELECT DISTINCT j.jobCategory FROM JobPosting j " +
                           "WHERE j.isActive = true AND j.jobCategory IS NOT NULL " +
                           "AND LOWER(j.jobCategory) LIKE LOWER(:prefix) " +
                           "ORDER BY j.jobCategory", String.class)
                .setParameter("prefix", prefix + "%")
                .setMaxResults(limit / 2)
                .getResultList();

            List<String> suggestions = new ArrayList<>();
            suggestions.addAll(companySuggestions);
            suggestions.addAll(categorySuggestions);

            return suggestions.stream()
                .distinct()
                .limit(limit)
                .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("검색 제안 조회 실패", e);
            return new ArrayList<>();
        }
    }

    /**
     * 관련 기술/키워드 추출
     */
    public List<String> extractRelatedKeywords(String jobDescription) {
        try {
            // 간단한 기술 키워드 추출 (실제로는 더 정교한 NLP 처리 필요)
            List<String> keywords = new ArrayList<>();
            String[] commonTech = {
                "Java", "Python", "JavaScript", "React", "Vue", "Angular", "Spring",
                "Node.js", "Docker", "Kubernetes", "AWS", "Azure", "GCP",
                "MySQL", "PostgreSQL", "MongoDB", "Redis", "Elasticsearch",
                "Git", "Jenkins", "CI/CD", "REST API", "GraphQL", "MSA"
            };

            String description = jobDescription.toLowerCase();
            for (String tech : commonTech) {
                if (description.contains(tech.toLowerCase())) {
                    keywords.add(tech);
                }
            }

            return keywords;
        } catch (Exception e) {
            log.error("키워드 추출 실패", e);
            return new ArrayList<>();
        }
    }

    // Private helper methods

    private List<JobPosting> fallbackKeywordSearch(String query, int limit) {
        try {
            List<JobPosting> results = entityManager
                .createQuery("SELECT j FROM JobPosting j WHERE j.isActive = true " +
                           "AND (LOWER(j.title) LIKE LOWER(:keyword) " +
                           "OR LOWER(j.company) LIKE LOWER(:keyword) " +
                           "OR LOWER(j.description) LIKE LOWER(:keyword)) " +
                           "ORDER BY j.createdAt DESC", JobPosting.class)
                .setParameter("keyword", "%" + query + "%")
                .setMaxResults(limit)
                .getResultList();

            return results;
        } catch (Exception e) {
            log.error("Fallback 검색 실패", e);
            return new ArrayList<>();
        }
    }

    private JobPosting mapToJobPosting(Object[] result) {
        try {
            JobPosting job = new JobPosting();

            // Native query 결과를 JobPosting 객체로 매핑
            // 실제 컬럼 순서에 맞게 수정 필요
            job.setId(((Number) result[0]).longValue());
            job.setTitle((String) result[16]);
            job.setCompany((String) result[2]);
            job.setDescription((String) result[5]);
            job.setLocation((String) result[11]);
            job.setExperienceLevel((String) result[8]);
            job.setSalary((String) result[13]);
            job.setEmploymentType((String) result[7]);
            job.setJobCategory((String) result[10]);
            job.setRequirements((String) result[12]);
            job.setBenefits((String) result[1]);
            job.setSourceSite((String) result[14]);
            job.setSourceUrl((String) result[15]);

            // timestamp 처리
            if (result[4] != null) {
                job.setCreatedAt(((Timestamp) result[4]).toLocalDateTime());
            }
            job.setIsActive((Boolean) result[9]);
            job.setCreatedAt(((Timestamp) result[3]).toLocalDateTime());
            job.setCreatedAt(((Timestamp) result[17]).toLocalDateTime());

            // similarity score는 별도 처리 가능 (필요시)

            return job;
        } catch (Exception e) {
            log.error("JobPosting 매핑 실패", e);
            return null;
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