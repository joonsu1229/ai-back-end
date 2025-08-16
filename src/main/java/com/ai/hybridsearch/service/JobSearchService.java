package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.JobPosting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Map;

public interface JobSearchService {
    List<JobPosting> searchSimilarJobs(String query, int limit);
    List<JobPosting> hybridSearch(String query, int limit);
    Page<JobPosting> searchWithFilters(String keyword, String jobCategory, String location,
                                       String sourceSite, String experienceLevel, Pageable pageable);
    List<JobPosting> getRecommendedJobs(String userProfile, int limit);
    List<JobPosting> getTrendingJobs(int days, int limit);
    Map<String, Long> getCompanyJobCounts();
    Map<String, Long> getCategoryJobCounts();
    Map<String, Long> getLocationJobCounts();
    List<JobPosting> getJobsNearDeadline(int days);
    List<JobPosting> getRecentJobs(int days);
    List<Object[]> getPopularCompanies(int minJobs);
    List<String> getSearchSuggestions(String prefix, int limit);
    List<String> extractRelatedKeywords(String jobDescription);
}