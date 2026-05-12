package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;

/**
 * Contract for a conversation-aware workflow that reads and updates chat history.
 */
public interface SecuredEnhancedUnifiedWorkflow {

    /**
     * Processes one question and stores the resulting conversation turn.
     */
    Map<String, Object> processQuestionWithHistory(
            String question,
            String username,
            Integer questionId
    );
}
