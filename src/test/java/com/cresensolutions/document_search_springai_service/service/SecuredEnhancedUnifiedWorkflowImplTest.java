package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import com.cresensolutions.document_search_springai_service.service.Impl.SecuredEnhancedUnifiedWorkflowImpl;
import com.cresensolutions.document_search_springai_service.service.SecuredUnifiedQueryWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuredEnhancedUnifiedWorkflowImpl Tests")
class SecuredEnhancedUnifiedWorkflowImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock SecuredUnifiedQueryWorkflow baseWorkflow;
    @Mock ChatHistoryService chatHistoryService;

    SecuredEnhancedUnifiedWorkflowImpl service;

    @BeforeEach
    void setUp() {
        service = new SecuredEnhancedUnifiedWorkflowImpl(baseWorkflow, chatHistoryService, "conv1", USER_ID, 5);
    }

    @Test
    @DisplayName("processQuestionWithHistory: fetches context, calls workflow, appends exchange")
    void processQuestionWithHistory_happyPath() {
        when(chatHistoryService.getRecentContext("conv1", USER_ID, 5)).thenReturn("prior context");
        Map<String, Object> workflowResult = Map.of(
                "standalone_query", "standalone q",
                "nlp_answer", "The answer",
                "citations", Map.of()
        );
        when(baseWorkflow.processQuestion("question", "user", "prior context", "conv1", 1, USER_ID))
                .thenReturn(workflowResult);

        Map<String, Object> result = service.processQuestionWithHistory("reqId", "question", "user", 1);

        assertThat(result).isSameAs(workflowResult);

        // Verify exchange was appended with correct data
        verify(chatHistoryService).appendExchange(
                eq("conv1"),
                eq(USER_ID),
                eq("question"),
                eq("standalone q"),
                eq("The answer"),
                eq(1),
                argThat(meta -> !meta.containsKey("nlp_answer") && !meta.containsKey("citations") && !meta.containsKey("prefetched_docs"))
        );
    }

    @Test
    @DisplayName("processQuestionWithHistory: uses empty string when standalone_query is missing")
    void processQuestionWithHistory_missingStandaloneQuery() {
        when(chatHistoryService.getRecentContext(anyString(), any(java.util.UUID.class), anyInt())).thenReturn("");
        when(baseWorkflow.processQuestion(anyString(), anyString(), anyString(), anyString(), anyInt(), any(java.util.UUID.class)))
                .thenReturn(Map.of("nlp_answer", "answer"));

        service.processQuestionWithHistory("reqId", "q", "user", 1);

        ArgumentCaptor<String> standaloneCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatHistoryService).appendExchange(anyString(), any(java.util.UUID.class), anyString(),
                standaloneCaptor.capture(), anyString(), anyInt(), any());
        assertThat(standaloneCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("processQuestionWithHistory: metadata passed to appendExchange strips sensitive keys")
    void processQuestionWithHistory_metadataStripping() {
        when(chatHistoryService.getRecentContext(anyString(), any(java.util.UUID.class), anyInt())).thenReturn("");
        Map<String, Object> workflowResult = Map.of(
                "nlp_answer", "ans",
                "citations", "cite",
                "prefetched_docs", "docs",
                "intent", "document"
        );
        when(baseWorkflow.processQuestion(anyString(), anyString(), anyString(), anyString(), anyInt(), any(java.util.UUID.class)))
                .thenReturn(workflowResult);

        service.processQuestionWithHistory("reqId", "q", "user", 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(chatHistoryService).appendExchange(anyString(), any(java.util.UUID.class), anyString(),
                anyString(), anyString(), anyInt(), metaCaptor.capture());

        Map<String, Object> captured = metaCaptor.getValue();
        assertThat(captured).doesNotContainKey("nlp_answer");
        assertThat(captured).doesNotContainKey("citations");
        assertThat(captured).doesNotContainKey("prefetched_docs");
        assertThat(captured).containsKey("intent");
    }
}
