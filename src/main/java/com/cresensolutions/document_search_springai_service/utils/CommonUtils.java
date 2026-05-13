package com.cresensolutions.document_search_springai_service.utils;

import com.cresensolutions.document_search_springai_service.commons.Common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class CommonUtils {

    private CommonUtils() {
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public static String stringValue(Object value) {
        return value == null ? Common.EMPTY : value.toString();
    }

    public static String defaultString(Object value, String fallback) {
        String text = stringValue(value);
        return text.isBlank() ? fallback : text;
    }

    public static String normalizeWhitespace(String value) {
        return value == null ? Common.EMPTY : value.replaceAll("\\s+", " ").trim();
    }

    public static String trimTrailingSlash(String value) {
        if (value == null) {
            return Common.EMPTY;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public static String buildAzureChatCompletionsUrl(String endpoint, String deployment, String apiVersion) {
        return trimTrailingSlash(endpoint) + Common.AZURE_CHAT_COMPLETIONS_PATH.formatted(deployment, apiVersion);
    }

    public static String buildAzureSearchUrl(String endpoint, String indexName, String apiVersion) {
        return trimTrailingSlash(endpoint) + Common.AZURE_SEARCH_PATH.formatted(indexName, apiVersion);
    }

    public static String extractJsonObject(String content) {
        String text = content == null ? Common.EMPTY : content.trim()
                .replaceFirst("^```json\\s*", Common.EMPTY)
                .replaceFirst("^```\\s*", Common.EMPTY)
                .replaceFirst("\\s*```$", Common.EMPTY);
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    public static double clamp(double value, double min, double max, double fallback) {
        if (Double.isNaN(value)) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0.0 : Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    public static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return Collections.emptyList();
        }
        return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
    }

    public static Set<String> significantTokens(String text) {
        return List.of(normalizeWhitespace(text).toLowerCase().split("[^\\p{L}\\p{N}]+")).stream()
                .filter(token -> token.length() > 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static double tokenOverlapRatio(String left, String right) {
        Set<String> leftTokens = significantTokens(left);
        if (leftTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> rightTokens = significantTokens(right);
        long matches = leftTokens.stream().filter(rightTokens::contains).count();
        return (double) matches / leftTokens.size();
    }

    public static String filename(String blobName) {
        int slash = blobName == null ? -1 : blobName.lastIndexOf('/');
        return slash >= 0 ? blobName.substring(slash + 1) : stringValue(blobName);
    }

    public static String appendPageFragment(String url, Integer page) {
        if (!hasText(url) || page == null || page < 1) {
            return url;
        }
        return url + Common.PAGE_FRAGMENT_PREFIX + page;
    }

    public static String newChatId() {
        return Common.CHAT_ID_PREFIX + UUID.randomUUID().toString().replace("-", Common.EMPTY)
                .substring(0, Common.CHAT_ID_LENGTH);
    }

    public static Map<String, Object> toStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        return map.entrySet().stream()
                .collect(Collectors.toMap(
                        entry -> entry.getKey().toString(),
                        Map.Entry::getValue,
                        (first, second) -> first,
                        LinkedHashMap::new
                ));
    }

    public static <T> T parseLlmJson(String rawContent, Class<T> targetClass, com.fasterxml.jackson.databind.ObjectMapper objectMapper) throws com.fasterxml.jackson.core.JsonProcessingException {
        if (!hasText(rawContent)) {
            throw new IllegalArgumentException("Cannot parse empty LLM content");
        }
        String json = extractJsonObject(rawContent);
        return objectMapper.readValue(json, targetClass);
    }
}
