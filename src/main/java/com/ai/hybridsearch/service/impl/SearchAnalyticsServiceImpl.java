package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.service.SearchAnalyticsService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SearchAnalyticsServiceImpl implements SearchAnalyticsService {

    private final Map<String, AtomicLong> queryCount = new ConcurrentHashMap<>();
    private final Map<String, LocalDateTime> lastSearchTime = new ConcurrentHashMap<>();

    public void recordSearch(String query, String category, int resultCount, long responseTime) {
        String key = category != null ? query + ":" + category : query;
        queryCount.computeIfAbsent(key, k -> new AtomicLong(0)).incrementAndGet();
        lastSearchTime.put(key, LocalDateTime.now());

        // 로깅 또는 메트릭 수집
        System.out.printf("Search recorded - Query: %s, Category: %s, Results: %d, Time: %dms%n",
            query, category, resultCount, responseTime);
    }

    public Map<String, Long> getPopularQueries(int limit) {
        return queryCount.entrySet().stream()
            .sorted(Map.Entry.<String, AtomicLong>comparingByValue((a, b) ->
                Long.compare(b.get(), a.get())))
            .limit(limit)
            .collect(java.util.stream.Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get(),
                (e1, e2) -> e1,
                java.util.LinkedHashMap::new
            ));
    }

    public long getTotalSearches() {
        return queryCount.values().stream()
            .mapToLong(AtomicLong::get)
            .sum();
    }
}