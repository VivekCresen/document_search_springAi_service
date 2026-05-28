package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;
import java.util.UUID;

/**
 * Contract for the base secured query workflow that converts a user question into a response payload.
 */
public interface SecuredUnifiedQueryWorkflow {

    /**
     * Runs the workflow with the already-resolved conversation context and user identity.
     */
    Map<String, Object> processQuestion(
            String question,
            String username,
            String conversationContext,
            String conversationId,
            Integer questionId,
            UUID userId,
            java.util.List<String> attachedFiles
    );

    default Map<String, Object> processQuestion(
            String question,
            String username,
            String conversationContext,
            String conversationId,
            Integer questionId,
            UUID userId
    ) {
        return processQuestion(question, username, conversationContext, conversationId, questionId, userId, null);
    }
}
