package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.domain.DbSearchSchemaVersion;
import com.cresensolutions.document_search_springai_service.domain.DbSearchSource;
import com.cresensolutions.document_search_springai_service.dto.ViewRegistryEntry;
import com.cresensolutions.document_search_springai_service.repository.DbSearchSchemaVersionRepository;
import com.cresensolutions.document_search_springai_service.repository.DbSearchSourceRepository;
import com.cresensolutions.document_search_springai_service.service.SchemaRegistryService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * DB-backed schema registry with background refresh every 24 h.
 * Mirrors Python config.py VIEW_SCHEMAS / VIEW_DESCRIPTIONS / VIEW_ROUTING_METADATA.
 *
 * Thread-safety: a ReadWriteLock guards the in-memory snapshot so concurrent
 * query threads never see a partially-updated registry.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaRegistryServiceImpl implements SchemaRegistryService {

    private final DbSearchSourceRepository sourceRepository;
    private final DbSearchSchemaVersionRepository schemaVersionRepository;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    // In-memory snapshots — swapped atomically under write lock
    private volatile List<ViewRegistryEntry> activeViews = Collections.emptyList();
    private volatile Map<String, String> viewDescriptions = Collections.emptyMap();
    private volatile Map<String, Map<String, Object>> viewRoutingMetadata = Collections.emptyMap();

    @PostConstruct
    public void init() {
        try {
            refresh();
            log.info("Schema registry loaded: {} active view(s)", activeViews.size());
        } catch (Exception e) {
            log.warn("Schema registry initial load failed — registry will be empty until next refresh: {}", e.getMessage());
        }
    }

    /** Scheduled refresh every 24 hours (matches Python REGISTRY_REFRESH_HOURS=24). */
    @Scheduled(fixedRateString = "${doc-search.schema-registry.refresh-interval-ms:86400000}")
    @Override
    @Transactional(readOnly = true)
    public void refresh() {
        log.debug("Schema registry refresh starting...");
        List<DbSearchSource> sources = sourceRepository.findByActiveTrue();

        List<ViewRegistryEntry> newViews = new ArrayList<>(sources.size());
        Map<String, String> newDescriptions = new LinkedHashMap<>();
        Map<String, Map<String, Object>> newRoutingMetadata = new LinkedHashMap<>();

        for (DbSearchSource source : sources) {
            Optional<DbSearchSchemaVersion> latestVersion =
                    schemaVersionRepository.findLatestActiveBySourceId(source.getId());

            Map<String, Object> schemaJson = latestVersion
                    .map(DbSearchSchemaVersion::getSchemaJson)
                    .orElse(Collections.emptyMap());

            Map<String, Object> routingMeta = source.getRoutingMetadata() != null
                    ? source.getRoutingMetadata()
                    : Collections.emptyMap();

            List<String> keywords = castStringList(routingMeta.get("confidence_keywords"));
            List<String> categoricalCols = castStringList(routingMeta.get("categorical_columns"));

            ViewRegistryEntry entry = ViewRegistryEntry.builder()
                    .viewName(source.getViewName())
                    .description(source.getDescription() != null ? source.getDescription() : "")
                    .schemaJson(schemaJson)
                    .confidenceKeywords(keywords)
                    .categoricalColumns(categoricalCols)
                    .build();

            newViews.add(entry);
            newDescriptions.put(source.getViewName(), entry.description());
            newRoutingMetadata.put(source.getViewName(), routingMeta);
        }

        // Swap under write lock — fast, no DB I/O inside the lock
        lock.writeLock().lock();
        try {
            activeViews = Collections.unmodifiableList(newViews);
            viewDescriptions = Collections.unmodifiableMap(newDescriptions);
            viewRoutingMetadata = Collections.unmodifiableMap(newRoutingMetadata);
        } finally {
            lock.writeLock().unlock();
        }

        log.info("Schema registry refreshed: {} view(s)", newViews.size());
    }

    @Override
    public List<ViewRegistryEntry> getActiveViews() {
        lock.readLock().lock();
        try {
            return activeViews;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<String, String> getViewDescriptions() {
        lock.readLock().lock();
        try {
            return viewDescriptions;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<String, Map<String, Object>> getViewRoutingMetadata() {
        lock.readLock().lock();
        try {
            return viewRoutingMetadata;
        } finally {
            lock.readLock().unlock();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .toList();
        }
        return Collections.emptyList();
    }
}
