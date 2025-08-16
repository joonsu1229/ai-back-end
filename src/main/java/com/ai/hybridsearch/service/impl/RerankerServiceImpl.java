package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.dto.SearchResult;
import com.ai.hybridsearch.entity.JobPosting;
import com.ai.hybridsearch.service.EmbeddingService;
import com.ai.hybridsearch.service.RerankerService;
import dev.langchain4j.data.embedding.Embedding;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class RerankerServiceImpl implements RerankerService {

    @Autowired
    private EmbeddingService embeddingService;

    public List<SearchResult> rerankWithCategoryBoost(List<SearchResult> results, String query,
                                                     String preferredCategory, int topK) {
        if (results.isEmpty()) {
            return results;
        }

        Embedding queryEmbedding = embeddingService.generateEmbedding(query);

        return results.stream()
            .map(result -> {
                String content = result.getDocument().getTitle() + " " + result.getDocument().getContent();
                Embedding docEmbedding = embeddingService.generateEmbedding(content);
                double semanticSimilarity = embeddingService.cosineSimilarity(queryEmbedding, docEmbedding);

                // 카테고리 부스트
                double categoryBoost = (preferredCategory != null &&
                    preferredCategory.equals(result.getDocument().getCategory())) ? 1.2 : 1.0;

                double hybridScore = ((result.getScore() * 0.3) + (semanticSimilarity * 0.7)) * categoryBoost;
                result.setScore(hybridScore);

                return result;
            })
            .sorted((r1, r2) -> Double.compare(r2.getScore(), r1.getScore()))
            .limit(topK)
            .collect(Collectors.toList());
    }

    public List<JobPosting> rerank(List<JobPosting> results, String query, int topK) {
        if (results.isEmpty()) {
            return results;
        }

        Embedding queryEmbedding = embeddingService.generateEmbedding(query);

        // 각 결과에 대해 의미적 유사도 계산 및 스코어 재조정
        return results.stream()
            .map(result -> {
                String content = result.getJobPosting().getTitle() + " " + result.getJobPosting().getDescription();
                Embedding docEmbedding = embeddingService.generateEmbedding(content);
                double semanticSimilarity = embeddingService.cosineSimilarity(queryEmbedding, docEmbedding);

                // 하이브리드 스코어: 키워드 검색 스코어 + 의미적 유사도
                double hybridScore = (result.getScore() * 0.3) + (semanticSimilarity * 0.7);
                result.setScore(hybridScore);

                return result;
            })
            .sorted((r1, r2) -> Double.compare(r2.getScore(), r1.getScore()))
            .limit(topK)
            .collect(Collectors.toList());
    }

}