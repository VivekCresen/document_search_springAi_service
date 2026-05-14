package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.service.Impl.SemanticCacheManagerImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("SemanticCacheManagerImpl Tests")
class SemanticCacheManagerImplTest {

    SemanticCacheManagerImpl service;
    ObjectMapper objectMapper;
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        service = new SemanticCacheManagerImpl(objectMapper);

        tempDir = Files.createTempDirectory("semantic_cache_test");

        ReflectionTestUtils.setField(service, "cacheDirPath", tempDir.toString());
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 86400L); // 24 hours
        
        service.init();
    }

    @AfterEach
    void tearDown() throws IOException {
        try (Stream<Path> walk = Files.walk(tempDir)) {
            walk.sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    @Test
    @DisplayName("init: creates directory if missing")
    void init_createsDirectory() throws IOException {
        Path newDir = tempDir.resolve("nested");
        ReflectionTestUtils.setField(service, "cacheDirPath", newDir.toString());
        
        service.init();
        
        assertThat(Files.exists(newDir)).isTrue();
        assertThat(Files.isDirectory(newDir)).isTrue();
    }

    @Test
    @DisplayName("getCachedEmbeddings: returns null when cache file does not exist")
    void getCachedEmbeddings_missingFile() {
        List<float[]> embeddings = service.getCachedEmbeddings("my_view", "my_col");
        assertThat(embeddings).isNull();
    }

    @Test
    @DisplayName("setCachedEmbeddings and getCachedEmbeddings: works correctly within TTL")
    void setAndGet_withinTtl() {
        List<float[]> expected = List.of(new float[]{0.1f, 0.2f}, new float[]{0.3f, 0.4f});
        
        service.setCachedEmbeddings("my_view", "my_col", expected);
        
        List<float[]> actual = service.getCachedEmbeddings("my_view", "my_col");
        
        assertThat(actual).isNotNull();
        assertThat(actual).hasSize(2);
        assertThat(actual.get(0)).containsExactly(0.1f, 0.2f);
        assertThat(actual.get(1)).containsExactly(0.3f, 0.4f);
    }

    @Test
    @DisplayName("getCachedEmbeddings: returns null and deletes file when TTL expires")
    void getCachedEmbeddings_expiredTtl() throws InterruptedException {
        // Set TTL to 0 seconds so it expires immediately
        ReflectionTestUtils.setField(service, "cacheTtlSeconds", 0L);
        
        List<float[]> expected = List.of(new float[]{0.1f});
        service.setCachedEmbeddings("my_view", "my_col", expected);
        
        // Wait briefly to ensure epoch second rolls over if needed
        Thread.sleep(1000);

        List<float[]> actual = service.getCachedEmbeddings("my_view", "my_col");
        
        assertThat(actual).isNull();
        
        // Verify file was deleted
        File cacheFile = new File(tempDir.toFile(), "my_view_my_col.json");
        assertThat(cacheFile.exists()).isFalse();
    }

    @Test
    @DisplayName("invalidateCache: deletes the specific cache file")
    void invalidateCache_deletesFile() {
        List<float[]> expected = List.of(new float[]{0.1f});
        service.setCachedEmbeddings("viewA", "colA", expected);
        service.setCachedEmbeddings("viewB", "colB", expected);
        
        service.invalidateCache("viewA", "colA");
        
        assertThat(service.getCachedEmbeddings("viewA", "colA")).isNull();
        assertThat(service.getCachedEmbeddings("viewB", "colB")).isNotNull();
    }
    
    @Test
    @DisplayName("getCacheFile: sanitizes filename")
    void getCacheFile_sanitizes() {
        service.setCachedEmbeddings("bad/view", "col\\name", List.of());
        
        File expectedFile = new File(tempDir.toFile(), "bad_view_col_name.json");
        assertThat(expectedFile.exists()).isTrue();
    }
}
