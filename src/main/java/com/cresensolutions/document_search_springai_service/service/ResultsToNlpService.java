package com.cresensolutions.document_search_springai_service.service;

import java.util.List;
import java.util.Map;

/**
 * Converts SQL query results to natural-language answers or table intro text.
 * Mirrors Python db_search.ResultsToNLPConverter.
 */
public interface ResultsToNlpService {

    /**
     * Generate a natural-language answer from query results.
     *
     * @param question   original user question
     * @param rows       query result rows
     * @param isHybrid   true when called from the hybrid path (summary only, no table)
     * @return natural-language answer string
     */
    String generateNlpAnswer(String question, List<Map<String, Object>> rows, boolean isHybrid);

    /**
     * Generate a short intro sentence for a table response.
     *
     * @param question original user question
     * @param rowCount number of records returned
     * @return intro sentence
     */
    String generateTableIntro(String question, int rowCount);
}
