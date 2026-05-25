package com.cresensolutions.document_search_springai_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration property class for Azure AI Search engine connection parameters.
 * Mapped to the prefix "azure.search".
 */
@Data
@Component
@ConfigurationProperties(prefix = "azure.search")
public class AzureSearchProperty {

    /**
     * The Azure AI Search service HTTP endpoint URL.
     */
    private String endpoint;

    /**
     * The admin query or API key required to authenticate searches.
     */
    private String apiKey;

    /**
     * Name of the target document search index registry. Defaults to "demo".
     */
    private String indexName = "demo";

    /**
     * Azure search API protocol version version. Defaults to "2023-11-01".
     */
    private String apiVersion = "2023-11-01";
}
