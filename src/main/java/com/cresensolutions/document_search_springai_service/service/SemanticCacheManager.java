package com.cresensolutions.document_search_springai_service.service;

import java.util.List;

public interface SemanticCacheManager {

    /**
     * Attempts to retrieve a list of cached embeddings for a given column.
     * Returns null if no cache exists or if it has expired.
     *
     * @param viewName The name of the database view
     * @param column   The name of the categorical column
     * @return List of embeddings (represented as double arrays) or null
     */
    List<float[]> getCachedEmbeddings(String viewName, String column);

    /**
     * Caches the list of embeddings for a given column with a TTL.
     *
     * @param viewName   The name of the database view
     * @param column     The name of the categorical column
     * @param embeddings The embeddings to cache
     */
    void setCachedEmbeddings(String viewName, String column, List<float[]> embeddings);

    /**
     * Invalidates the cache for a given view and column.
     */
    void invalidateCache(String viewName, String column);
}
