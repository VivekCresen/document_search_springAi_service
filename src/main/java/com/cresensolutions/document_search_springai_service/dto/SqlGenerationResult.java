package com.cresensolutions.document_search_springai_service.dto;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Output of the SQL generation step.
 * Mirrors the 5-tuple returned by Python MMSQLConverter.generate_sql_query().
 */
@Builder
public record SqlGenerationResult(
        /** Primary SQL (aggregate for hybrid, detail for others). Null on failure or clarification. */
        String primarySql,
        /** Detail SQL — only populated for hybrid response type. */
        String detailSql,
        /** Human-readable column labels: [{name, proxy}, …]. */
        List<Map<String, String>> selectedColumns,
        /** Explanation / error message / clarification question. */
        String explanation,
        /** True when the LLM returned a clarification question instead of SQL. */
        boolean isClarification
) {
}
