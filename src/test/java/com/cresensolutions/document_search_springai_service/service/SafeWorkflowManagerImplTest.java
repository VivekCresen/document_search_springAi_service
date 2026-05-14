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
import java.util.UUID;
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

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OTHER_USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

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
        SecuredEnhancedUnifiedWorkflow workflow = service.getOrCreateConversation("conv1", USER_ID);
        assertThat(workflow).isNotNull();
        verify(chatHistoryService).startNewChatWithId("conv1", USER_ID);
    }

    @Test
    @DisplayName("getOrCreateConversation: retrieves existing from cache within TTL")
    void getOrCreateConversation_returnsCached() {
        SecuredEnhancedUnifiedWorkflow w1 = service.getOrCreateConversation("conv1", USER_ID);
        SecuredEnhancedUnifiedWorkflow w2 = service.getOrCreateConversation("conv1", USER_ID);
        
        assertThat(w1).isSameAs(w2);
        // Should only be called once when creating the new cache entry
        verify(chatHistoryService).startNewChatWithId("conv1", USER_ID);
    }

    @Test
    @DisplayName("processQuestionAsync: executes asynchronously using provided taskExecutor")
    void processQuestionAsync_executes() throws Exception {
        when(workflowProperty.getRequestTimeoutSeconds()).thenReturn(30L);
        when(chatHistoryService.getRecentContext(anyString(), any(java.util.UUID.class), anyInt())).thenReturn("");
        
        // Setup internal mock behavior correctly to return map without failing
        when(baseWorkflow.processQuestion(anyString(), anyString(), anyString(), anyString(), anyInt(), any(java.util.UUID.class)))
                .thenReturn(Map.of("test", "success"));

        CompletableFuture<Map<String, Object>> future = service.processQuestionAsync("conv1", "q1", "user", 1, USER_ID);
        Map<String, Object> result = future.get();
        
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("getStats: correctly returns metrics")
    void getStats_returnsMetrics() {
        when(workflowProperty.getConversationCacheSize()).thenReturn(100);
        
        service.getOrCreateConversation("conv1", USER_ID);
        service.getOrCreateConversation("conv2", OTHER_USER_ID);

        Map<String, Object> stats = service.getStats();
        
        assertThat(stats.get("active_conversations")).isEqualTo(2);
        assertThat(stats.get("conversation_cache_size")).isEqualTo(100);
        assertThat(stats.get("conversation_timeout_seconds")).isEqualTo(3600L);
        assertThat(stats.get("expired_conversations")).isEqualTo(0L);
    }
}
