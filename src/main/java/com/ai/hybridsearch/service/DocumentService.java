package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.Document;
import org.springframework.http.ResponseEntity;
import java.util.List;

public interface DocumentService {
    void updateEmbedding(String embedding, Long id);
    ResponseEntity<Document> createDocument(Document document);
    ResponseEntity<Document> updateDocument(Long id, Document document);
    List<Document> findByCategory(String category);
}