package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;

import java.util.List;
import java.util.UUID;

public interface SecuredRagPipeline {

    DocumentAnswer answerQuestionWithSecurity(
            String question,
            String username,
            List<SearchResultDocument> prefetchedDocs,
            String conversationId,
            Integer questionId,
            UUID userId
    );
}
