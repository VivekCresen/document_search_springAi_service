package com.cresensolutions.document_search_springai_service.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CloudProperty.class)
public class AzureStorageConfig {

    private final CloudProperty cloudProperty;

    @Bean
    public BlobServiceClient blobServiceClient() {
        StorageSharedKeyCredential credential = new StorageSharedKeyCredential(
                cloudProperty.getAccountName(), cloudProperty.getAccountKey());

        return new BlobServiceClientBuilder()
                .endpoint(cloudProperty.getAccountUrl())
                .credential(credential)
                .buildClient();
    }
}
