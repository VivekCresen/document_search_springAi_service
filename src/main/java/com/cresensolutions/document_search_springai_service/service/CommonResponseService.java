package com.cresensolutions.document_search_springai_service.service;

import java.util.Optional;

/**
 * Service to handle high-frequency, non-contextual queries using hardcoded responses.
 * This helps in saving LLM tokens and reducing latency for common interactions.
 */
public interface CommonResponseService {

    /**
     * Checks if the given question has a predefined hardcoded response.
     *
     * @param question the user's question
     * @return an Optional containing the response if found, or empty otherwise
     */
    Optional<String> getCommonResponse(String question);
}
