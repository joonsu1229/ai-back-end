package com.ai.hybridsearch.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "documents")
@Getter
@Setter
public class Document {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String content;

    @Column(name = "category")
    private String category;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    // Full-text search를 위한 tsvector 컬럼
    @Column(name = "search_vector", insertable = false, updatable = false, columnDefinition = "tsvector")
    private String searchVector;

    // vector 검색을 위한 vector컬럼
    @Transient
    private float[] embedding;

    @Transient
    private Document document;
    @Transient
    private float score;

    // 생성자
    public Document() {}

    public Document(Document document, float score) {
        this.document = document;
        this.score = score;
    }

    public Document(String title, String content, String category) {
        this.title = title;
        this.content = content;
        this.category = category;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

}