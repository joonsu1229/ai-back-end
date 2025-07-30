package com.ai.hybridsearch.service;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.Document;
import com.ai.hybridsearch.repository.VectorRepository;
import com.pgvector.PGvector;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class VectorSearchService {


    private final EmbeddingService embeddingService; // LangChain4j용
    private final VectorRepository vectorRepository;

    public VectorSearchService(EmbeddingService embeddingService, VectorRepository vectorRepository) {
        this.embeddingService = embeddingService;
        this.vectorRepository = vectorRepository;
    }

    public List<SearchResult> searchByEmbedding(String query, String category, int limit) {
        float[] embedding = embeddingService.embed(query);

        List<Document> documents = (category != null && !category.isEmpty())
                ? vectorRepository.searchByEmbeddingAndCategory(embedding, category, limit)
                : vectorRepository.searchByEmbedding(embedding, limit);

        return documents.stream()
                .map(doc -> new SearchResult(doc, 0.8, "embedding"))
                .collect(Collectors.toList());
    }
}
