package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;
import com.cresensolutions.document_search_springai_service.dto.RagSourceDocument;
import com.cresensolutions.document_search_springai_service.dto.SupportingPassage;
import com.cresensolutions.document_search_springai_service.service.ConsolidatedCitationManager;
import com.cresensolutions.document_search_springai_service.service.PdfHighlightManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Implementation of the Consolidated Citation Manager.
 * 
 * This service groups RAG extractions by their source PDF and handles the generation 
 * of highlighted PDF links (view/download) with correct page fragments.
 */
@Service
@RequiredArgsConstructor
public class ConsolidatedCitationManagerImpl implements ConsolidatedCitationManager {

    private static final List<Color> COLORS = List.of(
            Color.YELLOW,
            new Color(128, 255, 128),
            new Color(153, 204, 255),
            new Color(255, 153, 153),
            new Color(230, 179, 255),
            new Color(255, 204, 102),
            new Color(102, 230, 230)
    );

    private final PdfHighlightManager pdfHighlightManager;

    @Override
    public Map<String, Object> createCitationsFromPassages(
            List<SupportingPassage> supportingPassages,
            List<RagSourceDocument> sourceDocuments,
            String conversationId,
            Integer questionId,
            Long userId
    ) {
        Map<String, RagSourceDocument> docsBySource = sourceDocuments.stream()
                .collect(Collectors.toMap(RagSourceDocument::source, doc -> doc, (first, second) -> first, LinkedHashMap::new));

        Map<String, Object> citations = new LinkedHashMap<>();
        int index = 1;
        for (SupportingPassage passage : supportingPassages) {
            RagSourceDocument sourceDocument = docsBySource.get(passage.source());
            if (sourceDocument == null) {
                continue;
            }
            Color color = COLORS.get((index - 1) % COLORS.size());
            HighlightedPdfResult highlighted = pdfHighlightManager.highlightMultiplePassagesInPdf(
                    resolveBlobPath(sourceDocument),
                    passage.individualPassages().isEmpty() ? List.of(passage.text()) : passage.individualPassages(),
                    color,
                    conversationId,
                    questionId,
                    userId,
                    passage.diPageSpans()
            );

            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("file_name", passage.source());
            citation.put("download_link", highlighted.getDownloadLink());
            citation.put("view_link", highlighted.getViewLink());
            citation.put("highlighted_pages", highlighted.getHighlightedPages());
            citations.put(String.valueOf(index++), citation);
        }
        return citations;
    }

    private String resolveBlobPath(RagSourceDocument sourceDocument) {
        String blobUri = sourceDocument.blobUri();
        if (blobUri != null && !blobUri.isBlank()) {
            try {
                URI uri = URI.create(blobUri);
                String path = uri.getPath();
                int containerEnd = path.indexOf('/', 1);
                if (containerEnd >= 0 && containerEnd + 1 < path.length()) {
                    return path.substring(containerEnd + 1);
                }
            } catch (Exception ignored) {
                return blobUri;
            }
        }
        return sourceDocument.filepath();
    }
}
