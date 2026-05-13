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

@Service
@RequiredArgsConstructor
@Slf4j
public class ResponseForwardingServiceImpl implements ResponseForwardingService {

    private final WorkflowProperty workflowProperty;
    private final RestClient.Builder restClientBuilder;

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
