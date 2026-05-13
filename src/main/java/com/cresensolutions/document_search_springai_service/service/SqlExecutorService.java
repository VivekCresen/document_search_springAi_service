package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.SqlExecutionResult;

/**
 * Executes a read-only SQL query and returns the rows as a list of maps.
 * Mirrors Python db_search.SQLQueryExecutor.
 */
public interface SqlExecutorService {

    SqlExecutionResult execute(String sql);
}
