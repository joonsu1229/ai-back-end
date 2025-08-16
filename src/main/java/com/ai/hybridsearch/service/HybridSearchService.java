package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import java.util.List;

public interface HybridSearchService {
    List<SearchResult> hybridSearch(String query, String category, int limit);
    List<SearchResult> semanticSearch(String query, String category, int limit);
    List<SearchResult> lexicalSearch(String query, String category, int limit);
    List<SearchResult> searchWithBoolean(List<String> mustHave, List<String> shouldHave,
                                         List<String> mustNotHave, String category, int limit);
    List<SearchResult> searchByCategory(String category, int limit);
    List<SearchResult> advancedHybridSearch(String query, String category,
                                            boolean useFuzzy, boolean usePhrase, int limit);
}