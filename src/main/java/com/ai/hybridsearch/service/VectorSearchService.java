package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private EntityManager entityManager;

    public List<SearchResult> semanticSearch(String query, String category, int limit) {
        float[] embedding = embeddingService.embed(query);

        List<Document> documents = (category != null && !category.isEmpty())
                ? this.searchByEmbeddingAndCategory(embedding, category, limit)
                : this.searchByEmbedding(embedding, limit);

        return documents.stream()
                .map(doc -> new SearchResult(doc, doc.getScore(), "semantic"))  // "semantic" 타입명 유지
                .collect(Collectors.toList());
    }

    // Vector similarity search
    @SuppressWarnings("unchecked")
    public List<Document> searchByEmbedding(float[] embedding, int limit) {
        String vectorStr = floatArrayToVectorString(embedding);

        Query nativeQuery = entityManager.createNativeQuery("""
            SELECT *, 1 - (embedding <=> CAST(?1 AS vector)) AS similarity
            FROM documents
            ORDER BY embedding <=> CAST(?1 AS vector)
            LIMIT ?2
            """);
        nativeQuery.setParameter(1, vectorStr);
        nativeQuery.setParameter(2, limit);

        List<Object[]> results = nativeQuery.getResultList();

        return results.stream().map(row -> {
            Document doc = mapRowToDocument(row);
            // similarity 점수를 Document의 score 필드에 설정
            float score = ((Number) row[row.length - 1]).floatValue();
            doc.setScore(score);
            return doc;
        }).collect(Collectors.toList());
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
            """);
        nativeQuery.setParameter(1, vectorStr);
        nativeQuery.setParameter(2, category);
        nativeQuery.setParameter(3, limit);

        List<Object[]> results = nativeQuery.getResultList();

        return results.stream().map(row -> {
            Document doc = mapRowToDocument(row);
            // similarity 점수를 Document의 score 필드에 설정
            float score = ((Number) row[row.length - 1]).floatValue();
            doc.setScore(score);
            return doc;
        }).collect(Collectors.toList());
    }

    // Object[] 배열을 Document로 변환하는 헬퍼 메서드
    private Document mapRowToDocument(Object[] row) {
        Document doc = new Document();
        doc.setId((Long) row[0]);                                    // id
        doc.setTitle((String) row[5]);                               // title
        doc.setContent((String) row[2]);                             // content
        doc.setCategory((String) row[1]);                            // category

        // created_at, updated_at 처리 (null 체크 포함)
        if (row[4] != null) {
            doc.setCreatedAt(((Timestamp) row[3]).toLocalDateTime());
        }
        if (row[5] != null) {
            doc.setUpdatedAt(((Timestamp) row[6]).toLocalDateTime());
        }

        doc.setSearchVector(row[4].toString());                        // search_vector
        // row[7]은 embedding (보통 매핑하지 않음)
        // row[8]이 similarity (별도로 처리)

        return doc;
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