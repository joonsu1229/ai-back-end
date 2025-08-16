package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import java.util.List;

public interface VectorSearchService {
    List<SearchResult> semanticSearch(String query, String category, int limit);
    List<Document> findByFullTextSearch(String searchQuery, int limit);
    List<Document> findByFullTextSearchAndCategory(String searchQuery, String category, int limit);
}