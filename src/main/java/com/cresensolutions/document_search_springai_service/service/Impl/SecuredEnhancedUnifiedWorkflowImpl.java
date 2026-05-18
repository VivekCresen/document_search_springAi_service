package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import com.cresensolutions.document_search_springai_service.service.SecuredEnhancedUnifiedWorkflow;
import com.cresensolutions.document_search_springai_service.service.SecuredUnifiedQueryWorkflow;
import lombok.RequiredArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Conversation-scoped workflow wrapper that adds history lookup and persistence around the base workflow.
 */
@RequiredArgsConstructor
public class SecuredEnhancedUnifiedWorkflowImpl implements SecuredEnhancedUnifiedWorkflow {

    private final SecuredUnifiedQueryWorkflow baseWorkflow;
    private final ChatHistoryService chatHistoryService;
    private final String conversationId;
    private final java.util.UUID userId;
    private final int recentMessageLimit;

    @Override
    public Map<String, Object> processQuestionWithHistory(
            String requestId,
            String question,
            String username,
            Integer questionId
    ) {
        // Recent history helps the base workflow rewrite follow-up questions into standalone queries.
        String context = chatHistoryService.getRecentContext(conversationId, userId, recentMessageLimit);
        String requestTimestamp = java.time.OffsetDateTime.now().toString();
        long startTime = System.currentTimeMillis();
        Map<String, Object> result = baseWorkflow.processQuestion(
                question,
                username,
                context,
                conversationId,
                questionId,
                userId
        );
        long latencyMs = System.currentTimeMillis() - startTime;

        Map<String, Object> metadata = new LinkedHashMap<>(result);
        metadata.remove("nlp_answer");
        metadata.remove("citations");
        metadata.remove("prefetched_docs");

        // Pass essential simple parameters through metadata to avoid breaking appendExchange signature
        metadata.put("request_id", requestId != null ? requestId : "");
        metadata.put("latency_ms", latencyMs);
        metadata.put("request_timestamp", requestTimestamp);

        chatHistoryService.appendExchange(
                conversationId,
                userId,
                question,
                result.getOrDefault("standalone_query", "").toString(),
                result.getOrDefault("nlp_answer", "").toString(),
                questionId,
                metadata
        );
        return result;
    }
}
