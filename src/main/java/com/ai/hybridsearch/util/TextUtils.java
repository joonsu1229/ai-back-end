package com.ai.hybridsearch.util;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class TextUtils {

    private static final Pattern HTML_TAGS = Pattern.compile("<[^>]+>");
    private static final Pattern MULTIPLE_WHITESPACE = Pattern.compile("\\s+");
    private static final List<String> STOP_WORDS = Arrays.asList(
        "a", "an", "and", "are", "as", "at", "be", "by", "for", "from",
        "has", "he", "in", "is", "it", "its", "of", "on", "that", "the",
        "to", "was", "will", "with", "the", "this", "but", "they", "have",
        "had", "what", "said", "each", "which", "she", "do", "how", "their"
    );

    public static String cleanText(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        // HTML 태그 제거
        String cleaned = HTML_TAGS.matcher(text).replaceAll(" ");

        // 여러 공백을 하나로 변환
        cleaned = MULTIPLE_WHITESPACE.matcher(cleaned).replaceAll(" ");

        return cleaned.trim().toLowerCase();
    }

    public static String removeStopWords(String text) {
        if (text == null || text.trim().isEmpty()) {
            return "";
        }

        return Arrays.stream(text.toLowerCase().split("\\s+"))
            .filter(word -> !STOP_WORDS.contains(word))
            .filter(word -> word.length() > 2)
            .collect(Collectors.joining(" "));
    }

    public static String generateSnippet(String text, String query, int maxLength) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        if (text.length() <= maxLength) {
            return text;
        }

        // 쿼리가 있는 경우 해당 부분 중심으로 스니펫 생성
        if (query != null && !query.isEmpty()) {
            String lowerText = text.toLowerCase();
            String lowerQuery = query.toLowerCase();
            int index = lowerText.indexOf(lowerQuery);

            if (index != -1) {
                int start = Math.max(0, index - maxLength / 4);
                int end = Math.min(text.length(), start + maxLength);

                String snippet = text.substring(start, end);
                if (start > 0) snippet = "..." + snippet;
                if (end < text.length()) snippet += "...";

                return snippet;
            }
        }

        // 쿼리가 없거나 찾을 수 없는 경우 처음부터 자르기
        return text.substring(0, maxLength) + "...";
    }

    public static List<String> extractKeywords(String text, int limit) {
        if (text == null || text.trim().isEmpty()) {
            return List.of();
        }

        String cleaned = cleanText(text);
        String withoutStopWords = removeStopWords(cleaned);

        return Arrays.stream(withoutStopWords.split("\\s+"))
            .distinct()
            .limit(limit)
            .collect(Collectors.toList());
    }
}