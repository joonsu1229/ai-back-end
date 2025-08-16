package com.ai.hybridsearch.service;

import com.ai.hybridsearch.entity.JobPosting;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public interface JobCrawlingService {
    CompletableFuture<String> startManualCrawling();
    CompletableFuture<String> startCrawlingBySites(List<String> siteIds);
    CompletableFuture<List<JobPosting>> crawlAllSites();
    List<JobPosting> crawlAllSitesSync();
    void saveIndividualJob(JobPosting job);
    Map<String, String> getSupportedSites();
    Map<String, Object> getSiteStatus(String siteId);
    List<Map<String, Object>> getAllSitesStatus();
    Map<String, Object> getSiteStatistics();
    void testAiExtraction(String url, String siteName);
}