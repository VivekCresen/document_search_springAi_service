package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;
import com.cresensolutions.document_search_springai_service.service.ResponseForwardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Implementation of ResponseForwardingService that posts complete Query responses asynchronously
 * to external targets configured in WorkflowProperties.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ResponseForwardingServiceImpl implements ResponseForwardingService {

    private final WorkflowProperty workflowProperty;
    private final RestClient.Builder restClientBuilder;

    /**
     * Forwards a completed search query envelope response payload asynchronously to a configured third-party REST endpoint.
     * Operation fails silently if the endpoint is not configured or throws exceptions.
     *
     * @param response the EnvelopeResponse payload to transmit
     */
    @Override
    @Async("taskExecutor")
    public void forwardResponse(EnvelopeResponse response) {
        if (workflowProperty.getTargetEndpoint() == null || workflowProperty.getTargetEndpoint().isBlank()) {
            return;
        }
        try {
            restClientBuilder.build()
                    .post()
                    .uri(workflowProperty.getTargetEndpoint())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getResponseData())
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to forward query response to {}", workflowProperty.getTargetEndpoint(), e);
        }
    }
}
