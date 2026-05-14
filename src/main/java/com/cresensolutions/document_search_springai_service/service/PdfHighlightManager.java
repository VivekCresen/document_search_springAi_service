package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;

import java.awt.Color;
import java.util.List;
import java.util.UUID;

/**
 * Service interface for highlighting text in PDF documents.
 * Supports coordinate-based highlighting and text-search fallback.
 */
public interface PdfHighlightManager {

    /**
     * Highlights multiple text passages in a PDF and returns links to the annotated file.
     *
     * @param pdfBlobName the name of the original PDF blob in storage
     * @param textPassages list of text strings to highlight
     * @param color the highlight color
     * @param conversationId current conversation ID
     * @param questionId current question ID
     * @param userId current user ID
     * @param diPageSpans optional coordinates from Document Intelligence
     * @return a HighlightedPdfResult containing links and page numbers
     */
    HighlightedPdfResult highlightMultiplePassagesInPdf(
            String pdfBlobName,
            List<String> textPassages,
            Color color,
            String conversationId,
            Integer questionId,
            UUID userId,
            List<DiPageSpan> diPageSpans
    );
}
