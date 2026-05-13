package com.cresensolutions.document_search_springai_service.dto;

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
public class HighlightedPdfResult {

    private String downloadLink;
    private String viewLink;

    @Builder.Default
    private List<Integer> highlightedPages = Collections.emptyList();
}
