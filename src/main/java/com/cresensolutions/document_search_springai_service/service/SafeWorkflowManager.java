package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Contract for running query workflows while reusing per-conversation history state.
 */
public interface SafeWorkflowManager {

    /**
     * Processes one question synchronously for the provided conversation.
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
