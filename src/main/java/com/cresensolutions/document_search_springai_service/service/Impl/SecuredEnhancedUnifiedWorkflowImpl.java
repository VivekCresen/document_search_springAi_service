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

    /**
     * Executes the query workflow under context history, automatically loading recent chat messages
     * for query rewriting, tracking execution latency, and saving the final exchange back to the database history.
     *
     * @param requestId tracing/logging UUID representing this turn
     * @param question raw user question string
     * @param username username of requester
     * @param questionId query sequence turn ID
     * @return workflow execution results map
     */
    @Override
    public Map<String, Object> processQuestionWithHistory(
            String requestId,
            String question,
            String username,
            Integer questionId,
            java.util.List<String> attachedFiles
    ) {
        // Recent history helps the base workflow rewrite follow-up questions into standalone queries.
        String context = chatHistoryService.getRecentContext(conversationId, userId, recentMessageLimit);
        String requestTimestamp = java.time.OffsetDateTime.now().toString();
        long startTime = System.currentTimeMillis();
        Map<String, Object> result;
        if (attachedFiles != null) {
            result = baseWorkflow.processQuestion(
                    question,
                    username,
                    context,
                    conversationId,
                    questionId,
                    userId,
                    attachedFiles
            );
        } else {
            result = baseWorkflow.processQuestion(
                    question,
                    username,
                    context,
                    conversationId,
                    questionId,
                    userId
            );
        }
        long latencyMs = System.currentTimeMillis() - startTime;

        Map<String, Object> metadata = new LinkedHashMap<>(result);
        metadata.remove("nlp_answer");
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
