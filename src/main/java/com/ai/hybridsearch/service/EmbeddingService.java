package com.ai.hybridsearch.service;

import com.ai.hybridsearch.config.EmbeddingProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingProperties properties;

    private EmbeddingModel embeddingModel;

    @PostConstruct
    public void init() {
        switch (properties.getModelType().toLowerCase()) {
            case "onnx" -> embeddingModel = new AllMiniLmL6V2EmbeddingModel();
            case "openai" -> embeddingModel = OpenAiEmbeddingModel.builder()
                    .apiKey(properties.getOpenai().getApiKey())
                    .build();
            default -> throw new IllegalArgumentException("Unsupported embedding model type: " + properties.getModelType());
        }
    }

    public Embedding generateEmbedding(String text) {
        return embeddingModel.embed(text).content();
    }

    public float[] embed(String text) {
        return embeddingModel.embed(text).content().vector();
    }

    public double cosineSimilarity(Embedding embedding1, Embedding embedding2) {
        float[] vector1 = embedding1.vector();
        float[] vector2 = embedding2.vector();

        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;

        for (int i = 0; i < vector1.length; i++) {
            dotProduct += vector1[i] * vector2[i];
            normA += Math.pow(vector1[i], 2);
            normB += Math.pow(vector2[i], 2);
        }

        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
