package com.cresensolutions.document_search_springai_service.dto;

import lombok.Builder;

import java.util.Collections;
import java.util.List;

@Builder
public record SupportingPassage(
        String text,
        String source,
        String page,
        String relevance,
        List<String> individualPassages,
        List<DiPageSpan> diPageSpans
) {
    public SupportingPassage {
        individualPassages = individualPassages == null ? Collections.emptyList() : individualPassages;
        diPageSpans = diPageSpans == null ? Collections.emptyList() : diPageSpans;
    }
}
