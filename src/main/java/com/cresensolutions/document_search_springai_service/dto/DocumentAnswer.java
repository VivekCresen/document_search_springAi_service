package com.cresensolutions.document_search_springai_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentAnswer {

    private String answer;
    private Map<String, Object> citations;
}
