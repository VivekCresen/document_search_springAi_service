package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.IntentClassification;
import com.cresensolutions.document_search_springai_service.dto.SearchResultDocument;

import java.util.List;

public interface SecuredIntentClassifier {

    List<SearchResultDocument> searchRelevantDocumentsWithSecurity(String question, String searchFilter, int topK);

    IntentClassification classifyIntent(String question, String username, List<SearchResultDocument> prefetchedDocs);
}
