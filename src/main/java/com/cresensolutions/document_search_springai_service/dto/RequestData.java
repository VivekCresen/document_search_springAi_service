package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class RequestData {
    @JsonProperty("request_id")
    private String requestId;

    @JsonAlias({"Authorization", "authorization"})
    private String authorization;

    @JsonAlias({"xtenantid", "xTenantId", "x_tenant_id"})
    private String xTenantId;

    private String email;
    private String username;
    private String question;

    @JsonProperty("question_id")
    private Integer questionId;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("product_name")
    private String productName = "MM";

    private String profile = "dev";

    @JsonProperty("user_id")
    private Long userId;
}
