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
public class IntentClassification {

    private String intent;

    @JsonProperty("response_type")
    private String responseType;

    private double confidence;
    private String reasoning;

    @JsonProperty("prefetched_docs")
    @Builder.Default
    private List<SearchResultDocument> prefetchedDocs = Collections.emptyList();
}
