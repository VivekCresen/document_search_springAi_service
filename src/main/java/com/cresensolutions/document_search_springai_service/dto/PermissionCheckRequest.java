package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
public class PermissionCheckRequest {

    @JsonProperty("folder_ids")
    private List<String> folderIds;
}
