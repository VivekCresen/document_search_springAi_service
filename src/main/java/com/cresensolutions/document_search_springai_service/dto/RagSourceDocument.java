package com.cresensolutions.document_search_springai_service.dto;

import lombok.Builder;

import java.util.Collections;
import java.util.List;

@Builder
public record RagSourceDocument(
        String content,
        String source,
        String filepath,
        String blobUri,
        String page,
        List<DiPageSpan> diPageSpans
) {
    public RagSourceDocument {
        diPageSpans = diPageSpans == null ? Collections.emptyList() : diPageSpans;
    }
}
