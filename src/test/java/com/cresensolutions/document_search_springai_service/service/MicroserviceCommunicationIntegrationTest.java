package com.cresensolutions.document_search_springai_service.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test to verify the communication between the Spring AI microservice
 * and the Azure Indexing microservice.
 *
 * <p>Requires the Azure Indexing microservice to be running on http://localhost:8086.
 */
@SpringBootTest
class MicroserviceCommunicationIntegrationTest {

    @Autowired
    private IndexingCallbackService indexingCallbackService;

    @Test
    void testMicroserviceCommunicationWithAzureIndexing() {
        RestTemplate restTemplate = new RestTemplate();
        String url = "http://localhost:8086/api/indexing/blobs/trigger";
        
        Map<String, String> payload = Map.of(
                "blobUri", "https://cresengpt.blob.core.windows.net/internchatgptdoccontainer/test-integration-file.pdf",
                "blobName", "test-integration-file.pdf",
                "fileName", "test-integration-file.pdf"
        );

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(url, payload, Map.class);
            
            // Assert that the communication was successful and returned 2xx status code
            assertTrue(response.getStatusCode().is2xxSuccessful(), "Response status should be 2xx successful");
            assertNotNull(response.getBody(), "Response body should not be null");
            
            // Assert that the response contains the expected keys
            assertTrue(response.getBody().containsKey("queued"), "Response should contain key 'queued'");
            assertTrue(response.getBody().containsKey("blobUri"), "Response should contain key 'blobUri'");
            
            System.out.println(">>> Integration Test: Communication with Azure Indexing microservice is working perfectly!");
            System.out.println(">>> Response: " + response.getBody());
        } catch (Exception e) {
            fail("Communication with Azure Indexing microservice failed: " + e.getMessage());
        }
    }
}
