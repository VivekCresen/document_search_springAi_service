package com.cresensolutions.document_search_springai_service.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Component
@ConfigurationProperties(prefix = "azure.storage")
public class CloudProperty {

    private String accountName;
    private String accountKey;
    private String accountUrl;
    private String containerName;
    private long blobSasExpiresInSeconds;
    private long blobSasExpiresInDaysForPdf;
}
