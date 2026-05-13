package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DocumentAnswer;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;

import java.util.List;

public interface SecuredRagPipeline {

    DocumentAnswer answerQuestionWithSecurity(
            String question,
            String username,
            List<SearchResultDocument> prefetchedDocs,
            String conversationId,
            Integer questionId,
            Long userId
    );
}
