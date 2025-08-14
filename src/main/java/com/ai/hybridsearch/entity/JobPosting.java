package com.ai.hybridsearch.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "job_postings")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobPosting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1000)
    private String title;

    @Column(nullable = false, length = 500)
    private String company;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 200)
    private String location;

    @Column(name = "experience_level", length = 100)
    private String experienceLevel;

    @Column(length = 200)
    private String salary;

    @Column(name = "employment_type", length = 100)
    private String employmentType; // 정규직, 계약직, 인턴 등

    @Column(name = "job_category", length = 100)
    private String jobCategory; // 개발, 디자인, 마케팅 등

    @Column(columnDefinition = "TEXT")
    private String requirements;

    @Column(columnDefinition = "TEXT")
    private String benefits;

    @Column(name = "source_site", length = 100)
    private String sourceSite; // 사람인, 잡코리아, 원티드 등

    @Column(name = "source_url", length = 2000)
    private String sourceUrl;

    @Column(name = "deadline")
    private LocalDateTime deadline;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "embedding")
    private float[] embedding;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}