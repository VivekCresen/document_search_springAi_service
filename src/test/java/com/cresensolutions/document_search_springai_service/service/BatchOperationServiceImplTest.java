package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import com.cresensolutions.document_search_springai_service.domain.FilePath;
import com.cresensolutions.document_search_springai_service.repository.FileMetadataRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.BatchOperationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BatchOperationServiceImplTest {

    @Mock
    private FileMetadataRepository fileMetadataRepository;

    @InjectMocks
    private BatchOperationServiceImpl batchOperationService;

    private FileMetadata file1;
    private FileMetadata file2;

    @BeforeEach
    void setUp() {
        file1 = new FileMetadata();
        file1.setFilepath(FilePath.of(Arrays.asList("folder1", "doc1")));
        file1.setStatus(FileMetadata.FileStatus.UPLOADED);

        file2 = new FileMetadata();
        file2.setFilepath(FilePath.of(Arrays.asList("folder1", "doc2")));
        file2.setStatus(FileMetadata.FileStatus.UPLOADED);
    }

    @Test
    void batchUpdateStatus_UpdatesValidDocuments() {
        List<String> docIds = Arrays.asList("doc1", "doc2", "invalidDoc");

        when(fileMetadataRepository.findByDocumentId("doc1")).thenReturn(Optional.of(file1));
        when(fileMetadataRepository.findByDocumentId("doc2")).thenReturn(Optional.of(file2));
        when(fileMetadataRepository.findByDocumentId("invalidDoc")).thenReturn(Optional.empty());

        batchOperationService.batchUpdateStatus(docIds, FileMetadata.FileStatus.INDEXED);

        assertEquals(FileMetadata.FileStatus.INDEXED, file1.getStatus());
        assertEquals(FileMetadata.FileStatus.INDEXED, file2.getStatus());
        
        verify(fileMetadataRepository, times(1)).saveAll(anyList());
    }

    @Test
    void getFilesByDocumentIds_ReturnsMapOfValidDocuments() {
        List<String> docIds = Arrays.asList("doc1", "invalidDoc");

        when(fileMetadataRepository.findByDocumentId("doc1")).thenReturn(Optional.of(file1));
        when(fileMetadataRepository.findByDocumentId("invalidDoc")).thenReturn(Optional.empty());

        Map<String, FileMetadata> result = batchOperationService.getFilesByDocumentIds(docIds);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("folder1/doc1"));
        assertEquals(file1, result.get("folder1/doc1"));
    }

    @Test
    void getFilesByFolderIds_ReturnsListOfFiles() {
        List<String> folderIds = Arrays.asList("folder1");

        when(fileMetadataRepository.findByFolderId("folder1")).thenReturn(Arrays.asList(file1, file2));

        List<FileMetadata> result = batchOperationService.getFilesByFolderIds(folderIds);

        assertEquals(2, result.size());
        assertTrue(result.contains(file1));
        assertTrue(result.contains(file2));
    }
}
