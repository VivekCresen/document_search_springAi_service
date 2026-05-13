package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;

import java.util.List;
import java.util.Map;

/**
 * Provides a thread-safe, periodically refreshed snapshot of the DB-backed schema registry.
 * Mirrors Python config.py VIEW_SCHEMAS / VIEW_DESCRIPTIONS / VIEW_ROUTING_METADATA.
 */
public interface SchemaRegistryService {

    /** All active view entries (schema + description + routing metadata). */
    List<ViewRegistryEntry> getActiveViews();

    /** Map of viewName → description for intent classification prompt. */
    Map<String, String> getViewDescriptions();

    /** Map of viewName → routing metadata (confidence_keywords, categorical_columns, …). */
    Map<String, Map<String, Object>> getViewRoutingMetadata();

    /** Force an immediate reload from the database. */
    void refresh();
}
