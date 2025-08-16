package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.JobPosting;
import java.util.List;

public interface RerankerService {
    List<SearchResult> rerankWithCategoryBoost(List<SearchResult> results, String query,
                                               String preferredCategory, int topK);
    List<JobPosting> rerank(List<JobPosting> results, String query, int topK);
}
