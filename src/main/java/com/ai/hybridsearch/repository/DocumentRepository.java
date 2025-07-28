package com.ai.hybridsearch.repository;

import com.ai.hybridsearch.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    // Full-text search using PostgreSQL
    @Query(value = """
        SELECT d.*, ts_rank(d.search_vector, plainto_tsquery(:query)) as rank
        FROM documents d
        WHERE d.search_vector @@ plainto_tsquery(:query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Document> findByFullTextSearch(@Param("query") String query, @Param("limit") int limit);

    // Category-based search
    @Query("SELECT d FROM Document d WHERE d.category = :category")
    List<Document> findByCategory(@Param("category") String category);

    // Combined text and category search
    @Query(value = """
        SELECT d.*, ts_rank(d.search_vector, plainto_tsquery(:query)) as rank
        FROM documents d
        WHERE d.search_vector @@ plainto_tsquery(:query)
        AND (:category IS NULL OR d.category = :category)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Document> findByFullTextSearchAndCategory(
        @Param("query") String query,
        @Param("category") String category,
        @Param("limit") int limit
    );
}