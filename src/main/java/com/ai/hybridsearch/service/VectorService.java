package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.Document;
import org.springframework.http.ResponseEntity;
import java.util.List;

public interface VectorService {
    List<Document> findByFullTextSearch(String query, int limit);
    List<Document> findByFullTextSearchAndCategory(String query, String category, int limit);
    void updateEmbedding(String embedding, Long id);
    ResponseEntity<Document> createDocument(Document document);
    ResponseEntity<Document> updateDocument(Long id, Document document);
}