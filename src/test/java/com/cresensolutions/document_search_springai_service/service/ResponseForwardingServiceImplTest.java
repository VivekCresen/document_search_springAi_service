package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;
import com.cresensolutions.document_search_springai_service.dto.ResponseData;
import com.cresensolutions.document_search_springai_service.service.Impl.ResponseForwardingServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResponseForwardingServiceImpl Tests")
class ResponseForwardingServiceImplTest {

    @Mock
    WorkflowProperty workflowProperty;
    
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    RestClient.Builder restClientBuilder;

    @InjectMocks
    ResponseForwardingServiceImpl service;

    @Test
    @DisplayName("forwardResponse: ignores empty target endpoint")
    void forwardResponse_emptyEndpoint() {
        when(workflowProperty.getTargetEndpoint()).thenReturn("");

        EnvelopeResponse response = new EnvelopeResponse();
        service.forwardResponse(response);

        verifyNoInteractions(restClientBuilder);
    }

    @Test
    @DisplayName("forwardResponse: ignores null target endpoint")
    void forwardResponse_nullEndpoint() {
        when(workflowProperty.getTargetEndpoint()).thenReturn(null);

        EnvelopeResponse response = new EnvelopeResponse();
        service.forwardResponse(response);

        verifyNoInteractions(restClientBuilder);
    }

    @Test
    @DisplayName("forwardResponse: forwards to target endpoint")
    void forwardResponse_successful() {
        String endpoint = "http://example.com/callback";
        when(workflowProperty.getTargetEndpoint()).thenReturn(endpoint);

        EnvelopeResponse response = new EnvelopeResponse();
       ResponseData data = mock(ResponseData.class);
        response.setResponseData(data);

        service.forwardResponse(response);

        // Verification relies on the deep stub not throwing an exception.
        // We can verify that toBodilessEntity was called on the deep stub.
        verify(restClientBuilder.build().post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .body(response.getResponseData())
                .retrieve()).toBodilessEntity();
    }
    
    @Test
    @DisplayName("forwardResponse: catches exceptions silently")
    void forwardResponse_catchesExceptions() {
        when(workflowProperty.getTargetEndpoint()).thenReturn("http://example.com/callback");
        when(restClientBuilder.build()).thenThrow(new RuntimeException("Connection Refused"));

        EnvelopeResponse response = new EnvelopeResponse();
        service.forwardResponse(response);

        // verify it called build and swallowed the exception
        verify(restClientBuilder).build();
    }
}
