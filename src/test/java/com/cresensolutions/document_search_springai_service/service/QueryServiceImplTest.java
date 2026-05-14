package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.dto.AnswerItem;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeRequest;
import com.cresensolutions.document_search_springai_service.dto.EnvelopeResponse;
import com.cresensolutions.document_search_springai_service.dto.RequestData;
import com.cresensolutions.document_search_springai_service.dto.ResponseData;
import com.cresensolutions.document_search_springai_service.service.Impl.QueryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("QueryServiceImpl Tests")
class QueryServiceImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    SafeWorkflowManager workflowManager;

    @Mock
    ResponseForwardingService responseForwardingService;

    QueryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new QueryServiceImpl(workflowManager, responseForwardingService);
    }

    @Test
    @DisplayName("processQuery: successfully processes text response")
    void processQuery_textResponse() throws Exception {
        EnvelopeRequest req = new EnvelopeRequest();
        RequestData reqData = new RequestData();
        reqData.setConversationId("conv1");
        reqData.setQuestion("q1");
        reqData.setEmail("test@test.com");
        reqData.setQuestionId(1);
        reqData.setUserId(USER_ID);
        req.setRequestData(reqData);

        Map<String, Object> mockResult = Map.of(
                Common.RESULT_INTERNAL_TYPE, Common.TEXT_RESPONSE_TYPE,
                Common.RESULT_TEXT_PAYLOAD, "Answer text",
                Common.RESULT_STANDALONE_QUERY, "Standalone q1"
        );

        when(workflowManager.processQuestionAsync("conv1", "q1", "test@test.com", 1, USER_ID))
                .thenReturn(CompletableFuture.completedFuture(mockResult));

        CompletableFuture<EnvelopeResponse> future = service.processQuery(req);
        EnvelopeResponse response = future.get();

        assertThat(response).isNotNull();
        ResponseData data = response.getResponseData();
        assertThat(data).isNotNull();
        assertThat(data.getResponseType()).isEqualTo(Common.TEXT_RESPONSE_TYPE);
        assertThat(data.getStandaloneQuery()).isEqualTo("Standalone q1");
        
        List<AnswerItem> answers = data.getAnswer();
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).getText()).isEqualTo("Answer text");
        assertThat(answers.get(0).getTable()).isNull();

        verify(responseForwardingService).forwardResponse(any(EnvelopeResponse.class));
    }

    @Test
    @DisplayName("processQuery: generates new conversationId if blank")
    void processQuery_newConversationId() throws Exception {
        EnvelopeRequest req = new EnvelopeRequest();
        RequestData reqData = new RequestData();
        reqData.setConversationId(""); // Blank
        reqData.setQuestion("q1");
        req.setRequestData(reqData);

        when(workflowManager.processQuestionAsync(anyString(), eq("q1"), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(Map.of()));

        CompletableFuture<EnvelopeResponse> future = service.processQuery(req);
        EnvelopeResponse response = future.get();

        assertThat(response.getResponseData().getConversationId()).isNotBlank();
        
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(workflowManager).processQuestionAsync(captor.capture(), eq("q1"), any(), any(), any());
        assertThat(captor.getValue()).isNotBlank();
    }

    @Test
    @DisplayName("processQuery: successfully processes table response")
    void processQuery_tableResponse() throws Exception {
        EnvelopeRequest req = new EnvelopeRequest();
        RequestData reqData = new RequestData();
        reqData.setConversationId("conv1");
        reqData.setQuestion("q1");
        req.setRequestData(reqData);

        Map<String, Object> mockResult = Map.of(
                Common.RESULT_INTERNAL_TYPE, Common.TABLE_RESPONSE_TYPE,
                Common.RESULT_DATA_PAYLOAD, List.of(Map.of("col1", "val1"))
        );

        when(workflowManager.processQuestionAsync(eq("conv1"), eq("q1"), any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(mockResult));

        CompletableFuture<EnvelopeResponse> future = service.processQuery(req);
        EnvelopeResponse response = future.get();

        ResponseData data = response.getResponseData();
        assertThat(data.getResponseType()).isEqualTo(Common.TABLE_RESPONSE_TYPE);
        
        List<AnswerItem> answers = data.getAnswer();
        assertThat(answers).hasSize(1);
        assertThat(answers.get(0).getText()).isNull();
        assertThat(answers.get(0).getTable()).hasSize(1);
        assertThat(answers.get(0).getTable().get(0)).containsEntry("col1", "val1");
    }
}
