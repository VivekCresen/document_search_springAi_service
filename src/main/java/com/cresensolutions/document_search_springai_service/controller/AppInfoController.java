package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.config.WorkflowProperty;
import com.cresensolutions.document_search_springai_service.service.SafeWorkflowManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * REST Controller responsible for providing basic system metadata, health checks, and operational metrics.
 */
@RestController
@RequiredArgsConstructor
public class AppInfoController {

    private final SafeWorkflowManager workflowManager;
    private final WorkflowProperty workflowProperty;
    private final ObjectProvider<HealthEndpoint> healthEndpointProvider;

    /**
     * Endpoint resolving the application name, version, feature lists, and main available endpoints map.
     *
     * @return REST API description metadata map
     */
    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "name", "MM Chatbot API - Secured Spring AI",
                "version", "5.0.0",
                "features", List.of(
                        "Spring AI ChatClient workflow",
                        "Blob-backed PDF highlighting",
                        "SAS URL download and view links per citation",
                        "Folder-level access control",
                        "Background response forwarding",
                        "JSONB filePath upload metadata"
                ),
                "endpoints", Map.of(
                        "query", "POST /query",
                        "documents_upload_json_path", "POST /api/documents/upload-file",
                        "documents_download_json_path", "POST /api/documents/download-file",
                        "permissions_check", "POST /permissions/check",
                        "health", "GET /health",
                        "metrics", "GET /metrics"
                )
        );
    }

    /**
     * Exposes simple system health status checks querying the actuator backend state.
     *
     * @return system health details map
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        HealthEndpoint healthEndpoint = healthEndpointProvider.getIfAvailable();
        String status = "healthy";
        if (healthEndpoint != null) {
            HealthComponent health = healthEndpoint.health();
            status = health.getStatus().getCode().equalsIgnoreCase("UP") ? "healthy" : "unhealthy";
        }
        return Map.of(
                "status", status,
                "timestamp", OffsetDateTime.now().toString(),
                "db_pool_status", "managed_by_spring_datasource",
                "worker_pid", ProcessHandle.current().pid()
        );
    }

    /**
     * Resolves currently active configuration boundaries and thread pool workflow statistics.
     *
     * @return application operational metrics map
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return Map.of(
                "timestamp", OffsetDateTime.now().toString(),
                "config", Map.of(
                        "request_timeout_seconds", workflowProperty.getRequestTimeoutSeconds(),
                        "conversation_cache_size", workflowProperty.getConversationCacheSize(),
                        "conversation_timeout_seconds", workflowProperty.getConversationTimeoutSeconds()
                ),
                "current", workflowManager.getStats()
        );
    }
}
