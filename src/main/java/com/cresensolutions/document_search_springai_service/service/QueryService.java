package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.EnvelopeRequest;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;

import java.util.concurrent.CompletableFuture;

public interface QueryService {

    CompletableFuture<EnvelopeResponse> processQuery(EnvelopeRequest request);
}
