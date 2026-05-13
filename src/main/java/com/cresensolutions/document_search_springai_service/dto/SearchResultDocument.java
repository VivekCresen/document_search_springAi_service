package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchResultDocument {

    private String source;
    private String filepath;

    @JsonProperty("blob_uri")
    private String blobUri;

    private String content;
    private Object page;
    private Double score;
    private List<String> topics;

    @JsonProperty("example_queries")
    private List<String> exampleQueries;

    @JsonProperty("intent_signals")
    private List<String> intentSignals;

    @JsonProperty("folder_id")
    private String folderId;

    @JsonProperty("di_page_spans")
    private List<DiPageSpan> diPageSpans;
}
