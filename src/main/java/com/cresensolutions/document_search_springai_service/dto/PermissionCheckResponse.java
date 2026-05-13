package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class PermissionCheckResponse {

    private String username;
    private Map<String, Boolean> permissions;

    @JsonProperty("restricted_count")
    private int restrictedCount;

    @JsonProperty("accessible_count")
    private int accessibleCount;
}
