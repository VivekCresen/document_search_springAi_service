package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.service.Impl.SafeWorkflowManagerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
@DisplayName("SafeWorkflowManagerImpl Tests")
class SafeWorkflowManagerImplTest {

    @Mock
    SecuredUnifiedQueryWorkflow baseWorkflow;

    @Mock
    ChatHistoryService chatHistoryService;

    @Mock
    WorkflowProperty workflowProperty;

    Executor taskExecutor = new SyncTaskExecutor();

    SafeWorkflowManagerImpl service;

    @BeforeEach
    void setUp() {
        service = new SafeWorkflowManagerImpl(baseWorkflow, chatHistoryService, workflowProperty, taskExecutor);
        lenient().when(workflowProperty.getConversationTimeoutSeconds()).thenReturn(3600L);
        lenient().when(workflowProperty.getRecentMessageLimit()).thenReturn(10);
    }

    @Test
    @DisplayName("getOrCreateConversation: creates new workflow and invokes chatHistoryService")
    void getOrCreateConversation_createsNew() {
        SecuredEnhancedUnifiedWorkflow workflow = service.getOrCreateConversation("conv1", 1L);
        assertThat(workflow).isNotNull();
        verify(chatHistoryService).startNewChatWithId("conv1", 1L);
    }

    @Test
    @DisplayName("getOrCreateConversation: retrieves existing from cache within TTL")
    void getOrCreateConversation_returnsCached() {
        SecuredEnhancedUnifiedWorkflow w1 = service.getOrCreateConversation("conv1", 1L);
        SecuredEnhancedUnifiedWorkflow w2 = service.getOrCreateConversation("conv1", 1L);
        
        assertThat(w1).isSameAs(w2);
        // Should only be called once when creating the new cache entry
        verify(chatHistoryService).startNewChatWithId("conv1", 1L);
    }

    @Test
    @DisplayName("processQuestionAsync: executes asynchronously using provided taskExecutor")
    void processQuestionAsync_executes() throws Exception {
        when(workflowProperty.getRequestTimeoutSeconds()).thenReturn(30L);
        when(chatHistoryService.getRecentContext(anyString(), anyLong(), anyInt())).thenReturn("");
        
        // Setup internal mock behavior correctly to return map without failing
        when(baseWorkflow.processQuestion(anyString(), anyString(), anyString(), anyString(), anyInt(), anyLong()))
                .thenReturn(Map.of("test", "success"));

        CompletableFuture<Map<String, Object>> future = service.processQuestionAsync("conv1", "q1", "user", 1, 1L);
        Map<String, Object> result = future.get();
        
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getStats: correctly returns metrics")
    void getStats_returnsMetrics() {
        when(workflowProperty.getConversationCacheSize()).thenReturn(100);
        
        service.getOrCreateConversation("conv1", 1L);
        service.getOrCreateConversation("conv2", 2L);

        Map<String, Object> stats = service.getStats();
        
        assertThat(stats.get("active_conversations")).isEqualTo(2);
        assertThat(stats.get("conversation_cache_size")).isEqualTo(100);
        assertThat(stats.get("conversation_timeout_seconds")).isEqualTo(3600L);
        assertThat(stats.get("expired_conversations")).isEqualTo(0L);
    }
}
