package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LlmExtraction {

    @JsonProperty("exact_text")
    private String exactText;

    private String source;
    private String page;
    private String explains;
}
