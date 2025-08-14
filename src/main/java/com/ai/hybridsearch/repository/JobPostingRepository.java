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
import java.util.Set;

@Repository
public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

    // 활성 채용공고 조회
    @Query(
    value = "SELECT jp FROM JobPosting jp WHERE jp.isActive = true ORDER BY jp.createdAt DESC",
    countQuery = "SELECT count(jp) FROM JobPosting jp WHERE jp.isActive = true"
    )
    Page<JobPosting> findByIsActiveTrue(Pageable pageable);

    List<JobPosting> findByIsActiveTrueOrderByCreatedAtDesc();

    // 카테고리별 조회
    Page<JobPosting> findByJobCategoryAndIsActiveTrue(String jobCategory, Pageable pageable);

    List<JobPosting> findByJobCategoryAndIsActiveTrue(String jobCategory);

    // 회사별 조회
    List<JobPosting> findByCompanyContainingIgnoreCaseAndIsActiveTrue(String company);

    Page<JobPosting> findByCompanyContainingIgnoreCaseAndIsActiveTrue(String company, Pageable pageable);

    // 지역별 조회
    Page<JobPosting> findByLocationContainingIgnoreCaseAndIsActiveTrue(String location, Pageable pageable);

    List<JobPosting> findByLocationContainingIgnoreCaseAndIsActiveTrue(String location);

    // 출처별 조회
    Page<JobPosting> findBySourceSiteAndIsActiveTrue(String sourceSite, Pageable pageable);

    List<JobPosting> findBySourceSiteAndIsActiveTrue(String sourceSite);

    // 경력별 조회
    Page<JobPosting> findByExperienceLevelContainingIgnoreCaseAndIsActiveTrue(String experienceLevel, Pageable pageable);

    // 키워드 검색 (제목, 회사명, 설명에서 검색)
    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND " +
           "(LOWER(j.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(j.company) LIKE LOWER(CONCAT('%', :keyword, '%')) OR " +
           "LOWER(j.description) LIKE LOWER(CONCAT('%', :keyword, '%')))")
    Page<JobPosting> findByKeywordAndIsActiveTrue(@Param("keyword") String keyword, Pageable pageable);

    // 복합 검색 (제목, 회사명, 설명 각각)
    Page<JobPosting> findByTitleContainingIgnoreCaseOrCompanyContainingIgnoreCaseOrDescriptionContainingIgnoreCaseAndIsActiveTrue(
            String title, String company, String description, Pageable pageable);

    // 중복 체크용
    List<JobPosting> findByTitleAndCompanyAndSourceSite(String title, String company, String sourceSite);

    List<JobPosting> findByTitleAndCompany(String title, String company);

    // 날짜 기준 조회 (created_at 사용)
    @Query("SELECT j FROM JobPosting j WHERE j.createdAt >= :startDate AND j.isActive = true")
    List<JobPosting> findByCreatedAtAfterAndIsActiveTrue(@Param("startDate") LocalDateTime startDate);

    // 오늘 생성된 채용공고 개수 (created_at 사용)
    @Query("SELECT COUNT(*) FROM JobPosting j WHERE j.createdAt >= :startOfDay AND j.createdAt < :endOfDay")
    long countTodayJobs(@Param("startOfDay") LocalDateTime startOfDay, @Param("endOfDay") LocalDateTime endOfDay);

    // 통계용 쿼리들
    @Query("SELECT j.jobCategory, COUNT(j) FROM JobPosting j WHERE j.isActive = true GROUP BY j.jobCategory")
    List<Object[]> getJobCountByCategory();

    @Query("SELECT j.company, COUNT(j) FROM JobPosting j WHERE j.isActive = true GROUP BY j.company ORDER BY COUNT(j) DESC")
    List<Object[]> getTopCompanies();

    @Query("SELECT j.location, COUNT(j) FROM JobPosting j WHERE j.isActive = true AND j.location IS NOT NULL GROUP BY j.location")
    List<Object[]> getJobCountByLocation();

    @Query("SELECT j.sourceSite, COUNT(j) FROM JobPosting j WHERE j.isActive = true GROUP BY j.sourceSite")
    List<Object[]> getJobCountBySource();

    @Query("SELECT j.experienceLevel, COUNT(j) FROM JobPosting j WHERE j.isActive = true AND j.experienceLevel IS NOT NULL GROUP BY j.experienceLevel")
    List<Object[]> getJobCountByExperience();

    // 마감일 기준 조회
    @Query("SELECT j FROM JobPosting j WHERE j.deadline IS NOT NULL AND j.deadline >= CURRENT_TIMESTAMP AND j.isActive = true ORDER BY j.deadline ASC")
    List<JobPosting> findJobsWithDeadline();

    @Query("SELECT j FROM JobPosting j WHERE j.deadline IS NOT NULL AND j.deadline BETWEEN :startDate AND :endDate AND j.isActive = true")
    List<JobPosting> findJobsDeadlineBetween(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    // 유사도 검색용 (pgvector 사용)
    @Query(value = "SELECT *, (1 - (embedding <=> CAST(?1 AS vector))) as similarity " +
                   "FROM job_postings " +
                   "WHERE is_active = true " +
                   "ORDER BY embedding <=> CAST(?1 AS vector) " +
                   "LIMIT ?2", nativeQuery = true)
    List<Object[]> findSimilarJobs(String embeddingVector, int limit);

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

    // 검색 필터 조합
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

    // 최근 업데이트된 채용공고
    @Query("SELECT j FROM JobPosting j WHERE j.isActive = true AND j.updatedAt >= :since ORDER BY j.updatedAt DESC")
    List<JobPosting> findRecentlyUpdated(@Param("since") LocalDateTime since);

    // 인기 회사 (채용공고 수 기준)
    @Query("SELECT j.company, COUNT(j) as jobCount FROM JobPosting j " +
           "WHERE j.isActive = true " +
           "GROUP BY j.company " +
           "HAVING COUNT(j) >= :minJobs " +
           "ORDER BY COUNT(j) DESC")
    List<Object[]> findPopularCompanies(@Param("minJobs") int minJobs);

    // URL로 중복 체크
    boolean existsBySourceUrlAndIsActiveTrue(String sourceUrl);

    // 특정 기간 내 채용공고
    @Query("SELECT j FROM JobPosting j WHERE j.createdAt BETWEEN :startDate AND :endDate AND j.isActive = true")
    List<JobPosting> findJobsBetweenDates(@Param("startDate") LocalDateTime startDate, @Param("endDate") LocalDateTime endDate);

    @Modifying
    @Query("UPDATE JobPosting j SET j.isActive = false WHERE j.deadline < :currentDate AND j.isActive = true")
    int deactivateExpiredJobs(@Param("currentDate") LocalDateTime currentDate);

    @Query("DELETE FROM JobPosting j WHERE j.createdAt < :cutoffDate")
    @Modifying
    int deleteByCreatedAtBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

    // 배치 처리용 메서드
    @Modifying
    @Query(value = "INSERT INTO job_posting (title, company, source_site, source_url, job_category, location, " +
                  "description, requirements, benefits, salary, employment_type, experience_level, is_active, " +
                  "created_at, updated_at) VALUES " +
                  "(:#{#job.title}, :#{#job.company}, :#{#job.sourceSite}, :#{#job.sourceUrl}, :#{#job.jobCategory}, " +
                  ":#{#job.location}, :#{#job.description}, :#{#job.requirements}, :#{#job.benefits}, " +
                  ":#{#job.salary}, :#{#job.employmentType}, :#{#job.experienceLevel}, :#{#job.isActive}, " +
                  ":#{#job.createdAt}, :#{#job.updatedAt})", nativeQuery = true)
    void insertJobPosting(@Param("job") JobPosting job);
}