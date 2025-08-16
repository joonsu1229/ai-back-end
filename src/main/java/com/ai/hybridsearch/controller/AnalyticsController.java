package com.ai.hybridsearch.controller;

import com.ai.hybridsearch.service.impl.SearchAnalyticsServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "*")
public class AnalyticsController {
    
    @Autowired
    private SearchAnalyticsServiceImpl analyticsService;
    
    @GetMapping("/popular-queries")
    public ResponseEntity<Map<String, Long>> getPopularQueries(
            @RequestParam(defaultValue = "10") int limit) {
        Map<String, Long> popularQueries = analyticsService.getPopularQueries(limit);
        return ResponseEntity.ok(popularQueries);
    }
    
    @GetMapping("/total-searches")
    public ResponseEntity<Map<String, Long>> getTotalSearches() {
        long totalSearches = analyticsService.getTotalSearches();
        return ResponseEntity.ok(Map.of("totalSearches", totalSearches));
    }
}