package com.cresensolutions.document_search_springai_service.dto;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of one registered DB view used by the NL-to-SQL pipeline.
 */
@Builder
public record ViewRegistryEntry(
        String viewName,
        String description,
        Map<String, Object> schemaJson,
        List<String> confidenceKeywords,
        List<String> categoricalColumns
) {
}
