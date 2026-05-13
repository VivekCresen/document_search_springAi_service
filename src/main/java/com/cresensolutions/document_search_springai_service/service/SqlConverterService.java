package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.SqlGenerationResult;

import java.util.List;
import java.util.Map;

/**
 * Generates SQL from a natural-language question using the LLM.
 * Mirrors Python db_search.MMSQLConverter.
 */
public interface SqlConverterService {

    /**
     * @param question      standalone user question
     * @param responseType  "nlp_summary" | "detailed_records" | "hybrid"
     * @param viewName      fully-qualified view name to query
     * @param semanticHints optional map of column → top-ranked values from semantic cache
     * @return SQL generation result (may be a clarification instead of SQL)
     */
    SqlGenerationResult generateSql(
            String question,
            String responseType,
            String viewName,
            Map<String, List<String>> semanticHints
    );
}
