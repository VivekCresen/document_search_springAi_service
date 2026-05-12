package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.dto.AnswerItem;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeRequest;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;
import com.cresensolutions.document_search_springai_service.dto.RequestData;
import com.cresensolutions.document_search_springai_service.dto.ResponseData;
import com.cresensolutions.document_search_springai_service.service.SafeWorkflowManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * REST Controller for handling document query operations.
 * Processes user questions about documents using AI-powered search and retrieval.
 * Manages conversation context and provides asynchronous query processing.
 */
@RestController
@RequestMapping
@RequiredArgsConstructor
public class QueryController {

    // Date formatter for response timestamps
    private static final DateTimeFormatter RESPONSE_TIMESTAMP =
            DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");

    // Workflow manager for processing queries safely
    private final SafeWorkflowManager workflowManager;

    /**
     * Processes a document query asynchronously.
     * Takes a user question, processes it through the AI workflow,
     * and returns relevant document information and answers.
     *
     * @param request the envelope containing query data
     * @return CompletableFuture with the response containing query results
     */
    @PostMapping("/query")
    public CompletableFuture<ResponseEntity<EnvelopeResponse>> query(@RequestBody EnvelopeRequest request) {
        RequestData data = request.getRequestData();
        String conversationId = resolveConversationId(data.getConversationId());
        String principal = data.getEmail();

        return workflowManager.processQuestionAsync(
                        conversationId,
                        data.getQuestion(),
                        principal,
                        data.getQuestionId(),
                        data.getUserId()
                )
                .thenApply(result -> ResponseEntity.ok(buildResponse(data, conversationId, result)));
    }

    /**
     * Builds the response envelope from query results.
     * Formats the AI response with timestamps and conversation context.
     *
     * @param data the original request data
     * @param conversationId the resolved conversation ID
     * @param result the processing result from the workflow
     * @return formatted response envelope
     */
    private EnvelopeResponse buildResponse(RequestData data, String conversationId, Map<String, Object> result) {
        String standaloneQuery = result.getOrDefault("standalone_query", data.getQuestion()).toString();
        String context = result.getOrDefault("conversation_context", "").toString();

        ResponseData responseData = ResponseData.builder()
                .requestId(data.getRequestId())
                .authorization(data.getAuthorization())
                .xTenantId(data.getXTenantId())
                .email(data.getEmail())
                .username(data.getUsername())
                .questionId(data.getQuestionId())
                .responseType("text")
                .answer(List.of(AnswerItem.builder()
                        .text(standaloneQuery)
                        .build()))
                .citations(Map.of())
                .conversationId(conversationId)
                .productName(data.getProductName() == null ? "MM" : data.getProductName())
                .profile(data.getProfile() == null ? "dev" : data.getProfile())
                .userId(data.getUserId())
                .responseTimestamp(LocalDateTime.now().format(RESPONSE_TIMESTAMP))
                .standaloneQuery(standaloneQuery)
                .conversationContext(context)
                .build();

        return new EnvelopeResponse(responseData);
    }

    private String resolveConversationId(String conversationId) {
        if (conversationId != null && !conversationId.isBlank()) {
            return conversationId;
        }
        return "chat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
