package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import com.ai.hybridsearch.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {
    
    @Autowired
    private DocumentRepository documentRepository;
    
    @Autowired
    private QueryBuilderService queryBuilderService;
    
    @Autowired
    private RerankerService rerankerService;
    
    public List<SearchResult> hybridSearch(String query, String category, int limit) {
        // 1. Full-text search
        List<SearchResult> keywordResults = performKeywordSearch(query, category, limit * 2);
        
        // 2. Rerank with semantic similarity
        List<SearchResult> rerankedResults = rerankerService.rerankWithCategoryBoost(
            keywordResults, query, category, limit
        );
        
        return rerankedResults;
    }
    
    public List<SearchResult> performKeywordSearch(String query, String category, int limit) {
        String processedQuery = queryBuilderService.buildFullTextQuery(query);
        
        List<Document> documents;
        if (category != null && !category.isEmpty()) {
            documents = documentRepository.findByFullTextSearchAndCategory(processedQuery, category, limit);
        } else {
            documents = documentRepository.findByFullTextSearch(processedQuery, limit);
        }
        
        return documents.stream()
            .map(doc -> new SearchResult(doc, 1.0, "keyword"))
            .collect(Collectors.toList());
    }
    
    public List<SearchResult> searchWithBoolean(List<String> mustHave, List<String> shouldHave, 
                                               List<String> mustNotHave, String category, int limit) {
        String booleanQuery = queryBuilderService.buildBooleanQuery(mustHave, shouldHave, mustNotHave);
        return performKeywordSearch(booleanQuery, category, limit);
    }
    
    public List<SearchResult> searchByCategory(String category, int limit) {
        List<Document> documents = documentRepository.findByCategory(category);
        return documents.stream()
            .limit(limit)
            .map(doc -> new SearchResult(doc, 1.0, "category"))
            .collect(Collectors.toList());
    }
    
    public List<SearchResult> advancedHybridSearch(String query, String category, 
                                                  boolean useFuzzy, boolean usePhrase, int limit) {
        List<SearchResult> allResults = new ArrayList<>();
        
        // 정확한 매칭
        String exactQuery = queryBuilderService.buildFullTextQuery(query);
        List<SearchResult> exactResults = performKeywordSearch(exactQuery, category, limit);
        exactResults.forEach(result -> {
            result.setScore(result.getScore() * 1.0); // 정확한 매칭에 높은 가중치
            result.setSearchType("exact");
        });
        allResults.addAll(exactResults);
        
        // 구문 검색 (요청시)
        if (usePhrase) {
            String phraseQuery = queryBuilderService.buildPhraseQuery(query);
            List<SearchResult> phraseResults = performKeywordSearch(phraseQuery, category, limit);
            phraseResults.forEach(result -> {
                result.setScore(result.getScore() * 0.9);
                result.setSearchType("phrase");
            });
            allResults.addAll(phraseResults);
        }
        
        // 퍼지 검색 (요청시)
        if (useFuzzy) {
            String fuzzyQuery = queryBuilderService.buildFuzzyQuery(query);
            List<SearchResult> fuzzyResults = performKeywordSearch(fuzzyQuery, category, limit);
            fuzzyResults.forEach(result -> {
                result.setScore(result.getScore() * 0.7);
                result.setSearchType("fuzzy");
            });
            allResults.addAll(fuzzyResults);
        }
        
        // 중복 제거 및 재랭킹
        Map<Long, SearchResult> uniqueResults = new HashMap<>();
        for (SearchResult result : allResults) {
            Long docId = result.getDocument().getId();
            if (!uniqueResults.containsKey(docId) || 
                uniqueResults.get(docId).getScore() < result.getScore()) {
                uniqueResults.put(docId, result);
            }
        }
        
        List<SearchResult> finalResults = new ArrayList<>(uniqueResults.values());
        return rerankerService.rerankWithCategoryBoost(finalResults, query, category, limit);
    }
}