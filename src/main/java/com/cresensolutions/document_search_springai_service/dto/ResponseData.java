package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class ResponseData {
    @JsonProperty("request_id")
    private String requestId;

    @JsonProperty("Authorization")
    private String authorization;

    @JsonProperty("xtenantid")
    private String xTenantId;

    private String email;
    private String username;

    @JsonProperty("question_id")
    private Integer questionId;

    @JsonProperty("response_type")
    private String responseType;

    private List<AnswerItem> answer;
    private Map<String, Object> citations;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("product_name")
    private String productName;

    private String profile;

    @JsonProperty("user_id")
    private Long userId;

    @JsonProperty("response_timeStamp")
    private String responseTimestamp;

    @JsonProperty("standalone_query")
    private String standaloneQuery;

    @JsonProperty("conversation_context")
    private String conversationContext;
}
