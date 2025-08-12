package com.ai.hybridsearch.repository;

import com.ai.hybridsearch.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface VectorSearchRepository extends JpaRepository<Document, Long> {

    @Query(value = """
        SELECT d.*, 1 - (d.embedding <=> CAST(:vector AS vector)) AS similarity
        FROM documents d
        ORDER BY d.embedding <=> CAST(:vector AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchByEmbedding(@Param("vector") String vector, @Param("limit") int limit);

    @Query(value = """
        SELECT d.*, 1 - (d.embedding <=> CAST(:vector AS vector)) AS similarity
        FROM documents d
        WHERE d.category = :category
        ORDER BY d.embedding <=> CAST(:vector AS vector)
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> searchByEmbeddingAndCategory(@Param("vector") String vector, @Param("category") String category, @Param("limit") int limit);

    @Query(value = """
        SELECT d.*, ts_rank(d.search_vector, plainto_tsquery(:query)) as rank
        FROM documents d
        WHERE d.search_vector @@ plainto_tsquery(:query)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByFullTextSearch(@Param("query") String query, @Param("limit") int limit);

    @Query(value = """
        SELECT d.*, ts_rank(d.search_vector, plainto_tsquery(:query)) as rank
        FROM documents d
        WHERE d.search_vector @@ plainto_tsquery(:query)
        AND (:category IS NULL OR d.category = :category)
        ORDER BY rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByFullTextSearchAndCategory(@Param("query") String query, @Param("category") String category, @Param("limit") int limit);

    @Query(value = """
        SELECT d.*, 
               ts_rank_cd(d.search_vector, plainto_tsquery(:query)) as detailed_rank,
               ts_rank(d.search_vector, plainto_tsquery(:query)) as simple_rank
        FROM documents d
        WHERE d.search_vector @@ plainto_tsquery(:query)
        ORDER BY detailed_rank DESC, simple_rank DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findByFullTextSearchWithDetailedRanking(@Param("query") String query, @Param("limit") int limit);

    @Query(value = """
        SELECT 
            MIN(ts_rank(d.search_vector, plainto_tsquery(:query))) as min_score,
            MAX(ts_rank(d.search_vector, plainto_tsquery(:query))) as max_score,
            AVG(ts_rank(d.search_vector, plainto_tsquery(:query))) as avg_score,
            COUNT(*) as match_count
        FROM documents d
        WHERE d.search_vector @@ plainto_tsquery(:query)
        """, nativeQuery = true)
    Object[] analyzeFullTextScores(@Param("query") String query);

}