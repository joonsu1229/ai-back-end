package com.ai.hybridsearch.dto;

import com.ai.hybridsearch.entity.Document;

public class SearchResult {
    private Document document;
    private double score;
    private String searchType;
    private String snippet;

    public SearchResult(Document document, double score, String searchType) {
        this.document = document;
        this.score = score;
        this.searchType = searchType;
        this.snippet = generateSnippet(document.getContent());
    }

    private String generateSnippet(String content) {
        if (content == null || content.length() <= 200) {
            return content;
        }
        return content.substring(0, 200) + "...";
    }

    // Getters and Setters
    public Document getDocument() { return document; }
    public void setDocument(Document document) { this.document = document; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public String getSearchType() { return searchType; }
    public void setSearchType(String searchType) { this.searchType = searchType; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
}