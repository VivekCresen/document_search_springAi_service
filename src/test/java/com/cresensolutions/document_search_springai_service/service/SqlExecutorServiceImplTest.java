package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.SqlExecutionResult;
import com.cresensolutions.document_search_springai_service.service.Impl.SqlExecutorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SqlExecutorServiceImpl Tests")
class SqlExecutorServiceImplTest {

    @Mock
    NamedParameterJdbcTemplate jdbcTemplate;

    SqlExecutorServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SqlExecutorServiceImpl(jdbcTemplate);
    }

    @Test
    @DisplayName("execute: returns failure for null SQL")
    void execute_nullSql() {
        SqlExecutionResult result = service.execute(null);
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Empty SQL");
    }

    @Test
    @DisplayName("execute: returns failure for blank SQL")
    void execute_blankSql() {
        SqlExecutionResult result = service.execute("   ");
        assertThat(result.success()).isFalse();
    }

    @Test
    @DisplayName("execute: rejects non-SELECT SQL")
    void execute_nonSelect() {
        SqlExecutionResult result = service.execute("DELETE FROM orders");
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Only SELECT");
    }

    @Test
    @DisplayName("execute: executes SELECT and returns rows successfully")
    void execute_selectSuccess() {
        List<Map<String, Object>> dbRows = List.of(
                Map.of("id", 1, "name", "Alice"),
                Map.of("id", 2, "name", "Bob")
        );
        when(jdbcTemplate.queryForList(anyString(), any(Map.class))).thenReturn(dbRows);

        SqlExecutionResult result = service.execute("SELECT id, name FROM users");
        assertThat(result.success()).isTrue();
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rowCount()).isEqualTo(2);
        assertThat(result.errorMessage()).isNull();
    }

    @Test
    @DisplayName("execute: returns failure when JDBC throws exception")
    void execute_jdbcException() {
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        SqlExecutionResult result = service.execute("SELECT 1");
        assertThat(result.success()).isFalse();
        assertThat(result.errorMessage()).contains("Connection refused");
    }

    @Test
    @DisplayName("execute: serializes LocalDate to string")
    void execute_serializesLocalDate() {
        LocalDate date = LocalDate.of(2025, 1, 1);
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("created_at", date)));

        SqlExecutionResult result = service.execute("SELECT created_at FROM t");
        assertThat(result.success()).isTrue();
        assertThat(result.rows().get(0).get("created_at")).isEqualTo("2025-01-01");
    }

    @Test
    @DisplayName("execute: serializes LocalDateTime to string")
    void execute_serializesLocalDateTime() {
        LocalDateTime dt = LocalDateTime.of(2025, 1, 1, 12, 0);
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("ts", dt)));

        SqlExecutionResult result = service.execute("SELECT ts FROM t");
        assertThat(result.rows().get(0).get("ts")).isEqualTo("2025-01-01T12:00");
    }

    @Test
    @DisplayName("execute: serializes OffsetDateTime to string")
    void execute_serializesOffsetDateTime() {
        OffsetDateTime odt = OffsetDateTime.of(2025, 1, 1, 12, 0, 0, 0, ZoneOffset.UTC);
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("ts", odt)));

        SqlExecutionResult result = service.execute("SELECT ts FROM t");
        assertThat(result.rows().get(0).get("ts")).isEqualTo("2025-01-01T12:00Z");
    }

    @Test
    @DisplayName("execute: serializes BigDecimal to plain string")
    void execute_serializesBigDecimal() {
        BigDecimal bd = new BigDecimal("12345.67");
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("amount", bd)));

        SqlExecutionResult result = service.execute("SELECT amount FROM t");
        assertThat(result.rows().get(0).get("amount")).isEqualTo("12345.67");
    }

    @Test
    @DisplayName("execute: serializes byte[] to '[binary]'")
    void execute_serializesByteArray() {
        byte[] bytes = {1, 2, 3};
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(Map.of("data", bytes)));

        SqlExecutionResult result = service.execute("SELECT data FROM t");
        assertThat(result.rows().get(0).get("data")).isEqualTo("[binary]");
    }

    @Test
    @DisplayName("execute: handles null column value gracefully")
    void execute_nullColumnValue() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("col", null);
        when(jdbcTemplate.queryForList(anyString(), any(Map.class)))
                .thenReturn(List.of(row));

        SqlExecutionResult result = service.execute("SELECT col FROM t");
        assertThat(result.success()).isTrue();
        assertThat(result.rows().get(0).get("col")).isNull();
    }
}
