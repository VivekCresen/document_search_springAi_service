package com.cresensolutions.document_search_springai_service.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * Configuration properties class mapping Azure storage connection values.
 * Mapped to the prefix "azure.storage".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Component
@ConfigurationProperties(prefix = "azure.storage")
public class CloudProperty {

    /**
     * Name of the target Azure storage account.
     */
    private String accountName;

    /**
     * Account secret authentication key.
     */
    private String accountKey;

    /**
     * Base HTTP endpoint URL of the storage service.
     */
    private String accountUrl;

    /**
     * Target blob container registry directory name.
     */
    private String containerName;

    /**
     * Temporary SAS URI lifespan token limit in seconds.
     */
    private long blobSasExpiresInSeconds;

    /**
     * Highlight PDF download SAS URL lifespan limit in days.
     */
    private long blobSasExpiresInDaysForPdf;
}
