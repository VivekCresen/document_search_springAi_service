package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import com.cresensolutions.document_search_springai_service.service.SafeWorkflowManager;
import com.cresensolutions.document_search_springai_service.service.SecuredEnhancedUnifiedWorkflow;
import com.cresensolutions.document_search_springai_service.service.SecuredUnifiedQueryWorkflow;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinates query workflows and keeps lightweight per-conversation workflow instances in memory.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SafeWorkflowManagerImpl implements SafeWorkflowManager {

    private final SecuredUnifiedQueryWorkflow baseWorkflow;
    private final ChatHistoryService chatHistoryService;
    private final WorkflowProperty workflowProperty;
    @Qualifier("taskExecutor")
    private final Executor taskExecutor;
    private final Map<String, CachedConversation> activeConversations = new ConcurrentHashMap<>();
    private final AtomicInteger requestCounter = new AtomicInteger();

    @PostConstruct
    void initialize() {
        log.info("Initialized SafeWorkflowManager with SecuredUnifiedQueryWorkflow");
    }

    @Override
    public Map<String, Object> processQuestion(
            String conversationId,
            String question,
            String username,
            Integer questionId,
            Long userId
    ) {
        SecuredEnhancedUnifiedWorkflow workflow = getOrCreateConversation(conversationId, userId);
        return workflow.processQuestionWithHistory(question, username, questionId);
    }

    @Override
    public CompletableFuture<Map<String, Object>> processQuestionAsync(
            String conversationId,
            String question,
            String username,
            Integer questionId,
            Long userId
    ) {
        return CompletableFuture.supplyAsync(
                        () -> processQuestion(conversationId, question, username, questionId, userId),
                        taskExecutor
                )
                .orTimeout(workflowProperty.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public SecuredEnhancedUnifiedWorkflow getOrCreateConversation(String conversationId, Long userId) {
        String cacheKey = cacheKey(conversationId, userId);
        CachedConversation cached = activeConversations.compute(cacheKey, (key, existing) -> {
            if (existing != null && !existing.isExpired(workflowProperty.getConversationTimeoutSeconds())) {
                existing.touch();
                return existing;
            }
            chatHistoryService.startNewChatWithId(conversationId, userId);
            return new CachedConversation(new SecuredEnhancedUnifiedWorkflowImpl(
                    baseWorkflow,
                    chatHistoryService,
                    conversationId,
                    userId,
                    workflowProperty.getRecentMessageLimit()
            ));
        });
        cleanupOccasionally();
        return cached.workflow();
    }

    private String cacheKey(String conversationId, Long userId) {
        // Include user id so two users cannot share the same cached conversation accidentally.
        return conversationId + "::" + (userId == null ? "anonymous" : userId);
    }

    private void cleanupOccasionally() {
        // Avoid sorting the cache on every request; periodic cleanup is enough for this small map.
        if (requestCounter.incrementAndGet() % 64 == 0) {
            cleanupIfNeeded();
        }
    }

    private void cleanupIfNeeded() {
        int maxSize = workflowProperty.getConversationCacheSize();
        if (activeConversations.size() <= maxSize) {
            return;
        }
        activeConversations.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getValue().lastAccessed()))
                .limit(Math.max(1, maxSize / 10))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(activeConversations::remove);
    }

    /**
     * Tracks the workflow object and last access time for cache expiration.
     */
    private static final class CachedConversation {
        private final SecuredEnhancedUnifiedWorkflow workflow;
        private Instant lastAccessed;

        private CachedConversation(SecuredEnhancedUnifiedWorkflow workflow) {
            this.workflow = workflow;
            this.lastAccessed = Instant.now();
        }

        private SecuredEnhancedUnifiedWorkflow workflow() {
            return workflow;
        }

        private Instant lastAccessed() {
            return lastAccessed;
        }

        private void touch() {
            lastAccessed = Instant.now();
        }

        private boolean isExpired(long timeoutSeconds) {
            return Instant.now().minusSeconds(timeoutSeconds).isAfter(lastAccessed);
        }
    }
}
