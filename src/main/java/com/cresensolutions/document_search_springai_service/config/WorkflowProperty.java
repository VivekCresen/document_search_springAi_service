package com.cresensolutions.document_search_springai_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties class for document search workflow limits and parameters.
 * Mapped to the prefix "doc-search.workflow".
 */
@Data
@Component
@ConfigurationProperties(prefix = "doc-search.workflow")
public class WorkflowProperty {

    /**
     * Standard conversation history cache entry capacity. Defaults to 500.
     */
    private int conversationCacheSize = 500;

    /**
     * Timeout duration limit for cache entries in seconds. Defaults to 3600.
     */
    private long conversationTimeoutSeconds = 3600;

    /**
     * Maximum HTTP/thread processing request time boundaries before canceling tasks. Defaults to 500.
     */
    private long requestTimeoutSeconds = 500;

    /**
     * Number of recent messages from chat history to include as LLM history context. Defaults to 10.
     */
    private int recentMessageLimit = 10;

    /**
     * Background response callback forwarding target URL endpoint.
     */
    private String targetEndpoint;

    /**
     * Maximum character count permitted in RAG system contexts. Defaults to 24000.
     */
    private int ragMaxContextChars = 24000;

    /**
     * Maximum character count allowed per source document segment block. Defaults to 6000.
     */
    private int ragMaxDocumentChars = 6000;
}
