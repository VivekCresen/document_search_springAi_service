package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.service.SchemaRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST Controller for interacting with registered schemas and database views.
 */
@RestController
@RequestMapping("/api/v1/schema")
@RequiredArgsConstructor
@Slf4j
public class SchemaController {

    private final SchemaRegistryService schemaRegistryService;

    /**
     * Resolves currently active registered database view schemas and routing metadata.
     *
     * @return active view details map response
     */
    @GetMapping("/active")
    public ResponseEntity<Map<String, Object>> getActiveSchemas() {
        return ResponseEntity.ok(Map.of(
                "views", schemaRegistryService.getActiveViews(),
                "descriptions", schemaRegistryService.getViewDescriptions(),
                "routing_metadata", schemaRegistryService.getViewRoutingMetadata()
        ));
    }

    /**
     * Triggers manual schema definition updates and view caches reload from database catalogs.
     *
     * @return refresh outcome status map
     */
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, String>> refreshSchemas() {
        try {
            schemaRegistryService.refresh();
            return ResponseEntity.ok(Map.of("status", "success", "message", "Schema registry refreshed successfully"));
        } catch (Exception e) {
            log.error("Failed to refresh schema registry", e);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", "Failed to refresh schemas: " + e.getMessage()));
        }
    }
}
