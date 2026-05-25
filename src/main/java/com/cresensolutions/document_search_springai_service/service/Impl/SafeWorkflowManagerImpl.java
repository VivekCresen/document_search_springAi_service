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

    /**
     * Post-construct initialization hook. Logs the successful coordinator setup.
     */
    @PostConstruct
    void initialize() {
        log.info("Initialized SafeWorkflowManager with SecuredUnifiedQueryWorkflow");
    }

    /**
     * Processes a single question synchronously, retrieving or creating a stateful
     * workflow session for the user's conversation context.
     *
     * @param conversationId current chat session token
     * @param requestId tracing/logging UUID representing this turn
     * @param question raw user question string
     * @param username username of requester
     * @param questionId query sequence turn ID
     * @param userId unique user identifier
     * @return workflow execution results map
     */
    @Override
    public Map<String, Object> processQuestion(
            String conversationId,
            String requestId,
            String question,
            String username,
            Integer questionId,
            java.util.UUID userId
    ) {
        SecuredEnhancedUnifiedWorkflow workflow = getOrCreateConversation(conversationId, userId);
        return workflow.processQuestionWithHistory(requestId, question, username, questionId);
    }

    /**
     * Asynchronously executes query processing in the background, enforcing timeouts.
     *
     * @param conversationId current chat session token
     * @param requestId tracing/logging UUID representing this turn
     * @param question raw user question string
     * @param username username of requester
     * @param questionId query sequence turn ID
     * @param userId unique user identifier
     * @return CompletableFuture completing with the workflow execution results map
     */
    @Override
    public CompletableFuture<Map<String, Object>> processQuestionAsync(
            String conversationId,
            String requestId,
            String question,
            String username,
            Integer questionId,
            java.util.UUID userId
    ) {
        return CompletableFuture.supplyAsync(
                        () -> processQuestion(conversationId, requestId, question, username, questionId, userId),
                        taskExecutor
                 )
                .orTimeout(workflowProperty.getRequestTimeoutSeconds(), TimeUnit.SECONDS);
    }

    /**
     * Retrieves an existing stateful workflow for a conversation or creates a new one.
     * Implements a simple LRU-style cache with timeout expiration.
     *
     * @param conversationId the chat ID
     * @param userId the user ID
     * @return the stateful workflow instance
     */
    @Override
    public SecuredEnhancedUnifiedWorkflow getOrCreateConversation(String conversationId, java.util.UUID userId) {
        String cacheKey = cacheKey(conversationId, userId);
        
        // Use ConcurrentHashMap.compute to ensure atomic updates to the conversation cache
        CachedConversation cached = activeConversations.compute(cacheKey, (key, existing) -> {
            // Check if conversation exists and hasn't expired
            if (existing != null && !existing.isExpired(workflowProperty.getConversationTimeoutSeconds())) {
                existing.touch(); // Update last access time
                return existing;
            }
            
            // Otherwise, initialize a new stateful workflow for this conversation
            chatHistoryService.startNewChatWithId(conversationId, userId);
            return new CachedConversation(new SecuredEnhancedUnifiedWorkflowImpl(
                    baseWorkflow,
                    chatHistoryService,
                    conversationId,
                    userId,
                    workflowProperty.getRecentMessageLimit()
            ));
        });
        
        // Trigger background cleanup based on access frequency
        cleanupOccasionally();
        return cached.workflow();
    }

    /**
     * Computes cache statistics, including active vs. expired workflows and total requests processed.
     *
     * @return map of key performance and capacity metrics
     */
    @Override
    public Map<String, Object> getStats() {
        cleanupIfNeeded();
        long expiredCount = activeConversations.values().stream()
                .filter(conversation -> conversation.isExpired(workflowProperty.getConversationTimeoutSeconds()))
                .count();
        return Map.of(
                "active_conversations", activeConversations.size(),
                "expired_conversations", expiredCount,
                "conversation_cache_size", workflowProperty.getConversationCacheSize(),
                "conversation_timeout_seconds", workflowProperty.getConversationTimeoutSeconds(),
                "request_count", requestCounter.get()
        );
    }

    /**
     * Constructs a scoped composite key for cache lookups to support multiple users.
     *
     * @param conversationId session token
     * @param userId user identifier
     * @return string key representation
     */
    private String cacheKey(String conversationId, java.util.UUID userId) {
        // Include user id so two users cannot share the same cached conversation accidentally.
        return conversationId + "::" + (userId == null ? "anonymous" : userId.toString());
    }

    /**
     * Periodically triggers cache eviction routines on query request count boundaries.
     */
    private void cleanupOccasionally() {
        // Avoid sorting the cache on every request; periodic cleanup is enough for this small map.
        if (requestCounter.incrementAndGet() % 64 == 0) {
            cleanupIfNeeded();
        }
    }

    /**
     * Triggers active cache size reduction via LRU sorting if size limits are breached.
     */
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

        /**
         * Wraps a stateful workflow into a cache record.
         *
         * @param workflow the stateful workflow instance
         */
        private CachedConversation(SecuredEnhancedUnifiedWorkflow workflow) {
            this.workflow = workflow;
            this.lastAccessed = Instant.now();
        }

        /**
         * Obtains the wrapped stateful workflow instance.
         *
         * @return the workflow reference
         */
        private SecuredEnhancedUnifiedWorkflow workflow() {
            return workflow;
        }

        /**
         * Gets the timestamp of last access.
         *
         * @return Instant timestamp
         */
        private Instant lastAccessed() {
            return lastAccessed;
        }

        /**
         * Renews the last access timestamp to the current instant.
         */
        private void touch() {
            lastAccessed = Instant.now();
        }

        /**
         * Evaluates if the elapsed idle duration exceeds the configured lifetime timeout.
         *
         * @param timeoutSeconds lifetime in seconds
         * @return true if expired
         */
        private boolean isExpired(long timeoutSeconds) {
            return Instant.now().minusSeconds(timeoutSeconds).isAfter(lastAccessed);
        }
    }
}
