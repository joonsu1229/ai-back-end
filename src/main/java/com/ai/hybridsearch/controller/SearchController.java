package com.ai.hybridsearch.controller;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.service.impl.HybridSearchServiceImpl;
import com.ai.hybridsearch.service.impl.SearchAnalyticsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/search")
@CrossOrigin(origins = "*")
public class SearchController {
    
    @Autowired
    private HybridSearchServiceImpl hybridSearchServiceImpl;

    @Autowired
    private SearchAnalyticsServiceImpl searchAnalyticsServiceImpl;
    
    @GetMapping("/lexical")
    public ResponseEntity<List<SearchResult>> lexicalSearch(
            @RequestParam String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "10") int limit) {

        // 순수 키워드 검색 - 빠르고 정확한 매칭 위주
        List<SearchResult> results = hybridSearchServiceImpl.lexicalSearch(query, category, limit);
        searchAnalyticsServiceImpl.recordSearch(query, category, results.size(), 10); // 0은 응답 시간 예시

        return ResponseEntity.ok(results);
    }

    @GetMapping("/hybrid")
    public ResponseEntity<List<SearchResult>> hybridSearch(
            @RequestParam String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "10") int limit) {

        // 하이브리드 검색 - 의미적 유사도까지 고려한 고품질 결과
        List<SearchResult> results = hybridSearchServiceImpl.hybridSearch(query, category, limit);
        searchAnalyticsServiceImpl.recordSearch(query, category, results.size(), 10); // 0은 응답 시간 예시

        return ResponseEntity.ok(results);
    }

    @GetMapping("/semantic")
    public ResponseEntity<List<SearchResult>> semanticSearch(
            @RequestParam String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "10") int limit) {

        // 하이브리드 검색 - 의미적 유사도까지 고려한 고품질 결과
        List<SearchResult> results = hybridSearchServiceImpl.semanticSearch(query, category, limit);
        searchAnalyticsServiceImpl.recordSearch(query, category, results.size(), 10); // 0은 응답 시간 예시

        return ResponseEntity.ok(results);
    }
    
    @PostMapping("/boolean")
    public ResponseEntity<List<SearchResult>> booleanSearch(
            @RequestBody Map<String, Object> searchRequest) {
        
        @SuppressWarnings("unchecked")
        List<String> mustHave = (List<String>) searchRequest.get("mustHave");
        @SuppressWarnings("unchecked")
        List<String> shouldHave = (List<String>) searchRequest.get("shouldHave");
        @SuppressWarnings("unchecked")
        List<String> mustNotHave = (List<String>) searchRequest.get("mustNotHave");
        String category = (String) searchRequest.get("category");
        Integer limit = (Integer) searchRequest.getOrDefault("limit", 10);
        
        List<SearchResult> results = hybridSearchServiceImpl.searchWithBoolean(
            mustHave, shouldHave, mustNotHave, category, limit
        );
        return ResponseEntity.ok(results);
    }
    
    @GetMapping("/advanced")
    public ResponseEntity<List<SearchResult>> advancedSearch(
            @RequestParam String query,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "false") boolean useFuzzy,
            @RequestParam(defaultValue = "false") boolean usePhrase,
            @RequestParam(defaultValue = "10") int limit) {
        
        List<SearchResult> results = hybridSearchServiceImpl.advancedHybridSearch(
            query, category, useFuzzy, usePhrase, limit
        );
        searchAnalyticsServiceImpl.recordSearch(query, category, results.size(), 10); // 0은 응답 시간 예시

        return ResponseEntity.ok(results);
    }
    
    @GetMapping("/category/{category}")
    public ResponseEntity<List<SearchResult>> searchByCategory(
            @PathVariable String category,
            @RequestParam(defaultValue = "10") int limit) {
        
        List<SearchResult> results = hybridSearchServiceImpl.searchByCategory(category, limit);
        return ResponseEntity.ok(results);
    }
}