package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.SemanticCacheManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticCacheManagerImpl implements SemanticCacheManager {

    private final ObjectMapper objectMapper;

    @Value("${doc-search.cache.semantic.dir:/tmp/semantic_cache}")
    private String cacheDirPath;

    @Value("${doc-search.cache.semantic.ttl-seconds:86400}") // 24 hours
    private long cacheTtlSeconds;

    private File cacheDir;

    @PostConstruct
    public void init() {
        cacheDir = new File(cacheDirPath);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            log.warn("Failed to create semantic cache directory: {}", cacheDirPath);
        }
    }

    @Override
    public List<float[]> getCachedEmbeddings(String viewName, String column) {
        File cacheFile = getCacheFile(viewName, column);
        if (!cacheFile.exists()) {
            return null;
        }

        try {
            CacheEntry entry = objectMapper.readValue(cacheFile, CacheEntry.class);
            if (Instant.now().getEpochSecond() - entry.getTimestamp() > cacheTtlSeconds) {
                log.debug("Cache expired for {}.{}", viewName, column);
                cacheFile.delete();
                return null;
            }
            return entry.getEmbeddings();
        } catch (IOException e) {
            log.warn("Failed to read semantic cache file for {}.{}", viewName, column, e);
            return null;
        }
    }

    @Override
    public void setCachedEmbeddings(String viewName, String column, List<float[]> embeddings) {
        File cacheFile = getCacheFile(viewName, column);
        CacheEntry entry = new CacheEntry();
        entry.setTimestamp(Instant.now().getEpochSecond());
        entry.setEmbeddings(embeddings);

        try {
            objectMapper.writeValue(cacheFile, entry);
            log.debug("Cached embeddings for {}.{}", viewName, column);
        } catch (IOException e) {
            log.warn("Failed to write semantic cache file for {}.{}", viewName, column, e);
        }
    }

    @Override
    public void invalidateCache(String viewName, String column) {
        File cacheFile = getCacheFile(viewName, column);
        if (cacheFile.exists() && !cacheFile.delete()) {
            log.warn("Failed to delete semantic cache file for {}.{}", viewName, column);
        }
    }

    private File getCacheFile(String viewName, String column) {
        // Sanitize file name
        String safeName = (viewName + "_" + column).replaceAll("[^a-zA-Z0-9_\\-]", "_") + ".json";
        return new File(cacheDir, safeName);
    }

    @Data
    private static class CacheEntry {
        private long timestamp;
        private List<float[]> embeddings;
    }
}
