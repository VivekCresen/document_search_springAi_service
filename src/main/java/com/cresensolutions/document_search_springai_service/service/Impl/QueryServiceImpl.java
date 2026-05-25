package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.dto.AnswerItem;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeRequest;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;
import com.cresensolutions.document_search_springai_service.dto.RequestData;
import com.cresensolutions.document_search_springai_service.dto.ResponseData;
import com.cresensolutions.document_search_springai_service.service.QueryService;
import com.cresensolutions.document_search_springai_service.service.ResponseForwardingService;
import com.cresensolutions.document_search_springai_service.service.SafeWorkflowManager;
import com.cresensolutions.document_search_springai_service.utils.CommonUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of QueryService that orchestrates the query workflow.
 * Uses SafeWorkflowManager to handle the complex multi-stage search process.
 */
@Service
@RequiredArgsConstructor
public class QueryServiceImpl implements QueryService {

    private static final DateTimeFormatter RESPONSE_TIMESTAMP =
            DateTimeFormatter.ofPattern(Common.RESPONSE_TIMESTAMP_PATTERN);

    private final SafeWorkflowManager workflowManager;
    private final ResponseForwardingService responseForwardingService;

    /**
     * Entry point for query processing. Resolves conversation context and triggers the workflow.
     *
     * @param request the incoming search request
     * @return CompletableFuture with the search result
     */
    @Override
    public CompletableFuture<EnvelopeResponse> processQuery(EnvelopeRequest request) {
        RequestData data = request.getRequestData();
        // Ensure we have a valid conversation ID (generate one if missing)
        String conversationId = resolveConversationId(data.getConversationId());

        // Delegate the actual processing to the workflow manager
        return workflowManager.processQuestionAsync(
                        conversationId,
                        data.getRequestId(),
                        data.getQuestion(),
                        data.getEmail(),
                        data.getQuestionId(),
                        data.getUserId()
                )
                .thenApply(result -> {
                    // Once workflow finishes, build the envelope response and forward it
                    EnvelopeResponse response = buildResponse(data, conversationId, result);
                    responseForwardingService.forwardResponse(response);
                    return response;
                });
    }

    /**
     * Constructs the unified EnvelopeResponse descriptor containing search results,
     * citations, standalone query history, and system response timestamps.
     *
     * @param data original RequestData parameters
     * @param conversationId current conversation session token
     * @param result raw workflow execution maps and payloads
     * @return populated EnvelopeResponse
     */
    private EnvelopeResponse buildResponse(RequestData data, String conversationId, Map<String, Object> result) {
        String standaloneQuery = result.getOrDefault(Common.RESULT_STANDALONE_QUERY, data.getQuestion()).toString();
        String context = result.getOrDefault(Common.RESULT_CONVERSATION_CONTEXT, Common.EMPTY).toString();
        String answer = result.getOrDefault(
                Common.RESULT_TEXT_PAYLOAD,
                result.getOrDefault(Common.RESULT_NLP_ANSWER, standaloneQuery)
        ).toString();
        Object citations = result.getOrDefault(Common.RESULT_CITATIONS, Map.of());
        String responseType = apiResponseType(result);

        ResponseData responseData = ResponseData.builder()
                .requestId(data.getRequestId())
                .authorization(data.getAuthorization())
                .xTenantId(data.getXTenantId())
                .email(data.getEmail())
                .username(data.getUsername())
                .questionId(data.getQuestionId())
                .responseType(responseType)
                .answer(List.of(buildAnswerItem(answer, result, responseType)))
                .citations(CommonUtils.toStringObjectMap(citations))
                .conversationId(conversationId)
                .productName(data.getProductName() == null ? Common.DEFAULT_PRODUCT_NAME : data.getProductName())
                .profile(data.getProfile() == null ? Common.DEFAULT_PROFILE : data.getProfile())
                .userId(data.getUserId())
                .responseTimestamp(LocalDateTime.now().format(RESPONSE_TIMESTAMP))
                .standaloneQuery(standaloneQuery)
                .conversationContext(context)
                .build();

        return new EnvelopeResponse(responseData);
    }

    /**
     * Builds an AnswerItem matching text and tabular results based on the response layout type.
     *
     * @param answer text answer string
     * @param result workflow result map
     * @param responseType layout format representation (text, table, hybrid)
     * @return constructed AnswerItem
     */
    private AnswerItem buildAnswerItem(String answer, Map<String, Object> result, String responseType) {
        AnswerItem.AnswerItemBuilder builder = AnswerItem.builder();
        if (Common.TEXT_RESPONSE_TYPE.equals(responseType) || Common.TEXT_TABLE_RESPONSE_TYPE.equals(responseType)) {
            builder.text(answer == null || answer.isBlank() ? Common.NO_ANSWER_GENERATED : answer);
        }
        if (!Common.TEXT_RESPONSE_TYPE.equals(responseType)) {
            builder.table(tablePayload(result.get(Common.RESULT_DATA_PAYLOAD)));
        }
        return builder.build();
    }

    /**
     * Resolves the user-facing responseType formatting label from the internal query type.
     *
     * @param result raw workflow result map
     * @return user-facing string indicator (text, table, text_table)
     */
    private String apiResponseType(Map<String, Object> result) {
        String internalType = result.getOrDefault(Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE).toString();
        return switch (internalType) {
            case Common.TABLE_RESPONSE_TYPE -> Common.TABLE_RESPONSE_TYPE;
            case Common.HYBRID_RESPONSE_TYPE -> Common.TEXT_TABLE_RESPONSE_TYPE;
            default -> Common.TEXT_RESPONSE_TYPE;
        };
    }

    /**
     * Casts and extracts tabular raw datasets safely into lists of key-value maps.
     *
     * @param value raw data payload
     * @return formatted table payload list
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tablePayload(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return Collections.emptyList();
    }

    /**
     * Resolves the conversation ID, generating a new one if the incoming request parameter is blank.
     *
     * @param conversationId raw input identifier
     * @return validated non-blank conversation session identifier
     */
    private String resolveConversationId(String conversationId) {
        if (CommonUtils.hasText(conversationId)) {
            return conversationId;
        }
        return CommonUtils.newChatId();
    }
}
