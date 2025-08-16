package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import com.ai.hybridsearch.repository.VectorSearchRepository;
import com.ai.hybridsearch.service.EmbeddingService;
import com.ai.hybridsearch.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class VectorSearchServiceImpl implements VectorSearchService {

    @Autowired
    private EmbeddingService embeddingService;

    @Autowired
    private VectorSearchRepository vectorSearchRepository;

    public List<SearchResult> semanticSearch(String query, String category, int limit) {
        float[] embedding = embeddingService.embed(query);
        String vectorStr = toVectorString(embedding);

        List<Object[]> results;
        if (StringUtils.hasText(category)) {
            results = vectorSearchRepository.searchByEmbeddingAndCategory(vectorStr, category, limit);
        } else {
            results = vectorSearchRepository.searchByEmbedding(vectorStr, limit);
        }

        List<Document> documents = mapToDocumentsWithScore(results);
        return documents.stream()
                .map(doc -> new SearchResult(doc, doc.getScore(), "semantic"))
                .collect(Collectors.toList());
    }

    public List<Document> findByFullTextSearch(String searchQuery, int limit) {
        List<Object[]> results = vectorSearchRepository.findByFullTextSearch(searchQuery, limit);
        return mapToDocumentsWithScore(results);
    }

    public List<Document> findByFullTextSearchAndCategory(String searchQuery, String category, int limit) {
        List<Object[]> results = vectorSearchRepository.findByFullTextSearchAndCategory(searchQuery, category, limit);
        return mapToDocumentsWithScore(results);
    }

    // Private helper methods

    private String toVectorString(float[] array) {
        if (array == null || array.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < array.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private List<Document> mapToDocumentsWithScore(List<Object[]> results) {
        return results.stream()
                .map(this::mapRowToDocumentWithScore)
                .sorted((r1, r2) -> Double.compare(r2.getScore(), r1.getScore()))
                .collect(Collectors.toList());
    }

    private Document mapRowToDocumentWithScore(Object[] row) {
        Document document = mapRowToDocument(row);
        Number scoreNum = (Number) row[row.length - 1];
        document.setScore(scoreNum != null ? scoreNum.floatValue() : 0f);
        return document;
    }

    private Document mapRowToDocument(Object[] row) {
        Document document = new Document();
        // 인덱스는 쿼리 컬럼 순서에 맞춰 수정 필요
        document.setId(row[0] instanceof Number ? ((Number) row[0]).longValue() : null);
        document.setCategory(row[1] != null ? row[1].toString() : null);
        document.setContent(row[2] != null ? row[2].toString() : null);
        document.setTitle(row.length > 5 && row[5] != null ? row[5].toString() : null);

        if (row.length > 3 && row[3] instanceof Timestamp) {
            document.setCreatedAt(((Timestamp) row[3]).toLocalDateTime());
        }
        if (row.length > 6 && row[6] instanceof Timestamp) {
            document.setUpdatedAt(((Timestamp) row[6]).toLocalDateTime());
        }
        if (row.length > 4 && row[4] != null) {
            document.setSearchVector(row[4].toString());
        }
        return document;
    }
}
