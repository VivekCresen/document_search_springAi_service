package com.cresensolutions.document_search_springai_service.dto;

import lombok.Data;

@Data
public class EnvelopeRequest {
    private RequestMetadata metadata;
    private RequestData requestData;
}
