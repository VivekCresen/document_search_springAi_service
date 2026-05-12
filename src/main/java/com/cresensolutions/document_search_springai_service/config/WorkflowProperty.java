package com.cresensolutions.document_search_springai_service.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "doc-search.workflow")
public class WorkflowProperty {

    private int conversationCacheSize = 500;
    private long conversationTimeoutSeconds = 3600;
    private long requestTimeoutSeconds = 120;
    private int recentMessageLimit = 10;
}
