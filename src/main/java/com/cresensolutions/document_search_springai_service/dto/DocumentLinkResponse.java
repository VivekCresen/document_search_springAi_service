package com.cresensolutions.document_search_springai_service.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DocumentLinkResponse {
    private String documentId;
    private String fileName;
    private String downloadLink;
    private String viewLink;
}
