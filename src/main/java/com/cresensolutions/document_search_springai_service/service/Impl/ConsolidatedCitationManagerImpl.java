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

    /**
     * Groups passages by source document and generates highlighted PDFs for each.
     *
     * @param supportingPassages the passages to highlight
     * @param sourceDocuments the full list of sources for metadata retrieval
     * @param conversationId current chat context
     * @param questionId current turn index
     * @param userId current user
     * @return map of numbered citations with links and page metadata
     */
    @Override
    public Map<String, Object> createCitationsFromPassages(
            List<SupportingPassage> supportingPassages,
            List<RagSourceDocument> sourceDocuments,
            String conversationId,
            Integer questionId,
            java.util.UUID userId
    ) {
        // Map source documents by their unique identifier/source name for quick lookup
        Map<String, RagSourceDocument> docsBySource = sourceDocuments.stream()
                .collect(Collectors.toMap(RagSourceDocument::source, doc -> doc, (first, second) -> first, LinkedHashMap::new));

        Map<String, Object> citations = new LinkedHashMap<>();
        int index = 1;
        for (SupportingPassage passage : supportingPassages) {
            RagSourceDocument sourceDocument = docsBySource.get(passage.source());
            if (sourceDocument == null) {
                continue;
            }
            
            // Cycle through colors for different passages
            Color color = COLORS.get((index - 1) % COLORS.size());
            
            // Request the highlight manager to produce a temporary PDF with highlighted text
            HighlightedPdfResult highlighted = pdfHighlightManager.highlightMultiplePassagesInPdf(
                    resolveBlobPath(sourceDocument),
                    passage.individualPassages().isEmpty() ? List.of(passage.text()) : passage.individualPassages(),
                    color,
                    conversationId,
                    questionId,
                    userId,
                    passage.diPageSpans()
            );

            // Construct the citation DTO
            Map<String, Object> citation = new LinkedHashMap<>();
            citation.put("file_name", passage.source());
            citation.put("download_link", highlighted.getDownloadLink());
            citation.put("view_link", highlighted.getViewLink());
            citation.put("highlighted_pages", highlighted.getHighlightedPages());
            citations.put(String.valueOf(index++), citation);
        }
        return citations;
    }

    /**
     * Extracts the relative blob path from a full Azure Blob URI.
     * Fallback to the 'filepath' field if the URI parsing fails.
     * 
     * @param sourceDocument the document metadata
     * @return the relative path in the Azure container
     */
    private String resolveBlobPath(RagSourceDocument sourceDocument) {
        String blobUri = sourceDocument.blobUri();
        if (blobUri != null && !blobUri.isBlank()) {
            try {
                URI uri = URI.create(blobUri);
                String path = uri.getPath(); // /container-name/folder/file.pdf
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
