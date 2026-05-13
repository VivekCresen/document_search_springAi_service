package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagLlmResponse {

    private String answer;

    @JsonProperty("raw_extractions")
    @Builder.Default
    private List<LlmExtraction> rawExtractions = Collections.emptyList();
}
