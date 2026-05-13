package com.cresensolutions.document_search_springai_service.dto;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Result of executing a SQL query against the database.
 */
@Builder
public record SqlExecutionResult(
        boolean success,
        List<Map<String, Object>> rows,
        int rowCount,
        String errorMessage
) {
    public static SqlExecutionResult failure(String message) {
        return SqlExecutionResult.builder()
                .success(false)
                .rows(List.of())
                .rowCount(0)
                .errorMessage(message)
                .build();
    }
}
