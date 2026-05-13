package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CommonUtils Tests")
class CommonUtilsTest {

    // -------------------------------------------------------------------------
    // hasText
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("hasText: returns false for null/empty/blank")
    void hasText_falsy(String value) {
        assertThat(CommonUtils.hasText(value)).isFalse();
    }

    @Test
    @DisplayName("hasText: returns true for non-blank string")
    void hasText_truthy() {
        assertThat(CommonUtils.hasText("hello")).isTrue();
    }

    // -------------------------------------------------------------------------
    // stringValue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("stringValue: null returns empty string")
    void stringValue_null() {
        assertThat(CommonUtils.stringValue(null)).isEmpty();
    }

    @Test
    @DisplayName("stringValue: object returns toString()")
    void stringValue_object() {
        assertThat(CommonUtils.stringValue(42)).isEqualTo("42");
    }

    // -------------------------------------------------------------------------
    // defaultString
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("defaultString: returns fallback when value is blank")
    void defaultString_blank() {
        assertThat(CommonUtils.defaultString("", "fallback")).isEqualTo("fallback");
        assertThat(CommonUtils.defaultString(null, "fallback")).isEqualTo("fallback");
    }

    @Test
    @DisplayName("defaultString: returns actual string value when not blank")
    void defaultString_nonBlank() {
        assertThat(CommonUtils.defaultString("real", "fallback")).isEqualTo("real");
    }

    // -------------------------------------------------------------------------
    // normalizeWhitespace
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("normalizeWhitespace: collapses multiple spaces and trims")
    void normalizeWhitespace_collapsesSpaces() {
        assertThat(CommonUtils.normalizeWhitespace("  hello   world  ")).isEqualTo("hello world");
    }

    @Test
    @DisplayName("normalizeWhitespace: null returns empty string")
    void normalizeWhitespace_null() {
        assertThat(CommonUtils.normalizeWhitespace(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // trimTrailingSlash
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("trimTrailingSlash: removes exactly one trailing slash")
    void trimTrailingSlash_removesSlash() {
        assertThat(CommonUtils.trimTrailingSlash("https://example.com/")).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("trimTrailingSlash: no change when no trailing slash")
    void trimTrailingSlash_noChange() {
        assertThat(CommonUtils.trimTrailingSlash("https://example.com")).isEqualTo("https://example.com");
    }

    @Test
    @DisplayName("trimTrailingSlash: null returns empty string")
    void trimTrailingSlash_null() {
        assertThat(CommonUtils.trimTrailingSlash(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // extractJsonObject
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("extractJsonObject: extracts JSON from markdown code block")
    void extractJsonObject_fromMarkdown() {
        String raw = "```json\n{\"key\": \"value\"}\n```";
        assertThat(CommonUtils.extractJsonObject(raw)).isEqualTo("{\"key\": \"value\"}");
    }

    @Test
    @DisplayName("extractJsonObject: extracts JSON from plain text")
    void extractJsonObject_plainJson() {
        String raw = "Here is the answer: {\"intent\": \"document\"}";
        assertThat(CommonUtils.extractJsonObject(raw)).isEqualTo("{\"intent\": \"document\"}");
    }

    @Test
    @DisplayName("extractJsonObject: returns original text when no JSON object found")
    void extractJsonObject_noJson() {
        String raw = "no json here";
        assertThat(CommonUtils.extractJsonObject(raw)).isEqualTo("no json here");
    }

    // -------------------------------------------------------------------------
    // clamp
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({"0.5, 0.0, 1.0, 0.5, 0.5", "-1.0, 0.0, 1.0, 0.5, 0.0", "2.0, 0.0, 1.0, 0.5, 1.0"})
    @DisplayName("clamp: keeps value within [min, max]")
    void clamp_boundaryValues(double value, double min, double max, double fallback, double expected) {
        assertThat(CommonUtils.clamp(value, min, max, fallback)).isEqualTo(expected);
    }

    @Test
    @DisplayName("clamp: returns fallback for NaN")
    void clamp_nan() {
        assertThat(CommonUtils.clamp(Double.NaN, 0.0, 1.0, 0.5)).isEqualTo(0.5);
    }

    // -------------------------------------------------------------------------
    // doubleValue
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("doubleValue: converts Number to double")
    void doubleValue_number() {
        assertThat(CommonUtils.doubleValue(3.14f)).isEqualTo(3.14f, within(0.001));
    }

    @Test
    @DisplayName("doubleValue: parses string number")
    void doubleValue_string() {
        assertThat(CommonUtils.doubleValue("0.95")).isEqualTo(0.95);
    }

    @Test
    @DisplayName("doubleValue: returns 0.0 for null or unparseable string")
    void doubleValue_nullAndInvalid() {
        assertThat(CommonUtils.doubleValue(null)).isEqualTo(0.0);
        assertThat(CommonUtils.doubleValue("abc")).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // stringList
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("stringList: converts List<Object> to List<String>")
    void stringList_converts() {
        List<String> result = CommonUtils.stringList(List.of("a", "b", "c"));
        assertThat(result).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("stringList: returns empty list for non-list input")
    void stringList_nonList() {
        assertThat(CommonUtils.stringList("not a list")).isEmpty();
        assertThat(CommonUtils.stringList(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // significantTokens / tokenOverlapRatio
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("significantTokens: splits on non-word chars and filters short tokens")
    void significantTokens_filtersShortTokens() {
        Set<String> tokens = CommonUtils.significantTokens("the cat and the dog");
        // "the" (3), "cat" (3), "and" (3), "dog" (3) all pass (>2 chars)
        assertThat(tokens).contains("cat", "dog");
    }

    @Test
    @DisplayName("tokenOverlapRatio: 100% overlap for identical strings")
    void tokenOverlapRatio_identical() {
        assertThat(CommonUtils.tokenOverlapRatio("hello world test", "hello world test")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("tokenOverlapRatio: 0.0 for completely different strings")
    void tokenOverlapRatio_noOverlap() {
        assertThat(CommonUtils.tokenOverlapRatio("apple banana", "xyz abc")).isEqualTo(0.0);
    }

    @Test
    @DisplayName("tokenOverlapRatio: 0.0 when left side has no significant tokens")
    void tokenOverlapRatio_emptyLeft() {
        assertThat(CommonUtils.tokenOverlapRatio("ab", "apple")).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // filename
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filename: extracts last segment from slash-delimited path")
    void filename_fromPath() {
        assertThat(CommonUtils.filename("folder/sub/file.pdf")).isEqualTo("file.pdf");
    }

    @Test
    @DisplayName("filename: returns full string when no slash found")
    void filename_noSlash() {
        assertThat(CommonUtils.filename("file.pdf")).isEqualTo("file.pdf");
    }

    @Test
    @DisplayName("filename: null input returns empty string")
    void filename_null() {
        assertThat(CommonUtils.filename(null)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // appendPageFragment
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("appendPageFragment: appends #page=N to URL")
    void appendPageFragment_valid() {
        assertThat(CommonUtils.appendPageFragment("https://example.com/doc.pdf", 3))
                .isEqualTo("https://example.com/doc.pdf#page=3");
    }

    @Test
    @DisplayName("appendPageFragment: returns original URL for page < 1")
    void appendPageFragment_invalidPage() {
        assertThat(CommonUtils.appendPageFragment("https://example.com/doc.pdf", 0))
                .isEqualTo("https://example.com/doc.pdf");
    }

    @Test
    @DisplayName("appendPageFragment: returns original URL for null URL")
    void appendPageFragment_nullUrl() {
        assertThat(CommonUtils.appendPageFragment(null, 1)).isNull();
    }

    // -------------------------------------------------------------------------
    // newChatId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("newChatId: returns unique IDs with chat_ prefix")
    void newChatId_unique() {
        String id1 = CommonUtils.newChatId();
        String id2 = CommonUtils.newChatId();
        assertThat(id1).startsWith("chat_");
        assertThat(id2).startsWith("chat_");
        assertThat(id1).isNotEqualTo(id2);
    }

    // -------------------------------------------------------------------------
    // toStringObjectMap
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("toStringObjectMap: converts Map<?> to Map<String, Object>")
    void toStringObjectMap_convertsKeys() {
        Map<Integer, String> raw = Map.of(1, "a", 2, "b");
        Map<String, Object> result = CommonUtils.toStringObjectMap(raw);
        assertThat(result).containsKeys("1", "2");
    }

    @Test
    @DisplayName("toStringObjectMap: returns empty map for non-map input")
    void toStringObjectMap_nonMap() {
        assertThat(CommonUtils.toStringObjectMap("not a map")).isEmpty();
    }

    // -------------------------------------------------------------------------
    // parseLlmJson
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("parseLlmJson: deserialises JSON string into target class")
    void parseLlmJson_valid() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String raw = "{\"key\":\"value\"}";
        Map result = CommonUtils.parseLlmJson(raw, Map.class, mapper);
        assertThat(result).containsEntry("key", "value");
    }

    @Test
    @DisplayName("parseLlmJson: strips markdown code fence before deserialising")
    void parseLlmJson_stripsMarkdown() throws Exception {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String raw = "```json\n{\"intent\":\"document\"}\n```";
        Map result = CommonUtils.parseLlmJson(raw, Map.class, mapper);
        assertThat(result).containsEntry("intent", "document");
    }

    @Test
    @DisplayName("parseLlmJson: throws for blank content")
    void parseLlmJson_blank_throws() {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThatThrownBy(() -> CommonUtils.parseLlmJson("  ", Map.class, mapper))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
