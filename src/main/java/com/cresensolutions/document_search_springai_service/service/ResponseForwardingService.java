package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;

public interface ResponseForwardingService {

    void forwardResponse(EnvelopeResponse response);
}
