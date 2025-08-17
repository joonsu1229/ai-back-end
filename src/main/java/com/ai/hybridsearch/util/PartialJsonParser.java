package com.ai.hybridsearch.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public class PartialJsonParser {

    private static final ObjectMapper mapper = new ObjectMapper();

    /**
     * 불완전한 JSON에서 파싱 가능한 부분까지만 추출
     */
    public String extractValidJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return "";
        }

        String cleaned = cleanJsonString(jsonString);

        // 1. 전체 JSON이 유효한지 먼저 확인
        if (isValidJson(cleaned)) {
            return cleaned;
        }

        // 2. 배열인 경우 부분 파싱 시도
        if (cleaned.trim().startsWith("[")) {
            return extractValidArrayPart(cleaned);
        }

        // 3. 객체인 경우 부분 파싱 시도
        if (cleaned.trim().startsWith("{")) {
            return extractValidObjectPart(cleaned);
        }

        return cleaned;
    }

    /**
     * JSON 배열에서 완전한 객체들만 추출
     */
    private static String extractValidArrayPart(String jsonArray) {
        try {
            List<String> validObjects = new ArrayList<>();
            String trimmed = jsonArray.trim();

            if (!trimmed.startsWith("[")) {
                return jsonArray;
            }

            // [ 다음부터 시작
            String content = trimmed.substring(1);
            int braceCount = 0;
            int startIndex = -1;
            boolean inString = false;
            boolean escaped = false;

            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);

                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == '"') {
                    inString = !inString;
                    continue;
                }

                if (inString) {
                    continue;
                }

                if (c == '{') {
                    if (braceCount == 0) {
                        startIndex = i;
                    }
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0 && startIndex != -1) {
                        // 완전한 객체 발견
                        String objectStr = content.substring(startIndex, i + 1);
                        if (isValidJson(objectStr)) {
                            validObjects.add(objectStr);
                        }
                        startIndex = -1;
                    }
                }
            }

            if (!validObjects.isEmpty()) {
                return "[" + String.join(",", validObjects) + "]";
            }

        } catch (Exception e) {
            System.err.println("Error parsing partial array: " + e.getMessage());
        }

        return "[]"; // 빈 배열 반환
    }

    /**
     * JSON 객체에서 완전한 필드들만 추출
     */
    private static String extractValidObjectPart(String jsonObject) {
        try {
            Map<String, Object> validFields = new LinkedHashMap<>();
            String trimmed = jsonObject.trim();

            if (!trimmed.startsWith("{")) {
                return jsonObject;
            }

            // 간단한 필드 추출 로직
            String[] lines = trimmed.split("\n");
            StringBuilder currentField = new StringBuilder();
            String currentKey = null;
            boolean inValue = false;

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.equals("{") || line.equals("}")) {
                    continue;
                }

                // "key": 형태 찾기
                if (line.contains(":") && line.startsWith("\"")) {
                    int colonIndex = line.indexOf(":");
                    String keyPart = line.substring(0, colonIndex).trim();
                    String valuePart = line.substring(colonIndex + 1).trim();

                    // 키 추출
                    if (keyPart.endsWith("\"")) {
                        currentKey = keyPart.substring(1, keyPart.length() - 1);

                        // 값이 완전한지 확인
                        if (isCompleteValue(valuePart)) {
                            String cleanValue = valuePart.replaceAll(",$", "");
                            try {
                                // JSON 값으로 파싱 시도
                                JsonNode valueNode = mapper.readTree(cleanValue);
                                validFields.put(currentKey, valueNode);
                            } catch (Exception e) {
                                // 문자열로 처리
                                if (cleanValue.startsWith("\"") && cleanValue.endsWith("\"")) {
                                    validFields.put(currentKey, cleanValue.substring(1, cleanValue.length() - 1));
                                }
                            }
                        }
                    }
                }
            }

            if (!validFields.isEmpty()) {
                return mapper.writeValueAsString(validFields);
            }

        } catch (Exception e) {
            System.err.println("Error parsing partial object: " + e.getMessage());
        }

        return "{}"; // 빈 객체 반환
    }

    /**
     * 값이 완전한지 확인 (간단한 체크)
     */
    private static boolean isCompleteValue(String value) {
        value = value.trim().replaceAll(",$", "");

        // 문자열 값
        if (value.startsWith("\"")) {
            return value.endsWith("\"") && !value.endsWith("\\\"");
        }

        // 숫자 값
        if (value.matches("^-?\\d+(\\.\\d+)?$")) {
            return true;
        }

        // boolean, null
        if (value.equals("true") || value.equals("false") || value.equals("null")) {
            return true;
        }

        // 배열이나 객체는 간단히 체크
        if (value.startsWith("[") && value.endsWith("]")) {
            return true;
        }
        if (value.startsWith("{") && value.endsWith("}")) {
            return true;
        }

        return false;
    }

    /**
     * JSON 문자열 정리
     */
    private static String cleanJsonString(String json) {
        return json.replaceFirst("^```json\\s*", "")
                .replaceFirst("```\\s*$", "")
                .trim();
    }

    /**
     * JSON 유효성 검사
     */
    private static boolean isValidJson(String json) {
        try {
            mapper.readTree(json);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 더 간단한 방법: 마지막 완전한 객체까지만 파싱
     */
    public static String extractLastCompleteObject(String jsonArray) {
        try {
            String cleaned = cleanJsonString(jsonArray);
            if (!cleaned.startsWith("[")) {
                return cleaned;
            }

            // 마지막 완전한 }를 찾기
            int lastCompleteObject = -1;
            int braceCount = 0;
            boolean inString = false;
            boolean escaped = false;

            for (int i = 1; i < cleaned.length(); i++) { // [ 다음부터 시작
                char c = cleaned.charAt(i);

                if (escaped) {
                    escaped = false;
                    continue;
                }

                if (c == '\\') {
                    escaped = true;
                    continue;
                }

                if (c == '"') {
                    inString = !inString;
                    continue;
                }

                if (!inString) {
                    if (c == '{') {
                        braceCount++;
                    } else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            lastCompleteObject = i;
                        }
                    }
                }
            }

            if (lastCompleteObject != -1) {
                String partial = cleaned.substring(0, lastCompleteObject + 1) + "]";
                if (isValidJson(partial)) {
                    return partial;
                }
            }

        } catch (Exception e) {
            System.err.println("Error extracting last complete object: " + e.getMessage());
        }

        return "[]";
    }
}