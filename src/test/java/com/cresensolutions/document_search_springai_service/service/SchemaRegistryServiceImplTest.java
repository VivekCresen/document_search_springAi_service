package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.DbSearchSchemaVersion;
import com.cresensolutions.document_search_springai_service.domain.DbSearchSource;
import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;
import com.cresensolutions.document_search_springai_service.repository.DbSearchSchemaVersionRepository;
import com.cresensolutions.document_search_springai_service.repository.DbSearchSourceRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.SchemaRegistryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SchemaRegistryServiceImpl Tests")
class SchemaRegistryServiceImplTest {

    @Mock
    DbSearchSourceRepository sourceRepository;

    @Mock
    DbSearchSchemaVersionRepository schemaVersionRepository;

    @InjectMocks
    SchemaRegistryServiceImpl service;

    @Test
    @DisplayName("refresh: populates active views from repositories")
    void refresh_populatesActiveViews() {
        DbSearchSource source1 = new DbSearchSource();
        source1.setId(1L);
        source1.setViewName("schema.view1");
        source1.setDescription("Desc 1");
        source1.setRoutingMetadata(Map.of("confidence_keywords", List.of("k1", "k2"), "categorical_columns", List.of("c1")));

        DbSearchSource source2 = new DbSearchSource();
        source2.setId(2L);
        source2.setViewName("schema.view2");
        // null description and metadata to test fallbacks

        when(sourceRepository.findByActiveTrue()).thenReturn(List.of(source1, source2));

        DbSearchSchemaVersion version1 = new DbSearchSchemaVersion();
        version1.setSchemaJson(Map.of("col1", "int"));
        when(schemaVersionRepository.findLatestActiveBySourceId(1L)).thenReturn(Optional.of(version1));
        when(schemaVersionRepository.findLatestActiveBySourceId(2L)).thenReturn(Optional.empty());

        service.refresh();

        List<ViewRegistryEntry> views = service.getActiveViews();
        assertThat(views).hasSize(2);

        ViewRegistryEntry v1 = views.get(0);
        assertThat(v1.viewName()).isEqualTo("schema.view1");
        assertThat(v1.description()).isEqualTo("Desc 1");
        assertThat(v1.schemaJson()).containsEntry("col1", "int");
        assertThat(v1.confidenceKeywords()).containsExactly("k1", "k2");
        assertThat(v1.categoricalColumns()).containsExactly("c1");

        ViewRegistryEntry v2 = views.get(1);
        assertThat(v2.viewName()).isEqualTo("schema.view2");
        assertThat(v2.description()).isEmpty();
        assertThat(v2.schemaJson()).isEmpty();
        assertThat(v2.confidenceKeywords()).isEmpty();
        assertThat(v2.categoricalColumns()).isEmpty();

        Map<String, String> descriptions = service.getViewDescriptions();
        assertThat(descriptions).containsEntry("schema.view1", "Desc 1");
        assertThat(descriptions).containsEntry("schema.view2", "");

        Map<String, Map<String, Object>> metadata = service.getViewRoutingMetadata();
        assertThat(metadata.get("schema.view1")).containsEntry("confidence_keywords", List.of("k1", "k2"));
        assertThat(metadata.get("schema.view2")).isEmpty();
    }

    @Test
    @DisplayName("init: ignores exception and starts empty")
    void init_handlesExceptionGracefully() {
        when(sourceRepository.findByActiveTrue()).thenThrow(new RuntimeException("DB offline"));
        
        service.init();
        
        assertThat(service.getActiveViews()).isEmpty();
        assertThat(service.getViewDescriptions()).isEmpty();
        assertThat(service.getViewRoutingMetadata()).isEmpty();
    }
}
