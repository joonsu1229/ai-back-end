package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import com.ai.hybridsearch.service.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class HybridSearchServiceImpl implements HybridSearchService {
    
    @Autowired
    private DocumentServiceImpl documentService;
    
    @Autowired
    private QueryBuilderServiceImpl queryBuilderServiceImpl;
    
    @Autowired
    private RerankerServiceImpl rerankerServiceImpl;

    @Autowired
    private EmbeddingService embeddingService;

    private final VectorSearchServiceImpl vectorSearchServiceImpl;

    public HybridSearchServiceImpl(VectorSearchServiceImpl vectorSearchServiceImpl) {
        this.vectorSearchServiceImpl = vectorSearchServiceImpl;
    }

    public List<SearchResult> hybridSearch(String query, String category, int limit) {
        List<SearchResult> allResults = new ArrayList<>();

        // 1. 어휘적 검색 (Lexical Search)
        String lexicalQuery = queryBuilderServiceImpl.buildFullTextQuery(query);
        List<SearchResult> lexicalResults = lexicalSearch(lexicalQuery, category, limit);

        // 2. 의미적 검색 (Semantic Search)
        List<SearchResult> semanticResults = vectorSearchServiceImpl.semanticSearch(query, category, limit * 2);

        // 3. 결과 병합 및 중복 제거
        Map<Long, SearchResult> combinedResults = new HashMap<>();

        // 어휘적 결과 추가
        for (SearchResult result : lexicalResults) {
            Long docId = result.getDocument().getId();
            combinedResults.put(docId, result);
        }

        // 의미적 결과와 결합 (하이브리드 점수 계산)
        for (SearchResult semanticResult : semanticResults) {
            Long docId = semanticResult.getDocument().getId();
            SearchResult lexicalResult = combinedResults.get(docId);

            if (lexicalResult != null) {
                // 두 검색에서 모두 발견된 문서 - 하이브리드 점수 계산
                float hybridScore = (float) (lexicalResult.getScore() + semanticResult.getScore());
                SearchResult hybridResult = new SearchResult(
                    semanticResult.getDocument(),
                    hybridScore,
                    "hybrid"
                );
                combinedResults.put(docId, hybridResult);
            } else {
                // 의미적 검색에서만 발견된 문서
                combinedResults.put(docId, semanticResult);
            }
        }

        allResults.addAll(combinedResults.values());

        // 4. 중복 제거 및 최고 점수 유지
        Map<Long, SearchResult> uniqueResults = new HashMap<>();
        for (SearchResult result : allResults) {
            Long docId = result.getDocument().getId();
            if (!uniqueResults.containsKey(docId) ||
                uniqueResults.get(docId).getScore() < result.getScore()) {
                uniqueResults.put(docId, result);
            }
        }

        // 4. 재정렬 (유사도 기준 reranking)
        List<SearchResult> finalResults = new ArrayList<>(uniqueResults.values());
        return rerankerServiceImpl.rerankWithCategoryBoost(finalResults, query, category, limit);
    }

    public List<SearchResult> semanticSearch(String query, String category, int limit) {
        return vectorSearchServiceImpl.semanticSearch(query, category, limit);
    }

    public List<SearchResult> lexicalSearch(String query, String category, int limit) {
        String processedQuery = queryBuilderServiceImpl.buildFullTextQuery(query);

        List<Document> documents;
        if (category != null && !category.isEmpty()) {
            documents = vectorSearchServiceImpl.findByFullTextSearchAndCategory(processedQuery, category, limit);
        } else {
            documents = vectorSearchServiceImpl.findByFullTextSearch(processedQuery, limit);
        }

        return documents.stream()
            .map(doc -> new SearchResult(doc, doc.getScore(), "lexical"))
            .collect(Collectors.toList());
    }

    public List<SearchResult> searchWithBoolean(List<String> mustHave, List<String> shouldHave,
                                               List<String> mustNotHave, String category, int limit) {
        String booleanQuery = queryBuilderServiceImpl.buildBooleanQuery(mustHave, shouldHave, mustNotHave);
        return lexicalSearch(booleanQuery, category, limit);
    }

    public List<SearchResult> searchByCategory(String category, int limit) {
        List<Document> documents = documentService.findByCategory(category);
        return documents.stream()
            .limit(limit)
            .map(doc -> new SearchResult(doc, 1.0f, "category"))
            .collect(Collectors.toList());
    }

    public List<SearchResult> advancedHybridSearch(String query, String category,
                                                  boolean useFuzzy, boolean usePhrase, int limit) {
        List<SearchResult> allResults = new ArrayList<>();

        // 1. 기본 하이브리드 검색
        List<SearchResult> baseResults = hybridSearch(query, category, limit * 2);
        allResults.addAll(baseResults);

        // 2. 구문 검색 (요청시)
        if (usePhrase) {
            String phraseQuery = queryBuilderServiceImpl.buildPhraseQuery(query);
            List<SearchResult> phraseResults = lexicalSearch(phraseQuery, category, limit);
            phraseResults.forEach(result -> {
                result.setScore(result.getScore() * 0.9f);
                result.setSearchType("phrase");
            });
            allResults.addAll(phraseResults);
        }

        // 3. 퍼지 검색 (요청시)
        if (useFuzzy) {
            String fuzzyQuery = queryBuilderServiceImpl.buildFuzzyQuery(query);
            List<SearchResult> fuzzyResults = lexicalSearch(fuzzyQuery, category, limit);
            fuzzyResults.forEach(result -> {
                result.setScore(result.getScore() * 0.7f);
                result.setSearchType("fuzzy");
            });
            allResults.addAll(fuzzyResults);
        }

        // 4. 중복 제거 및 최고 점수 유지
        Map<Long, SearchResult> uniqueResults = new HashMap<>();
        for (SearchResult result : allResults) {
            Long docId = result.getDocument().getId();
            if (!uniqueResults.containsKey(docId) || 
                uniqueResults.get(docId).getScore() < result.getScore()) {
                uniqueResults.put(docId, result);
            }
        }
        
        List<SearchResult> finalResults = new ArrayList<>(uniqueResults.values());
        return rerankerServiceImpl.rerankWithCategoryBoost(finalResults, query, category, limit);
    }
}