package com.cresensolutions.document_search_springai_service.dto;

import lombok.Data;

@Data
public class SearchRequest {
    private String query;
    private int topK = 5;
}
