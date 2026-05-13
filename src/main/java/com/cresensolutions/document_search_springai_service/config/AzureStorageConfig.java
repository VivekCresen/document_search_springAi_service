package com.cresensolutions.document_search_springai_service.config;

import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(CloudProperty.class)
public class AzureStorageConfig {

    private final CloudProperty cloudProperty;

    @Bean
    public BlobServiceClient blobServiceClient() {
        String key = cloudProperty.getAccountKey();
        // Strip any surrounding quotes that dotenv libraries may inject
        if (key != null && key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
            key = key.substring(1, key.length() - 1);
        }
        log.info("Azure account name: {}", cloudProperty.getAccountName());
        log.info("Azure account key is null: {}, length: {}", key == null, key == null ? 0 : key.length());

        StorageSharedKeyCredential credential = new StorageSharedKeyCredential(
                cloudProperty.getAccountName(), key);

        return new BlobServiceClientBuilder()
                .endpoint(cloudProperty.getAccountUrl())
                .credential(credential)
                .buildClient();
    }
}
