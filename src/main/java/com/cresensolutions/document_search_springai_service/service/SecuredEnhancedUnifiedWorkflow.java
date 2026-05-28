package com.cresensolutions.document_search_springai_service.service;

import java.util.Map;
import java.util.List;

/**
 * Contract for a conversation-aware workflow that reads and updates chat history.
 */
public interface SecuredEnhancedUnifiedWorkflow {

    /**
     * Processes one question and stores the resulting conversation turn.
     */
    Map<String, Object> processQuestionWithHistory(
            String requestId,
            String question,
            String username,
            Integer questionId,
            List<String> attachedFiles
    );

    default Map<String, Object> processQuestionWithHistory(
            String requestId,
            String question,
            String username,
            Integer questionId
    ) {
        return processQuestionWithHistory(requestId, question, username, questionId, null);
    }
}
