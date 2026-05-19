package com.cresensolutions.document_search_springai_service.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * Notifies the {@code document_search_azure_indexing} microservice to immediately
 * queue a newly uploaded blob for indexing.
 *
 * <p>This is a best-effort, fire-and-forget style call: any exception is caught
 * and logged as a warning so that the upload flow is never blocked or rolled back
 * due to an indexing service outage.
 *
 * <p>The callback can be disabled entirely via {@code INDEXING_CALLBACK_ENABLED=false}
 * (useful for testing or when running without the indexing service).
 */
@Service
@Slf4j
public class IndexingCallbackService {

    private static final String TRIGGER_PATH = "/api/indexing/blobs/trigger";

    private final RestTemplate restTemplate;
    private final String indexingServiceUrl;
    private final boolean callbackEnabled;
    private final String internalToken;

    public IndexingCallbackService(
            RestTemplate restTemplate,
            @Value("${indexing.service.url:http://localhost:8086}") String indexingServiceUrl,
            @Value("${indexing.service.enabled:true}") boolean callbackEnabled,
            @Value("${indexing.service.internal-token:dev-indexing-internal-token}") String internalToken
    ) {
        this.restTemplate = restTemplate;
        this.indexingServiceUrl = indexingServiceUrl;
        this.callbackEnabled = callbackEnabled;
        this.internalToken = internalToken;
    }

    /**
     * Triggers indexing of a single blob in the Azure Indexing Service.
     *
     * <p>Constructs a trigger payload and POSTs it to
     * {@code POST {indexing.service.url}/api/indexing/blobs/trigger}.
     * All exceptions are swallowed to ensure the upload response is not affected.
     *
     * @param blobUri  Full Azure Blob URI
     *                 (e.g. {@code https://account.blob.core.windows.net/container/path/file.pdf})
     * @param blobName Relative blob path inside the container (e.g. {@code folder/uuid/file.pdf})
     * @param fileName Human-readable file name (e.g. {@code file.pdf})
     */
    public void triggerIndexing(String blobUri, String blobName, String fileName) {
        if (!callbackEnabled) {
            log.debug("[IndexingCallback] Callback disabled — skipping trigger for {}", blobUri);
            return;
        }
        if (blobUri == null || blobUri.isBlank()) {
            log.warn("[IndexingCallback] blobUri is blank — skipping trigger");
            return;
        }

        String url = indexingServiceUrl + TRIGGER_PATH;
        Map<String, String> payload = Map.of(
                "blobUri",  blobUri,
                "blobName", blobName == null ? "" : blobName,
                "fileName", fileName == null ? "" : fileName
        );
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Service-Token", internalToken);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, headers);

        try {
            log.info("[IndexingCallback] Triggering indexing for blob: {}", blobUri);
            ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                Object queued = response.getBody() != null ? response.getBody().get("queued") : null;
                log.info("[IndexingCallback] Indexing triggered successfully. queued={} blobUri={}", queued, blobUri);
            } else {
                log.warn("[IndexingCallback] Indexing service returned non-2xx status={} for blobUri={}",
                        response.getStatusCode(), blobUri);
            }
        } catch (Exception ex) {
            // Non-blocking: upload has already succeeded; log warning but do not propagate
            log.warn("[IndexingCallback] Failed to trigger indexing for blobUri={} — reason: {}",
                    blobUri, ex.getMessage());
        }
    }
}
