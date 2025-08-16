package com.ai.hybridsearch.service;

import java.util.Map;

public interface SearchAnalyticsService {
    void recordSearch(String query, String category, int resultCount, long responseTime);
    Map<String, Long> getPopularQueries(int limit);
    long getTotalSearches();
}