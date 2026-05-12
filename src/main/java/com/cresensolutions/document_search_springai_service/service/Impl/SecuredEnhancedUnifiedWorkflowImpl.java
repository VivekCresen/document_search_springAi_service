package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import com.cresensolutions.document_search_springai_service.service.SecuredEnhancedUnifiedWorkflow;
import com.cresensolutions.document_search_springai_service.service.SecuredUnifiedQueryWorkflow;
import lombok.RequiredArgsConstructor;

import java.util.Map;

/**
 * Conversation-scoped workflow wrapper that adds history lookup and persistence around the base workflow.
 */
@RequiredArgsConstructor
public class SecuredEnhancedUnifiedWorkflowImpl implements SecuredEnhancedUnifiedWorkflow {

    private final SecuredUnifiedQueryWorkflow baseWorkflow;
    private final ChatHistoryService chatHistoryService;
    private final String conversationId;
    private final Long userId;
    private final int recentMessageLimit;

    @Override
    public Map<String, Object> processQuestionWithHistory(
            String question,
            String username,
            Integer questionId
    ) {
        // Recent history helps the base workflow rewrite follow-up questions into standalone queries.
        String context = chatHistoryService.getRecentContext(conversationId, userId, recentMessageLimit);
        Map<String, Object> result = baseWorkflow.processQuestion(
                question,
                username,
                context,
                conversationId,
                questionId,
                userId
        );
        chatHistoryService.appendExchange(
                conversationId,
                userId,
                question,
                result.getOrDefault("standalone_query", "").toString(),
                questionId
        );
        return result;
    }
}
