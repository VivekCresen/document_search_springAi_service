package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.SemanticCacheManager;
import com.cresensolutions.document_search_springai_service.service.SemanticRankerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticRankerServiceImpl implements SemanticRankerService {

    private static final int MAX_HINTS_PER_COLUMN = 5;

    // Use ObjectProvider to optionally autowire EmbeddingModel so context loads even if absent
    private final org.springframework.beans.factory.ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final SemanticCacheManager semanticCacheManager;

    /**
     * Ranks potential categorical cell values in database columns according to semantic similarity
     * with the user's question, returning top matching values to feed as prompt filters.
     *
     * @param question user query
     * @param viewName the database view name context
     * @param routingMetadata routing metadata containing lists of categorical columns
     * @return a map of ranked categorical values grouped by column name
     */
    @Override
    public Map<String, List<String>> rankCategoricalValues(String question, String viewName, Map<String, Object> routingMetadata) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            log.warn("No EmbeddingModel available; skipping semantic ranking.");
            return Collections.emptyMap();
        }

        if (routingMetadata == null || !routingMetadata.containsKey("categorical_columns")) {
            return Collections.emptyMap();
        }

        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) routingMetadata.get("categorical_columns");
        if (columns == null || columns.isEmpty()) {
            return Collections.emptyMap();
        }

        float[] questionEmbedding;
        try {
            questionEmbedding = embeddingModel.embed(question);
        } catch (Exception e) {
            log.warn("Failed to embed question for semantic ranking", e);
            return Collections.emptyMap();
        }

        Map<String, List<String>> semanticHints = new HashMap<>();

        for (String col : columns) {
            List<String> validValues = extractValidValues(routingMetadata, col);
            if (validValues == null || validValues.isEmpty()) {
                continue;
            }

            List<float[]> valueEmbeddings = semanticCacheManager.getCachedEmbeddings(viewName, col);
            if (valueEmbeddings == null || valueEmbeddings.size() != validValues.size()) {
                valueEmbeddings = new ArrayList<>();
                boolean allEmbedded = true;
                for (String val : validValues) {
                    try {
                        valueEmbeddings.add(embeddingModel.embed(val));
                    } catch (Exception e) {
                        log.warn("Failed to embed categorical value: {}", val, e);
                        allEmbedded = false;
                        break;
                    }
                }
                if (allEmbedded) {
                    semanticCacheManager.setCachedEmbeddings(viewName, col, valueEmbeddings);
                } else {
                    continue; // Skip ranking for this column on error
                }
            }

            List<RankedValue> ranked = new ArrayList<>();
            for (int i = 0; i < validValues.size(); i++) {
                double similarity = cosineSimilarity(questionEmbedding, valueEmbeddings.get(i));
                ranked.add(new RankedValue(validValues.get(i), similarity));
            }

            ranked.sort((a, b) -> Double.compare(b.similarity, a.similarity)); // Descending

            List<String> topValues = new ArrayList<>();
            for (int i = 0; i < Math.min(MAX_HINTS_PER_COLUMN, ranked.size()); i++) {
                if (ranked.get(i).similarity > 0.75) { // Threshold
                    topValues.add(ranked.get(i).value);
                }
            }

            if (!topValues.isEmpty()) {
                semanticHints.put(col, topValues);
            }
        }

        return semanticHints;
    }

    /**
     * Extracts allowed categorical values for a given column from view metadata schema config.
     *
     * @param routingMetadata metadata config map
     * @param col target column key
     * @return list of allowable categorical values
     */
    @SuppressWarnings("unchecked")
    private List<String> extractValidValues(Map<String, Object> routingMetadata, String col) {
        if (!routingMetadata.containsKey("column_values")) {
            return null;
        }
        Map<String, Object> colValuesMap = (Map<String, Object>) routingMetadata.get("column_values");
        if (colValuesMap != null && colValuesMap.containsKey(col)) {
            return (List<String>) colValuesMap.get(col);
        }
        return null;
    }

    /**
     * Computes the cosine similarity metric between two embedding vector arrays.
     *
     * @param vec1 first embedding vector
     * @param vec2 second embedding vector
     * @return similarity score between 0.0 and 1.0
     */
    private double cosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1.length != vec2.length) {
            return 0.0;
        }
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            normA += vec1[i] * vec1[i];
            normB += vec2[i] * vec2[i];
        }
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private record RankedValue(String value, double similarity) {
    }
}
