package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;

import java.awt.Color;
import java.util.List;

public interface PdfHighlightManager {

    HighlightedPdfResult highlightMultiplePassagesInPdf(
            String pdfBlobName,
            List<String> textPassages,
            Color color,
            String conversationId,
            Integer questionId,
            Long userId,
            List<DiPageSpan> diPageSpans
    );
}
