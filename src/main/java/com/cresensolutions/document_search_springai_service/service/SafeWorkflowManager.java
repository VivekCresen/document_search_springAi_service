package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for managing stateful search workflows.
 * Ensures that per-conversation context is maintained and cleaned up appropriately.
 */
public interface SafeWorkflowManager {

    /**
     * Processes a single question synchronously within the context of a conversation.
     *
     * @param conversationId the conversation identifier
     * @param question the user's question
     * @param username the name of the user
     * @param questionId the index of the question in the conversation
     * @param userId the unique identifier of the user
     * @return a map containing the results of the workflow
     */
    Map<String, Object> processQuestion(
            String conversationId,
            String question,
            String username,
            Integer questionId,
            UUID userId
    );

    /**
     * Processes one question on the configured task executor and applies request timeout rules.
     */
    CompletableFuture<Map<String, Object>> processQuestionAsync(
            String conversationId,
            String question,
            String username,
            Integer questionId,
            UUID userId
    );

    /**
     * Returns the active workflow for a conversation or creates one when missing/expired.
     */
    SecuredEnhancedUnifiedWorkflow getOrCreateConversation(String conversationId, UUID userId);

    /**
     * Returns lightweight runtime stats for health and metrics endpoints.
     */
    Map<String, Object> getStats();
}
