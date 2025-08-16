package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.entity.Document;
import com.ai.hybridsearch.repository.DocumentRepository;
import com.ai.hybridsearch.service.EmbeddingService;
import com.ai.hybridsearch.service.VectorService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class VectorServiceImpl implements VectorService {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private EmbeddingService embeddingService;

    // Full text search
    @SuppressWarnings("unchecked")
    public List<Document> findByFullTextSearch(String query, int limit) {
        Query nativeQuery = entityManager.createNativeQuery("""
            SELECT d.*, ts_rank(d.search_vector, plainto_tsquery(?1)) as rank
            FROM documents d
            WHERE d.search_vector @@ plainto_tsquery(?1)
            ORDER BY rank DESC
            LIMIT ?2
            """, Document.class);
        nativeQuery.setParameter(1, query);
        nativeQuery.setParameter(2, limit);
        return nativeQuery.getResultList();
    }

    // Combined text and category search
    @SuppressWarnings("unchecked")
    public List<Document> findByFullTextSearchAndCategory(String query, String category, int limit) {
        Query nativeQuery = entityManager.createNativeQuery("""
            SELECT d.*, ts_rank(d.search_vector, plainto_tsquery(?1)) as rank
            FROM documents d
            WHERE d.search_vector @@ plainto_tsquery(?1)
            AND (?2 IS NULL OR d.category = ?2)
            ORDER BY rank DESC
            LIMIT ?3
            """, Document.class);
        nativeQuery.setParameter(1, query);
        nativeQuery.setParameter(2, category);
        nativeQuery.setParameter(3, limit);
        return nativeQuery.getResultList();
    }

    // Embedding 업데이트
    public void updateEmbedding(String embedding, Long id) {
        Query query = entityManager.createNativeQuery(
            "UPDATE documents SET embedding = CAST(?1 AS vector) WHERE id = ?2"
        );
        query.setParameter(1, embedding);
        query.setParameter(2, id);
        query.executeUpdate();
    }

    // Document 생성
    public ResponseEntity<Document> createDocument(Document document) {
        LocalDateTime now = LocalDateTime.now();

        // embedding 생성
        float[] embeddingArray = embeddingService.embed(document.getContent());
        String vectorStr = floatArrayToVectorString(embeddingArray);

        // EntityManager로 INSERT 실행
        Query query = entityManager.createNativeQuery(
            "INSERT INTO documents (title, content, category, created_at, updated_at, embedding) " +
            "VALUES (?1, ?2, ?3, ?4, ?5, CAST(?6 AS vector)) RETURNING id"
        );
        query.setParameter(1, document.getTitle());
        query.setParameter(2, document.getContent());
        query.setParameter(3, document.getCategory());
        query.setParameter(4, now);
        query.setParameter(5, now);
        query.setParameter(6, vectorStr);

        Object result = query.getSingleResult();
        Long savedId;

        if (result instanceof Long) {
            savedId = (Long) result;
        } else if (result instanceof BigInteger) {
            savedId = ((BigInteger) result).longValue();
        } else if (result instanceof Integer) {
            savedId = ((Integer) result).longValue();
        } else {
            savedId = Long.valueOf(result.toString());
        }

        // 저장된 Document 조회
        Document savedDocument = documentRepository.findById(savedId).orElse(null);

        return ResponseEntity.ok(savedDocument);
    }

    // Document 업데이트
    public ResponseEntity<Document> updateDocument(Long id, Document document) {
        Optional<Document> existingDoc = documentRepository.findById(id);
        if (existingDoc.isPresent()) {
            Document doc = existingDoc.get();
            doc.setTitle(document.getTitle());
            doc.setContent(document.getContent());
            doc.setCategory(document.getCategory());
            doc.setUpdatedAt(LocalDateTime.now());

            Document savedDocument = documentRepository.save(doc);

            // embedding 업데이트
            float[] embeddingArray = embeddingService.embed(document.getContent());
            String vectorStr = floatArrayToVectorString(embeddingArray);
            updateEmbedding(vectorStr, id);

            savedDocument.setEmbedding(embeddingArray);
            return ResponseEntity.ok(savedDocument);
        }
        return ResponseEntity.notFound().build();
    }

    private String floatArrayToVectorString(float[] array) {
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