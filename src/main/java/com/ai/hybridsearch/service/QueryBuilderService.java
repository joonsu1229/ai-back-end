package com.ai.hybridsearch.service;

import java.util.List;

public interface QueryBuilderService {
    String buildFullTextQuery(String query);
    String buildFuzzyQuery(String query);
    String buildPhraseQuery(String phrase);
    String buildBooleanQuery(List<String> mustHave, List<String> shouldHave, List<String> mustNotHave);
}