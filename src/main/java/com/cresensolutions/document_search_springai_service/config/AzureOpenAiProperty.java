package com.cresensolutions.document_search_springai_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "azure.openai")
public class AzureOpenAiProperty {

    private String endpoint;
    private String apiKey;
    private String deployment = "gpt-4o-mini";
    private String apiVersion = "2024-02-01";
}
