package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.dto.SqlExecutionResult;
import com.cresensolutions.document_search_springai_service.service.SqlExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes read-only SQL queries via JDBC.
 * Mirrors Python db_search.SQLQueryExecutor.execute_query().
 *
 * Security note: SQL is LLM-generated and constrained to SELECT on registered views.
 * The executor enforces read-only by rejecting any statement that is not a SELECT.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SqlExecutorServiceImpl implements SqlExecutorService {

    private static final int MAX_ROWS = 500;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Executes the given SELECT statement against the database, enforcing read-only constraints
     * and truncating the returned rows up to MAX_ROWS.
     *
     * @param sql target SQL SELECT statement string
     * @return the SqlExecutionResult containing rows, count, and success status
     */
    @Override
    public SqlExecutionResult execute(String sql) {
        if (sql == null || sql.isBlank()) {
            return SqlExecutionResult.failure("Empty SQL query.");
        }

        String trimmed = sql.strip();
        if (!trimmed.toUpperCase().startsWith("SELECT")) {
            log.warn("Rejected non-SELECT SQL: {}", trimmed.substring(0, Math.min(80, trimmed.length())));
            return SqlExecutionResult.failure("Only SELECT statements are permitted.");
        }

        try {
            List<Map<String, Object>> rawRows = jdbcTemplate.queryForList(trimmed, Collections.emptyMap());
            List<Map<String, Object>> rows = new ArrayList<>(Math.min(rawRows.size(), MAX_ROWS));
            for (Map<String, Object> row : rawRows) {
                if (rows.size() >= MAX_ROWS) {
                    break;
                }
                rows.add(serializeRow(row));
            }
            log.debug("SQL executed: {} row(s) returned", rows.size());
            return SqlExecutionResult.builder()
                    .success(true)
                    .rows(rows)
                    .rowCount(rows.size())
                    .build();
        } catch (Exception e) {
            log.warn("SQL execution failed: {}", e.getMessage());
            return SqlExecutionResult.failure(e.getMessage());
        }
    }

    /**
     * Converts JDBC types inside a row to clean JSON-serializable equivalent representations.
     * Mirrors Python convert_results_to_json_serializable().
     *
     * @param row target database query row map
     * @return converted JSON-serializable row map
     */
    private Map<String, Object> serializeRow(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>(row.size());
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            result.put(entry.getKey(), serializeValue(entry.getValue()));
        }
        return result;
    }

    /**
     * Casts and serializes a specific value safely based on temporal or precision types.
     *
     * @param value raw database column value
     * @return serializable representation of value
     */
    private Object serializeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate || value instanceof LocalDateTime
                || value instanceof OffsetDateTime) {
            return value.toString();
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof byte[]) {
            return "[binary]";
        }
        return value;
    }
}
