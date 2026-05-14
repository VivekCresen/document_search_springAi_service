package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.service.Impl.SemanticRankerServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticRankerServiceImpl Tests")
class SemanticRankerServiceImplTest {

    @Mock
    ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Mock
    EmbeddingModel embeddingModel;

    @Mock
    SemanticCacheManager semanticCacheManager;

    @InjectMocks
    SemanticRankerServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
    }

    @Test
    @DisplayName("rankCategoricalValues: returns empty if no EmbeddingModel available")
    void rankCategoricalValues_noModel() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);
        Map<String, List<String>> result = service.rankCategoricalValues("q", "v", Map.of("categorical_columns", List.of("c")));
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("rankCategoricalValues: returns empty if routingMetadata lacks categorical_columns")
    void rankCategoricalValues_noColumns() {
        Map<String, List<String>> result = service.rankCategoricalValues("q", "v", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("rankCategoricalValues: uses cached embeddings and computes similarity")
    void rankCategoricalValues_cachedEmbeddings() {
        Map<String, Object> routing = Map.of(
                "categorical_columns", List.of("status"),
                "column_values", Map.of("status", List.of("Active", "Inactive"))
        );

        float[] questionEmbed = {1.0f, 0.0f};
        when(embeddingModel.embed("q")).thenReturn(questionEmbed);

        // Active is exactly the same vector, Inactive is orthogonal
        List<float[]> cached = List.of(new float[]{1.0f, 0.0f}, new float[]{0.0f, 1.0f});
        when(semanticCacheManager.getCachedEmbeddings("v", "status")).thenReturn(cached);

        Map<String, List<String>> result = service.rankCategoricalValues("q", "v", routing);

        assertThat(result).containsKey("status");
        assertThat(result.get("status")).containsExactly("Active"); // Similarity 1.0 > 0.75
    }

    @Test
    @DisplayName("rankCategoricalValues: caches new embeddings if not cached")
    void rankCategoricalValues_generatesAndCachesEmbeddings() {
        Map<String, Object> routing = Map.of(
                "categorical_columns", List.of("type"),
                "column_values", Map.of("type", List.of("A"))
        );

        when(embeddingModel.embed("q")).thenReturn(new float[]{1.0f});
        when(semanticCacheManager.getCachedEmbeddings("v", "type")).thenReturn(null);
        when(embeddingModel.embed("A")).thenReturn(new float[]{1.0f});

        Map<String, List<String>> result = service.rankCategoricalValues("q", "v", routing);

        assertThat(result).containsKey("type");
        verify(semanticCacheManager).setCachedEmbeddings(eq("v"), eq("type"), argThat(list -> !list.isEmpty()));
    }

    @Test
    @DisplayName("rankCategoricalValues: skips column if value embedding fails")
    void rankCategoricalValues_embeddingFails() {
        Map<String, Object> routing = Map.of(
                "categorical_columns", List.of("type"),
                "column_values", Map.of("type", List.of("A"))
        );

        when(embeddingModel.embed("q")).thenReturn(new float[]{1.0f});
        when(semanticCacheManager.getCachedEmbeddings("v", "type")).thenReturn(null);
        when(embeddingModel.embed("A")).thenThrow(new RuntimeException("API error"));

        Map<String, List<String>> result = service.rankCategoricalValues("q", "v", routing);

        assertThat(result).isEmpty();
    }
}
