package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for the Secured RAG (Retrieval-Augmented Generation) pipeline.
 * Handles answering user questions while respecting security constraints.
 */
public interface SecuredRagPipeline {

    /**
     * Answers a question by retrieving and processing context from secured documents.
     *
     * @param question the user's question
     * @param username the name of the user requesting the answer
     * @param prefetchedDocs optional pre-retrieved documents from the index
     * @param conversationId current conversation ID
     * @param questionId current question ID
     * @param userId current user ID
     * @return a DocumentAnswer containing the generated text and citations
     */
    DocumentAnswer answerQuestionWithSecurity(
            String question,
            String username,
            List<SearchResultDocument> prefetchedDocs,
            String conversationId,
            Integer questionId,
            UUID userId
    );
}
