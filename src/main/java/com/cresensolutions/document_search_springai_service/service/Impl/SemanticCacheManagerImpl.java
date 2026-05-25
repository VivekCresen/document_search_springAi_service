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

    /**
     * Post-construct initialization hook. Eagerly ensures the local cache filesystem directory exists.
     */
    @PostConstruct
    public void init() {
        cacheDir = new File(cacheDirPath);
        if (!cacheDir.exists() && !cacheDir.mkdirs()) {
            log.warn("Failed to create semantic cache directory: {}", cacheDirPath);
        }
    }

    /**
     * Retrieves cached embedding vector floats from disk if they exist and are not expired.
     *
     * @param viewName the database view name
     * @param column the target column name
     * @return the list of float vector embeddings, or null on cache miss / expiration
     */
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

    /**
     * Caches categorical string list embedding vectors to disk under safe file names.
     *
     * @param viewName the database view name
     * @param column the target column name
     * @param embeddings list of embedded float vectors
     */
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

    /**
     * Manually deletes cached embedding vector files for a given view and column.
     *
     * @param viewName the database view name
     * @param column the target column name
     */
    @Override
    public void invalidateCache(String viewName, String column) {
        File cacheFile = getCacheFile(viewName, column);
        if (cacheFile.exists() && !cacheFile.delete()) {
            log.warn("Failed to delete semantic cache file for {}.{}", viewName, column);
        }
    }

    /**
     * Resolves the safe File path reference corresponding to a view name and column key on disk.
     *
     * @param viewName the database view name
     * @param column the target column name
     * @return the local cache File handle reference
     */
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
