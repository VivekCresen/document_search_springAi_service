package com.cresensolutions.document_search_springai_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "azure.search")
public class AzureSearchProperty {

    private String endpoint;
    private String apiKey;
    private String indexName = "demo";
    private String apiVersion = "2023-11-01";
}
