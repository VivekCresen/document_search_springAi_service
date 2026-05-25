package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.config.AzureSearchProperty;
import com.cresensolutions.document_search_springai_service.config.ChatClientConfig;
import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.IntentClassification;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;
import com.cresensolutions.document_search_springai_service.service.SecuredIntentClassifier;
import com.cresensolutions.document_search_springai_service.service.UserAccessService;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implementation of the Secured Intent Classifier.
 * 
 * This service performs two critical tasks:
 * 1. Security-aware pre-fetching of documents from Azure Search (filtering metadata).
 * 2. Classifying user intent into "database", "document", or "general" using an LLM.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecuredIntentClassifierImpl implements SecuredIntentClassifier {

    private static final List<String> SEARCH_FIELDS = List.of(
            Common.SEARCH_FIELD_EXAMPLE_QUERIES,
            Common.SEARCH_FIELD_TOPICS,
            Common.SEARCH_FIELD_INTENT_SIGNALS,
            Common.SEARCH_FIELD_CONTENT
    );
    private static final List<String> SELECT_FIELDS = List.of(
            Common.SEARCH_FIELD_SOURCE,
            Common.SEARCH_FIELD_FILEPATH,
            Common.SEARCH_FIELD_BLOB_URI,
            Common.SEARCH_FIELD_CONTENT,
            Common.SEARCH_FIELD_PAGE_NUMBER,
            Common.SEARCH_FIELD_TOPICS,
            Common.SEARCH_FIELD_EXAMPLE_QUERIES,
            Common.SEARCH_FIELD_INTENT_SIGNALS,
            Common.SEARCH_FIELD_FOLDER_ID,
            Common.SEARCH_FIELD_METADATA
    );
    private static final TypeReference<List<DiPageSpan>> DI_SPAN_LIST = new TypeReference<>() {
    };

    private final AzureSearchProperty azureSearchProperty;
    private final UserAccessService userAccessService;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;
    private final Map<String, ChatClient> chatClients;
    private RestClient restClient;

    /**
     * Initializes the underlying RestClient client.
     */
    @PostConstruct
    public void initialize() {
        this.restClient = restClientBuilder.build();
    }

    @Override
    public List<SearchResultDocument> searchRelevantDocumentsWithSecurity(String question, String searchFilter, int topK) {
        if (!hasAzureSearchConfig() || question == null || question.isBlank()) {
            return Collections.emptyList();
        }

        try {
            Map<String, Object> request = buildSearchRequest(question, searchFilter, Math.max(1, topK));

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient
                    .post()
                    .uri(buildSearchUrl())
                    .header(Common.AZURE_API_KEY_HEADER, azureSearchProperty.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            Object rawResults = response == null ? null : response.get("value");
            if (!(rawResults instanceof List<?> results)) {
                return Collections.emptyList();
            }
            return results.stream()
                    .filter(Map.class::isInstance)
                    .map(result -> toSearchResultDocument((Map<?, ?>) result))
                    .toList();
        } catch (Exception e) {
            log.warn("Secured metadata document pre-fetch failed; continuing without prefetched docs", e);
            return Collections.emptyList();
        }
    }

    @Override
    public IntentClassification classifyIntent(
            String question,
            String username,
            List<SearchResultDocument> prefetchedDocs
    ) {
        List<SearchResultDocument> documents = prefetchedDocs == null ? Collections.emptyList() : prefetchedDocs;
        if (isGreeting(question)) {
            return baseClassification("general", null, 1.0, "Greeting", documents);
        }

        String prompt = buildClassificationPrompt(question, username, documents);
        ChatClient chatClient = chatClients.get(ChatClientConfig.INTENT_CLASSIFIER_CHAT_CLIENT);
        if (chatClient != null) {
            try {
                String content = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                return parseClassification(content, documents);
            } catch (Exception e) {
                log.warn("Spring AI intent classification failed; using document fallback", e);
            }
        }
        return baseClassification("document", null, 0.3, "No LLM configuration available", documents);
    }

    /**
     * Builds the query request payload payload for Azure AI Search engine.
     *
     * @param question user text query
     * @param searchFilter OData security filter
     * @param topK maximum candidate retrieval limit
     * @return search request payload map
     */
    private Map<String, Object> buildSearchRequest(String question, String searchFilter, int topK) {
        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("search", question);
        request.put("top", topK);
        request.put("searchFields", String.join(",", SEARCH_FIELDS));
        request.put("select", String.join(",", SELECT_FIELDS));
        if (searchFilter != null && !searchFilter.isBlank()) {
            request.put("filter", searchFilter);
        }
        return request;
    }

    /**
     * Converts a raw map record from Azure Search hits into a structured SearchResultDocument.
     *
     * @param result raw search match hit map
     * @return SearchResultDocument structure representation
     */
    private SearchResultDocument toSearchResultDocument(Map<?, ?> result) {
        String filepath = CommonUtils.stringValue(result.get(Common.SEARCH_FIELD_FILEPATH));
        String blobUri = CommonUtils.stringValue(result.containsKey(Common.SEARCH_FIELD_BLOB_URI)
                ? result.get(Common.SEARCH_FIELD_BLOB_URI)
                : filepath);
        Object page = result.containsKey(Common.SEARCH_FIELD_PAGE_NUMBER)
                ? result.get(Common.SEARCH_FIELD_PAGE_NUMBER)
                : Common.NOT_AVAILABLE;
        return SearchResultDocument.builder()
                .source(CommonUtils.defaultString(result.get(Common.SEARCH_FIELD_SOURCE), Common.UNKNOWN))
                .filepath(filepath)
                .blobUri(blobUri)
                .content(CommonUtils.stringValue(result.get(Common.SEARCH_FIELD_CONTENT)))
                .page(page)
                .score(CommonUtils.doubleValue(result.get(Common.SEARCH_SCORE)))
                .topics(CommonUtils.stringList(result.get(Common.SEARCH_FIELD_TOPICS)))
                .exampleQueries(CommonUtils.stringList(result.get(Common.SEARCH_FIELD_EXAMPLE_QUERIES)))
                .intentSignals(CommonUtils.stringList(result.get(Common.SEARCH_FIELD_INTENT_SIGNALS)))
                .folderId(CommonUtils.defaultString(result.get(Common.SEARCH_FIELD_FOLDER_ID), "0"))
                .diPageSpans(extractDiPageSpans(result.get(Common.SEARCH_FIELD_METADATA)))
                .build();
    }

    /**
     * Extracts and converts the unstructured page layout metadata JSON into structured PageSpan list definitions.
     *
     * @param metadata raw metadata block
     * @return page span list
     */
    private List<DiPageSpan> extractDiPageSpans(Object metadata) {
        if (metadata == null) {
            return Collections.emptyList();
        }
        try {
            Map<?, ?> parsedMetadata;
            if (metadata instanceof String metadataJson) {
                if (metadataJson.isBlank()) {
                    return Collections.emptyList();
                }
                parsedMetadata = objectMapper.readValue(metadataJson, Map.class);
            } else if (metadata instanceof Map<?, ?> metadataMap) {
                parsedMetadata = metadataMap;
            } else {
                return Collections.emptyList();
            }

            Object spans = parsedMetadata.get(Common.SEARCH_FIELD_DI_PAGE_SPANS);
            if (spans == null) {
                return Collections.emptyList();
            }
            return objectMapper.convertValue(spans, DI_SPAN_LIST);
        } catch (IllegalArgumentException | JsonProcessingException e) {
            return Collections.emptyList();
        }
    }

    /**
     * Constructs the structural classification instruction prompt context.
     *
     * @param question user query question
     * @param username query requester name
     * @param documents metadata search documents pre-fetched
     * @return prompt text instruction
     */
    private String buildClassificationPrompt(String question, String username, List<SearchResultDocument> documents) {
        return """
                Analyze the user's question and classify it.

                USERNAME: "%s"
                USER QUESTION: "%s"
                ACCESSIBLE DOCUMENT PREFETCH COUNT: %d

                PRIMARY INTENT:
                - "database": The user wants to query, count, list, filter, compare, or aggregate structured records.
                - "document": The user asks about specific policies, regulations, definitions, or procedures likely found in the PROVIDED context documents.
                - "general": Casual conversation, greetings, AI capability questions, or broad technical/general knowledge questions (e.g., "What is Java?", "How do I use Excel?") that are not specific to the organization's private data.

                RESPONSE TYPE, only for database intent:
                - "nlp_summary": facts, counts, or specific lookups where a text answer is enough
                - "detailed_records": explicit list/table/records/export requests
                - "hybrid": both a summary and underlying records

                Return only a JSON object:
                {
                  "intent": "database" | "document" | "general",
                  "response_type": "detailed_records" | "nlp_summary" | "hybrid" | null,
                  "confidence": 0.0 to 1.0,
                  "reasoning": "brief explanation"
                }
                """.formatted(username, question, documents.size());
    }

    /**
     * Parses the intent classification JSON response payload from LLM into IntentClassification.
     *
     * @param rawContent raw JSON content from LLM
     * @param documents pre-fetched documents
     * @return parsed IntentClassification object
     */
    private IntentClassification parseClassification(String rawContent, List<SearchResultDocument> documents) {
        try {
            IntentClassification parsed = CommonUtils.parseLlmJson(rawContent, IntentClassification.class, objectMapper);
            String intent = normalizeIntent(parsed.getIntent());
            String responseType = Common.INTENT_DATABASE.equals(intent) ? normalizeResponseType(parsed.getResponseType()) : null;
            return IntentClassification.builder()
                    .intent(intent)
                    .responseType(responseType)
                    .confidence(CommonUtils.clamp(parsed.getConfidence(), 0.0, 1.0, 0.5))
                    .reasoning(parsed.getReasoning() == null ? Common.EMPTY : parsed.getReasoning())
                    .prefetchedDocs(documents)
                    .build();
        } catch (Exception e) {
            return baseClassification("document", null, 0.3, "Could not parse classifier response", documents);
        }
    }

    /**
     * Constructs a default baseline IntentClassification on error or fallback triggers.
     *
     * @param intent fallback intent classification
     * @param responseType fallback database layout format
     * @param confidence numeric probability
     * @param reasoning text reasoning block
     * @param documents baseline documents list
     * @return built IntentClassification record
     */
    private IntentClassification baseClassification(
            String intent,
            String responseType,
            double confidence,
            String reasoning,
            List<SearchResultDocument> documents
    ) {
        return IntentClassification.builder()
                .intent(intent)
                .responseType(responseType)
                .confidence(confidence)
                .reasoning(reasoning)
                .prefetchedDocs(documents)
                .build();
    }

    /**
     * Resolves if the user question is a standard polite greeting.
     *
     * @param text raw question text
     * @return true if matches common greetings
     */
    private boolean isGreeting(String text) {
        if (text == null) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT).trim();
        return List.of("hello", "hi", "hey", "greetings", "good morning", "good afternoon", "good evening")
                .contains(normalized);
    }

    /**
     * Validates that all required Azure Cognitive Search endpoint configs are defined.
     *
     * @return true if valid search properties exist
     */
    private boolean hasAzureSearchConfig() {
        return CommonUtils.hasText(azureSearchProperty.getEndpoint())
                && CommonUtils.hasText(azureSearchProperty.getApiKey())
                && CommonUtils.hasText(azureSearchProperty.getIndexName());
    }

    /**
     * Dynamically compiles the Azure search query endpoint URL string.
     *
     * @return search endpoint url
     */
    private String buildSearchUrl() {
        return CommonUtils.buildAzureSearchUrl(
                azureSearchProperty.getEndpoint(),
                azureSearchProperty.getIndexName(),
                azureSearchProperty.getApiVersion()
        );
    }

    /**
     * Sanitizes and normalizes the intent category value.
     *
     * @param intent raw intent category
     * @return normalized intent category
     */
    private String normalizeIntent(String intent) {
        String value = intent == null ? Common.EMPTY : intent.toLowerCase(Locale.ROOT).trim();
        if (Common.VALID_INTENTS.contains(value)) {
            return value;
        }
        return Common.INTENT_DOCUMENT;
    }

    /**
     * Sanitizes and normalizes the response database presentation layout.
     *
     * @param responseType raw layout representation
     * @return normalized presentation layout
     */
    private String normalizeResponseType(String responseType) {
        String value = responseType == null ? Common.EMPTY : responseType.toLowerCase(Locale.ROOT).trim();
        if (Common.VALID_DATABASE_RESPONSE_TYPES.contains(value)) {
            return value;
        }
        return Common.RESPONSE_TYPE_NLP_SUMMARY;
    }
}
