package com.ai.hybridsearch.service.impl;

import com.ai.hybridsearch.service.QueryBuilderService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class QueryBuilderServiceImpl implements QueryBuilderService {

    private static final Pattern SPECIAL_CHARS = Pattern.compile("[!@#$%^&*()+=\\[\\]{}|;':\",./<>?`~]");

    public String buildFullTextQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }

        // 특수문자 제거 및 정규화
        String cleanQuery = SPECIAL_CHARS.matcher(query.trim()).replaceAll(" ");
        String[] terms = cleanQuery.split("\\s+");

        List<String> processedTerms = new ArrayList<>();
        for (String term : terms) {
            if (term.length() > 2) { // 2글자 이상의 단어만 포함
                processedTerms.add(term);
            }
        }

        return String.join(" & ", processedTerms);
    }

    public String buildFuzzyQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }

        String cleanQuery = SPECIAL_CHARS.matcher(query.trim()).replaceAll(" ");
        String[] terms = cleanQuery.split("\\s+");

        List<String> fuzzyTerms = new ArrayList<>();
        for (String term : terms) {
            if (term.length() > 3) {
                fuzzyTerms.add(term + ":*"); // Prefix matching
            } else {
                fuzzyTerms.add(term);
            }
        }

        return String.join(" & ", fuzzyTerms);
    }

    public String buildPhraseQuery(String phrase) {
        if (phrase == null || phrase.trim().isEmpty()) {
            return "";
        }

        return "\"" + phrase.trim() + "\"";
    }

    public String buildBooleanQuery(List<String> mustHave, List<String> shouldHave, List<String> mustNotHave) {
        List<String> queryParts = new ArrayList<>();

        if (mustHave != null && !mustHave.isEmpty()) {
            for (String term : mustHave) {
                queryParts.add("+" + term);
            }
        }

        if (shouldHave != null && !shouldHave.isEmpty()) {
            queryParts.addAll(shouldHave);
        }

        if (mustNotHave != null && !mustNotHave.isEmpty()) {
            for (String term : mustNotHave) {
                queryParts.add("-" + term);
            }
        }

        return String.join(" ", queryParts);
    }
}