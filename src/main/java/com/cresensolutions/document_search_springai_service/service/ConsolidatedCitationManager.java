package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.RagSourceDocument;
import com.cresensolutions.document_search_springai_service.dto.SupportingPassage;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service interface for managing consolidated citations from RAG results.
 * Handles the generation of highlighted PDF links for supporting passages.
 */
public interface ConsolidatedCitationManager {

    /**
     * Creates a map of citations based on supporting passages and source documents.
     * Each citation includes links to a highlighted version of the source PDF.
     *
     * @param supportingPassages list of passages extracted by the LLM
     * @param sourceDocuments list of source documents used for the RAG query
     * @param conversationId the current conversation ID
     * @param questionId the current question ID
     * @param userId the ID of the user
     * @return a map of citations keyed by index
     */
    Map<String, Object> createCitationsFromPassages(
            List<SupportingPassage> supportingPassages,
            List<RagSourceDocument> sourceDocuments,
            String conversationId,
            Integer questionId,
            java.util.UUID userId
    );
}
