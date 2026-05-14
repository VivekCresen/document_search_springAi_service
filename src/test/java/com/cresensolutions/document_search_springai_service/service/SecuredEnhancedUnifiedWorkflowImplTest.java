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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("SecuredEnhancedUnifiedWorkflowImpl Tests")
class SecuredEnhancedUnifiedWorkflowImplTest {

    @Mock SecuredUnifiedQueryWorkflow baseWorkflow;
    @Mock ChatHistoryService chatHistoryService;

    SecuredEnhancedUnifiedWorkflowImpl service;

    @BeforeEach
    void setUp() {
        service = new SecuredEnhancedUnifiedWorkflowImpl(baseWorkflow, chatHistoryService, "conv1", 99L, 5);
    }

    @Test
    @DisplayName("processQuestionWithHistory: fetches context, calls workflow, appends exchange")
    void processQuestionWithHistory_happyPath() {
        when(chatHistoryService.getRecentContext("conv1", 99L, 5)).thenReturn("prior context");
        Map<String, Object> workflowResult = Map.of(
                "standalone_query", "standalone q",
                "nlp_answer", "The answer",
                "citations", Map.of()
        );
        when(baseWorkflow.processQuestion("question", "user", "prior context", "conv1", 1, 99L))
                .thenReturn(workflowResult);

        Map<String, Object> result = service.processQuestionWithHistory("question", "user", 1);

        assertThat(result).isSameAs(workflowResult);

        // Verify exchange was appended with correct data
        verify(chatHistoryService).appendExchange(
                eq("conv1"),
                eq(99L),
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
        when(chatHistoryService.getRecentContext(anyString(), anyLong(), anyInt())).thenReturn("");
        when(baseWorkflow.processQuestion(anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(Map.of("nlp_answer", "answer"));

        service.processQuestionWithHistory("q", "user", 1);

        ArgumentCaptor<String> standaloneCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatHistoryService).appendExchange(anyString(), anyLong(), anyString(),
                standaloneCaptor.capture(), anyString(), anyInt(), any());
        assertThat(standaloneCaptor.getValue()).isEmpty();
    }

    @Test
    @DisplayName("processQuestionWithHistory: metadata passed to appendExchange strips sensitive keys")
    void processQuestionWithHistory_metadataStripping() {
        when(chatHistoryService.getRecentContext(anyString(), anyLong(), anyInt())).thenReturn("");
        Map<String, Object> workflowResult = Map.of(
                "nlp_answer", "ans",
                "citations", "cite",
                "prefetched_docs", "docs",
                "intent", "document"
        );
        when(baseWorkflow.processQuestion(anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(workflowResult);

        service.processQuestionWithHistory("q", "user", 1);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass(Map.class);
        verify(chatHistoryService).appendExchange(anyString(), anyLong(), anyString(),
                anyString(), anyString(), anyInt(), metaCaptor.capture());

        Map<String, Object> captured = metaCaptor.getValue();
        assertThat(captured).doesNotContainKey("nlp_answer");
        assertThat(captured).doesNotContainKey("citations");
        assertThat(captured).doesNotContainKey("prefetched_docs");
        assertThat(captured).containsKey("intent");
    }
}
