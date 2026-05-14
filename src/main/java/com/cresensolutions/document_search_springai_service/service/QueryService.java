package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.EnvelopeRequest;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;

import java.util.concurrent.CompletableFuture;

/**
 * Service interface for processing search queries.
 * Handles the asynchronous execution of query workflows and response construction.
 */
public interface QueryService {

    /**
     * Processes a search query contained in an envelope request.
     * Executes the workflow asynchronously and returns the formatted response.
     *
     * @param request the search request envelope
     * @return a future containing the search response envelope
     */
    CompletableFuture<EnvelopeResponse> processQuery(EnvelopeRequest request);
}
