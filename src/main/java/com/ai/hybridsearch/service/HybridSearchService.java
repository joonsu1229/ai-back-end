package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HybridSearchService {
    
    @Autowired
    private DocumentService documentService;    
    
    @Autowired
    private QueryBuilderService queryBuilderService;
    
    @Autowired
    private RerankerService rerankerService;

    @Autowired
    private EmbeddingService embeddingService; // LangChain4j용

    private final VectorSearchService vectorSearchService;

    public HybridSearchService(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    public List<SearchResult> hybridSearch(String query, String category, int limit) {
        // 1. 키워드 기반 검색
        String lexicalQuery = queryBuilderService.buildFullTextQuery(query);

        List<Document> lexicalDocs = (category != null && !category.isEmpty())
                ? documentService.findByFullTextSearchAndCategory(lexicalQuery, category, limit * 2)
                : documentService.findByFullTextSearch(lexicalQuery, limit * 2);

        List<SearchResult> lexicalResults = lexicalDocs.stream()
                .map(doc -> new SearchResult(doc, 1.0, "lexical"))
                .collect(Collectors.toList());

        float[] embedding = embeddingService.embed(query);
        List<Document> sementicDocs = (category != null && !category.isEmpty())
                ? vectorSearchService.searchByEmbeddingAndCategory(embedding, category, limit)
                : vectorSearchService.searchByEmbedding(embedding, limit);
        List<SearchResult> searchResults = sementicDocs.stream()
        .map(doc -> new SearchResult(doc, 1.0, "sementic"))
        .collect(Collectors.toList());

        // 3. 결과 병합
        List<SearchResult> combined = new ArrayList<>();
        combined.addAll(lexicalResults);
        combined.addAll(searchResults);

        // 4. 재정렬 (유사도 기준 reranking)
        return rerankerService.rerankWithCategoryBoost(combined, query, category, limit);
    }
    
    public List<SearchResult> sementicSearch(String query, String category, int limit) {
        float[] embedding = embeddingService.embed(query);
        List<Document> sementicDocs = (category != null && !category.isEmpty())
                ? vectorSearchService.searchByEmbeddingAndCategory(embedding, category, limit)
                : vectorSearchService.searchByEmbedding(embedding, limit);

        return sementicDocs.stream()
            .map(doc -> new SearchResult(doc, 1.0, "sementic"))
            .collect(Collectors.toList());
    }    
    
    public List<SearchResult> lexicalSearch(String query, String category, int limit) {
        String processedQuery = queryBuilderService.buildFullTextQuery(query);

        List<Document> documents;
        if (category != null && !category.isEmpty()) {
            documents = documentService.findByFullTextSearchAndCategory(processedQuery, category, limit);
        } else {
            documents = documentService.findByFullTextSearch(processedQuery, limit);
        }
        
        return documents.stream()
            .map(doc -> new SearchResult(doc, 1.0, "lexical"))
            .collect(Collectors.toList());
    }
    
    public List<SearchResult> searchWithBoolean(List<String> mustHave, List<String> shouldHave, 
                                               List<String> mustNotHave, String category, int limit) {
        String booleanQuery = queryBuilderService.buildBooleanQuery(mustHave, shouldHave, mustNotHave);
        return lexicalSearch(booleanQuery, category, limit);
    }
    
    public List<SearchResult> searchByCategory(String category, int limit) {
        List<Document> documents = documentService.findByCategory(category);
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
        List<SearchResult> exactResults = lexicalSearch(exactQuery, category, limit);
        exactResults.forEach(result -> {
            result.setScore(result.getScore() * 1.0); // 정확한 매칭에 높은 가중치
            result.setSearchType("exact");
        });

        // 2. 임베딩 기반 검색
        List<SearchResult> vectorResults = vectorSearchService.semanticSearch(query, category, limit * 2);
        vectorResults.forEach(result -> {
            result.setScore(result.getScore() * 1.0); // 정확한 매칭에 높은 가중치
            result.setSearchType("exact");
        });

        // 3. 결과 병합
        List<SearchResult> combined = new ArrayList<>();
        allResults.addAll(exactResults);
        allResults.addAll(vectorResults);

        
        // 구문 검색 (요청시)
        if (usePhrase) {
            String phraseQuery = queryBuilderService.buildPhraseQuery(query);
            List<SearchResult> phraseResults = lexicalSearch(phraseQuery, category, limit);
            phraseResults.forEach(result -> {
                result.setScore(result.getScore() * 0.9);
                result.setSearchType("phrase");
            });
            allResults.addAll(phraseResults);
        }
        
        // 퍼지 검색 (요청시)
        if (useFuzzy) {
            String fuzzyQuery = queryBuilderService.buildFuzzyQuery(query);
            List<SearchResult> fuzzyResults = lexicalSearch(fuzzyQuery, category, limit);
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