package com.ai.hybridsearch.repository;

import com.ai.hybridsearch.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface VectorRepository extends JpaRepository<Document, Long> {

    @Query(value = """
        SELECT *, 1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM documents
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
    """, nativeQuery = true)
    List<Document> searchByEmbedding(@Param("embedding") float[] embedding, @Param("limit") int limit);

    @Query(value = """
        SELECT *, 1 - (embedding <=> CAST(:embedding AS vector)) AS similarity
        FROM documents
        WHERE category = :category
        ORDER BY embedding <=> CAST(:embedding AS vector)
        LIMIT :limit
    """, nativeQuery = true)
    List<Document> searchByEmbeddingAndCategory(@Param("embedding") float[] embedding,
                                                @Param("category") String category,
                                                @Param("limit") int limit);

}