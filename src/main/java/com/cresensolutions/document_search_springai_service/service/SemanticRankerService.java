package com.cresensolutions.document_search_springai_service.service;

import java.util.List;
import java.util.Map;

public interface SemanticRankerService {

    /**
     * Finds the top matching categorical values for a given question.
     *
     * @param question        The user's question
     * @param viewName        The database view name
     * @param routingMetadata The routing metadata containing categorical column info
     * @return Map of column name to list of top matching values (semantic hints)
     */
    Map<String, List<String>> rankCategoricalValues(String question, String viewName, Map<String, Object> routingMetadata);
}
