package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    @Autowired
    private EmbeddingService embeddingService; // LangChain4j용

    @Autowired
    private EntityManager entityManager;

    public List<SearchResult> semanticSearch(String query, String category, int limit) {
        float[] embedding = embeddingService.embed(query);

        List<Document> documents = (category != null && !category.isEmpty())
                ? this.searchByEmbeddingAndCategory(embedding, category, limit)
                : this.searchByEmbedding(embedding, limit);

        return documents.stream()
                .map(doc -> new SearchResult(doc, 0.8, "embedding"))
                .collect(Collectors.toList());
    }

    // Vector similarity search
    @SuppressWarnings("unchecked")
    public List<Document> searchByEmbedding(float[] embedding, int limit) {
        String vectorStr = floatArrayToVectorString(embedding);

        Query nativeQuery = (Query) entityManager.createNativeQuery("""
            SELECT *, 1 - (embedding <=> CAST(?1 AS vector)) AS similarity
            FROM documents
            ORDER BY embedding <=> CAST(?1 AS vector)
            LIMIT ?2
            """, Document.class);
        nativeQuery.setParameter(1, vectorStr);
        nativeQuery.setParameter(2, limit);
        return nativeQuery.getResultList();
    }

    // Vector similarity search with category filter
    @SuppressWarnings("unchecked")
    public List<Document> searchByEmbeddingAndCategory(float[] embedding, String category, int limit) {
        String vectorStr = floatArrayToVectorString(embedding);

        Query nativeQuery = entityManager.createNativeQuery("""
            SELECT *, 1 - (embedding <=> CAST(?1 AS vector)) AS similarity
            FROM documents
            WHERE category = ?2
            ORDER BY embedding <=> CAST(?1 AS vector)
            LIMIT ?3
            """, Document.class);
        nativeQuery.setParameter(1, vectorStr);
        nativeQuery.setParameter(2, category);
        nativeQuery.setParameter(3, limit);
        return nativeQuery.getResultList();
    }

    // Text를 embedding으로 변환해서 검색하는 편의 메서드
    public List<Document> searchByText(String text, int limit) {
        float[] embedding = embeddingService.embed(text);
        return searchByEmbedding(embedding, limit);
    }

    // Text를 embedding으로 변환해서 카테고리와 함께 검색하는 편의 메서드
    public List<Document> searchByTextAndCategory(String text, String category, int limit) {
        float[] embedding = embeddingService.embed(text);
        return searchByEmbeddingAndCategory(embedding, category, limit);
    }

    private String floatArrayToVectorString(float[] array) {
        if (array == null || array.length == 0) {
            return "[]";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

}
