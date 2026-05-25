package com.cresensolutions.document_search_springai_service.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Configuration class enabling Caffeine-backed caching.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /**
     * Instantiates the core CacheManager defining active user restriction and file metadata cache profiles.
     *
     * @return CacheManager instance configured with Caffeine builder
     */
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
                "userRestrictions",
                "fileMetadata",
                "blobNames",
                "stableFiles",
                "unstableUris"
        );
        cacheManager.setCaffeine(caffeineCacheBuilder());
        return cacheManager;
    }

    /**
     * Constructs the standard Caffeine builder boundary limits and TTL specs.
     *
     * @return Caffeine cache builder
     */
    private Caffeine<Object, Object> caffeineCacheBuilder() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats();
    }
}
