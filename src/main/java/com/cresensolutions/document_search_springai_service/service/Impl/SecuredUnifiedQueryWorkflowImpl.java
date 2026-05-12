package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.SecuredUnifiedQueryWorkflow;
import com.cresensolutions.document_search_springai_service.service.StandaloneQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the phase 0-to-3 workflow response using a standalone query generated from chat context.
 */
@Service
@RequiredArgsConstructor
public class SecuredUnifiedQueryWorkflowImpl implements SecuredUnifiedQueryWorkflow {

    private final StandaloneQueryService standaloneQueryService;

    @Override
    public Map<String, Object> processQuestion(
            String question,
            String username,
            String conversationContext,
            String conversationId,
            Integer questionId,
            Long userId
    ) {
        String standaloneQuery = standaloneQueryService.createStandaloneQuery(question, conversationContext);

        // LinkedHashMap keeps response fields in a predictable order for clients and logs.
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("workflow", "phase_0_to_3");
        result.put("original_question", question);
        result.put("standalone_query", standaloneQuery);
        result.put("conversation_context", conversationContext);
        result.put("conversation_id", conversationId);
        result.put("question_id", questionId);
        result.put("user_id", userId);
        result.put("username", username);
        return result;
    }
}
