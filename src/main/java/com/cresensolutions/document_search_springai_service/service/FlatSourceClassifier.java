package com.cresensolutions.document_search_springai_service.service;

/**
 * Routes a natural-language question to the most relevant DB view name.
 * Mirrors Python db_search.FlatSourceClassifier.
 */
public interface FlatSourceClassifier {

    /**
     * @param question standalone user question
     * @return the fully-qualified view name (e.g. "demo.mm_activities_view")
     *         or null when no view is registered
     */
    String classify(String question);
}
