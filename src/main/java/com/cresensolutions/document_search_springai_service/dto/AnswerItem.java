package com.cresensolutions.document_search_springai_service.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
public class AnswerItem {
    @JsonProperty("Text")
    private String text;

    @JsonProperty("Table")
    private List<Map<String, Object>> table;
}
