package com.ai.hybridsearch.repository;

import com.ai.hybridsearch.entity.JobPosting;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    // -------------------
    // 조회용 메서드 (readOnly)
    // -------------------

    @Transactional(readOnly = true)
    @Query(value = "SELECT jp FROM JobPosting jp WHERE jp.isActive = true ORDER BY jp.deadline ASC",
            countQuery = "SELECT count(jp) FROM JobPosting jp WHERE jp.isActive = true")
    Page<JobPosting> findByIsActiveTrue(Pageable pageable);

    @Transactional(readOnly = true)
    List<JobPosting> findByIsActiveTrueOrderByCreatedAtDesc();

    @Transactional(readOnly = true)
    Page<JobPosting> findByJobCategoryAndIsActiveTrue(String jobCategory, Pageable pageable);

    @Transactional(readOnly = true)
    List<JobPosting> findByJobCategoryAndIsActiveTrue(String jobCategory);

    @Transactional(readOnly = true)
    List<JobPosting> findByCompanyContainingIgnoreCaseAndIsActiveTrue(String company);

    @Transactional(readOnly = true)
    Page<JobPosting> findByCompanyContainingIgnoreCaseAndIsActiveTrue(String company, Pageable pageable);

    @Transactional(readOnly = true)
    Page<JobPosting> findByLocationContainingIgnoreCaseAndIsActiveTrue(String location, Pageable pageable);

    @Transactional(readOnly = true)
    List<JobPosting> findByLocationContainingIgnoreCaseAndIsActiveTrue(String location);

    @Transactional(readOnly = true)
    List<JobPosting> findBySourceSiteAndIsActiveTrue(String sourceSite);

    @Transactional(readOnly = true)
    Page<JobPosting> findByExperienceLevelContainingIgnoreCaseAndIsActiveTrue(String experienceLevel, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND " +
            "(LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(j.company) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
            "LOWER(j.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<JobPosting> findByKeywordAndIsActiveTrue(@Param("keyword") String keyword, Pageable pageable);

    @Transactional(readOnly = true)
    Page<JobPosting> findByTitleContainingIgnoreCaseOrCompanyContainingIgnoreCaseOrDescriptionContainingIgnoreCaseAndIsActiveTrue(
            String title, String company, String description, Pageable pageable);

    @Transactional(readOnly = true)
    List<JobPosting> findByTitleAndCompanyAndSourceSite(String title, String company, String sourceSite);

    @Transactional(readOnly = true)
    List<JobPosting> findByTitleAndCompany(String title, String company);

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.createdAt >= :startDate AND j.isActive = true")
    List<JobPosting> findByCreatedAtAfterAndIsActiveTrue(@Param("startDate") LocalDateTime startDate);

    @Transactional(readOnly = true)
    @Query("SELECT COUNT(*) FROM JobPosting j WHERE j.createdAt >= :startOfDay AND j.createdAt < :endOfDay")
    long countTodayJobs(@Param("startOfDay") LocalDateTime startOfDay, @Param("endOfDay") LocalDateTime endOfDay);

    @Transactional(readOnly = true)
    @Query("SELECT j.jobCategory, COUNT(j) FROM JobPosting j WHERE j.isActive = true GROUP BY j.jobCategory")
    List<Object[]> getJobCountByCategory();

    @Transactional(readOnly = true)
    @Query("SELECT j.company, COUNT(j) FROM JobPosting j WHERE j.isActive = true GROUP BY j.company ORDER BY COUNT(j) DESC")
    List<Object[]> getTopCompanies();

    @Transactional(readOnly = true)
    @Query("SELECT j.location, COUNT(j) FROM JobPosting j WHERE j.isActive = true AND j.location IS NOT NULL GROUP BY j.location")
    List<Object[]> getJobCountByLocation();

    @Transactional(readOnly = true)
    @Query("SELECT j.sourceSite, COUNT(j) FROM JobPosting j WHERE j.isActive = true GROUP BY j.sourceSite")
    List<Object[]> getJobCountBySource();

    @Transactional(readOnly = true)
    @Query("SELECT j.experienceLevel, COUNT(j) FROM JobPosting j WHERE j.isActive = true AND j.experienceLevel IS NOT NULL GROUP BY j.experienceLevel")
    List<Object[]> getJobCountByExperience();

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.deadline IS NOT NULL AND j.deadline >= CURRENT_TIMESTAMP AND j.isActive = true ORDER BY j.deadline ASC")
    List<JobPosting> findJobsWithDeadline();

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.deadline IS NOT NULL AND j.deadline BETWEEN :startDate AND :endDate AND j.isActive = true")
    List<JobPosting> findJobsDeadlineBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Transactional(readOnly = true)
    @Query(value = "SELECT *, (1 - (embedding <=> CAST(?1 AS vector))) as similarity FROM job_postings WHERE is_active = true ORDER BY embedding <=> CAST(?1 AS vector) LIMIT ?2", nativeQuery = true)
    List<Object[]> findSimilarJobs(String embeddingVector, int limit);

    @Transactional(readOnly = true)
    @Query(value = "SELECT j.*, (1 - (j.embedding <=> CAST(?1 AS vector))) as similarity " +
            "FROM job_postings j " +
            "WHERE j.is_active = true " +
            "AND (LOWER(j.title) LIKE LOWER(CONCAT('%', ?2, '%')) " +
            "OR LOWER(j.company) LIKE LOWER(CONCAT('%', ?2, '%')) " +
            "OR LOWER(j.description) LIKE LOWER(CONCAT('%', ?2, '%'))) " +
            "ORDER BY " +
            "CASE WHEN LOWER(j.title) LIKE LOWER(CONCAT('%', ?2, '%')) THEN 1 " +
            "     WHEN LOWER(j.company) LIKE LOWER(CONCAT('%', ?2, '%')) THEN 2 " +
            "     ELSE 3 END, " +
            "j.embedding <=> CAST(?1 AS vector) " +
            "LIMIT ?3", nativeQuery = true)
    List<Object[]> hybridSearch(String embeddingVector, String keyword, int limit);

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true " +
            "AND (:jobCategory IS NULL OR j.jobCategory = :jobCategory) " +
            "AND (:location IS NULL OR LOWER(j.location) LIKE LOWER(CONCAT('%', :location, '%'))) " +
            "AND (:sourceSite IS NULL OR j.sourceSite = :sourceSite) " +
            "AND (:experienceLevel IS NULL OR LOWER(j.experienceLevel) LIKE LOWER(CONCAT('%', :experienceLevel, '%'))) " +
            "AND (:keyword IS NULL OR LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(j.company) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
            "OR LOWER(j.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<JobPosting> findWithFilters(
            @Param("jobCategory") String jobCategory,
            @Param("location") String location,
            @Param("sourceSite") String sourceSite,
            @Param("experienceLevel") String experienceLevel,
            @Param("keyword") String keyword,
            Pageable pageable
    );

    @Transactional(readOnly = true)
    @Query(value = "SELECT COUNT(*) FROM job_postings WHERE is_active=true", nativeQuery = true)
    long countActiveJobs();

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.updatedAt >= :since ORDER BY j.updatedAt DESC")
    List<JobPosting> findRecentlyUpdated(@Param("since") LocalDateTime since);

    @Transactional(readOnly = true)
    @Query("SELECT j.company, COUNT(j) as jobCount FROM JobPosting j WHERE j.isActive = true GROUP BY j.company HAVING COUNT(j) >= :minJobs ORDER BY COUNT(j) DESC")
    List<Object[]> findPopularCompanies(@Param("minJobs") int minJobs);

    @Transactional(readOnly = true)
    boolean existsBySourceUrlAndIsActiveTrue(String sourceUrl);

    @Transactional(readOnly = true)
    @Query("SELECT j FROM JobPosting j WHERE j.createdAt BETWEEN :startDate AND :endDate AND j.isActive = true")
    List<JobPosting> findJobsBetweenDates(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Transactional(readOnly = true)
    Optional<JobPosting> findFirstBySourceSiteOrderByCreatedAtDesc(String sourceSite);

    @Transactional(readOnly = true)
    Page<JobPosting> findBySourceSiteAndIsActiveTrue(String sourceSite, Pageable pageable);

    @Transactional(readOnly = true)
    long countBySourceSiteAndIsActiveTrue(@Param("siteName") String siteName);

    @Transactional(readOnly = true)
    boolean existsByTitleAndCompanyAndSourceSite(String title, String company, String sourceSite);

    @Transactional(readOnly = true)
    long countBySourceSiteAndCreatedAtAfterAndIsActiveTrue(@Param("siteName") String siteName, @Param("sinceDate") LocalDateTime sinceDate);

    // -------------------
    // 변경/배치용 메서드 (Modifying + Transactional)
    // -------------------

    @Modifying(clearAutomatically = true)
    @Transactional
    @Query(value = "UPDATE job_postings SET embedding = CAST(:embeddingText AS vector) WHERE id = :id", nativeQuery = true)
    int updateEmbedding(@Param("id") Long id, @Param("embeddingText") String embeddingText);

    @Modifying
    @Transactional
    @Query("UPDATE JobPosting j SET j.isActive = false WHERE j.deadline < :currentDate AND j.isActive = true")
    int deactivateExpiredJobs(@Param("currentDate") LocalDateTime currentDate);

    @Modifying
    @Transactional
    @Query("DELETE FROM JobPosting j WHERE j.createdAt < :cutoffDate")
    int deleteByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

    @Modifying
    @Transactional
    @Query(value = "INSERT INTO job_posting (title, company, source_site, source_url, job_category, location, description, requirements, benefits, salary, employment_type, experience_level, is_active, created_at, updated_at) VALUES (:#{#job.title}, :#{#job.company}, :#{#job.sourceSite}, :#{#job.sourceUrl}, :#{#job.jobCategory}, :#{#job.location}, :#{#job.description}, :#{#job.requirements}, :#{#job.benefits}, :#{#job.salary}, :#{#job.employmentType}, :#{#job.experienceLevel}, :#{#job.isActive}, :#{#job.createdAt}, :#{#job.updatedAt})", nativeQuery = true)
    void insertJobPosting(@Param("job") JobPosting job);
}
