package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.RagSourceDocument;
import com.cresensolutions.document_search_springai_service.dto.SupportingPassage;

import java.util.List;
import java.util.Map;

public interface ConsolidatedCitationManager {

    Map<String, Object> createCitationsFromPassages(
            List<SupportingPassage> supportingPassages,
            List<RagSourceDocument> sourceDocuments,
            String conversationId,
            Integer questionId,
            Long userId
    );
}
