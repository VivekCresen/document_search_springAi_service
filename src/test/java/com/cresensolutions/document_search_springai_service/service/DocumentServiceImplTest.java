package com.cresensolutions.document_search_springai_service.service;

import com.azure.core.http.rest.PagedIterable;
import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.BlobItemProperties;
import com.cresensolutions.document_search_springai_service.config.CloudProperty;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import com.cresensolutions.document_search_springai_service.domain.FilePath;
import com.cresensolutions.document_search_springai_service.domain.PrestageDocument;
import com.cresensolutions.document_search_springai_service.repository.FileInIndexRepository;
import com.cresensolutions.document_search_springai_service.repository.FileMetadataRepository;
import com.cresensolutions.document_search_springai_service.repository.PrestageDocumentRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.DocumentServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentServiceImpl Tests")
class DocumentServiceImplTest {

    @Mock BlobServiceClient blobServiceClient;
    @Mock BlobContainerClient blobContainerClient;
    @Mock BlobClient blobClient;
    @Mock FileMetadataRepository fileMetadataRepository;
    @Mock FileInIndexRepository fileInIndexRepository;
    @Mock PrestageDocumentRepository prestageDocumentRepository;
    @Mock CloudProperty cloudProperty;
    @Mock ObjectMapper objectMapper;

    @InjectMocks DocumentServiceImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(cloudProperty.getContainerName()).thenReturn("test-container");
        lenient().when(cloudProperty.getAccountUrl()).thenReturn("https://account.blob.core.windows.net/");
        lenient().when(blobServiceClient.getBlobContainerClient(anyString())).thenReturn(blobContainerClient);
        lenient().when(blobContainerClient.exists()).thenReturn(true);
        // init() will fire here
        service.init();
    }

    // -------------------------------------------------------------------------
    // uploadDocument
    // -------------------------------------------------------------------------

    /**
     * Verifies that a document is successfully uploaded to Azure and its metadata 
     * is saved to the database.
     */
    @Test
    @DisplayName("uploadDocument: happy path — uploads file and returns documentId")
    void uploadDocument_success() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "test.pdf",
                "application/pdf", "content".getBytes());

        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);
        when(fileMetadataRepository.findByFilePathJson(any())).thenReturn(Optional.empty());
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"filePath\":[\"f\",\"docId\",\"test.pdf\"]}");
        when(fileMetadataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        String docId = service.uploadDocument(file, "folder1", "vivek");

        assertThat(docId).isNotBlank();
        verify(blobClient).upload(any(InputStream.class), eq(true));
        verify(fileMetadataRepository).save(any(FileMetadata.class));
    }

    /**
     * Verifies that uploading an empty file throws an IllegalArgumentException.
     */
    @Test
    @DisplayName("uploadDocument: empty file throws IllegalArgumentException")
    void uploadDocument_emptyFile_throwsException() {
        MockMultipartFile emptyFile = new MockMultipartFile("file", new byte[0]);
        assertThatThrownBy(() -> service.uploadDocument(emptyFile, "folder1", "vivek"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File is empty");
    }

    // -------------------------------------------------------------------------
    // uploadFile
    // -------------------------------------------------------------------------

    /**
     * Verifies that uploading a new file via FilePath correctly saves a new metadata record.
     */
    @Test
    @DisplayName("uploadFile: new file — uploads and saves metadata")
    void uploadFile_newFile_savesMetadata() throws IOException {
        FilePath fileInfo = FilePath.of(Arrays.asList("CMRUS", "USA-2023-651", "ABC.png"));
        MockMultipartFile input = new MockMultipartFile("file", "ABC.png",
                "image/png", "bytes".getBytes());

        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(false);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"filePath\":[\"CMRUS\",\"USA-2023-651\",\"ABC.png\"]}");
        when(fileMetadataRepository.findByFilePathJson(any())).thenReturn(Optional.empty());
        when(fileMetadataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Boolean result = service.uploadFile(fileInfo, input, "UPLOADED", "vivek");

        assertThat(result).isTrue();
        verify(fileMetadataRepository).save(argThat(m ->
                m.getCreatedBy().equals("vivek") &&
                m.getStatus() == FileMetadata.FileStatus.UPLOADED));
    }

    /**
     * Verifies that uploading an existing file correctly updates the existing metadata record.
     */
    @Test
    @DisplayName("uploadFile: existing file — updates metadata instead of inserting")
    void uploadFile_existingFile_updatesMetadata() throws IOException {
        FilePath fileInfo = FilePath.of(Arrays.asList("CMRUS", "USA-2023-651", "ABC.png"));
        MockMultipartFile input = new MockMultipartFile("file", "ABC.png",
                "image/png", "bytes".getBytes());

        FileMetadata existing = FileMetadata.builder()
                .createdBy("old-user")
                .status(FileMetadata.FileStatus.UPLOADED)
                .fileSizeInMb(BigDecimal.ZERO)
                .build();

        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        when(blobClient.exists()).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"filePath\":[]}");
        when(fileMetadataRepository.findByFilePathJson(any())).thenReturn(Optional.of(existing));
        when(fileMetadataRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Boolean result = service.uploadFile(fileInfo, input, "INDEXED", "vivek");

        assertThat(result).isTrue();
        verify(blobClient).delete();   // old blob deleted before re-upload
        assertThat(existing.getCreatedBy()).isEqualTo("vivek");
        assertThat(existing.getStatus()).isEqualTo(FileMetadata.FileStatus.INDEXED);
    }

    @Test
    @DisplayName("uploadFile: null file throws IllegalArgumentException")
    void uploadFile_nullFile_throws() {
        FilePath fileInfo = FilePath.of(Arrays.asList("a", "b"));
        assertThatThrownBy(() -> service.uploadFile(fileInfo, null, "UPLOADED", "vivek"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // -------------------------------------------------------------------------
    // downloadFile
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("downloadFile: returns stream when metadata found")
    void downloadFile_found() throws IOException {
        FilePath fileInfo = FilePath.of(Arrays.asList("CMRUS", "USA-2023-651", "ABC.png"));
        FileMetadata meta = FileMetadata.builder().blobName("CMRUS/USA-2023-651/ABC.png").build();

        when(objectMapper.writeValueAsString(any())).thenReturn("{\"filePath\":[]}");
        when(fileMetadataRepository.findByFilePathJson(any())).thenReturn(Optional.of(meta));
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
        doAnswer(inv -> { ((ByteArrayOutputStream) inv.getArgument(0)).write("data".getBytes()); return null; })
                .when(blobClient).downloadStream(any(ByteArrayOutputStream.class));

        ByteArrayOutputStream result = service.downloadFile(fileInfo);

        assertThat(result).isNotNull();
        assertThat(result.toByteArray()).isNotEmpty();
    }

    @Test
    @DisplayName("downloadFile: returns null when metadata not found")
    void downloadFile_notFound() throws IOException {
        FilePath fileInfo = FilePath.of(Arrays.asList("CMRUS", "missing.pdf"));
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"filePath\":[]}");
        when(fileMetadataRepository.findByFilePathJson(any())).thenReturn(Optional.empty());

        ByteArrayOutputStream result = service.downloadFile(fileInfo);

        assertThat(result).isNull();
        verify(blobContainerClient, never()).getBlobClient(any());
    }

    // -------------------------------------------------------------------------
    // downloadFileDirectly
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("downloadFileDirectly: bypasses DB and downloads directly")
    void downloadFileDirectly_success() {
        FilePath fileInfo = FilePath.of(Arrays.asList("folder", "file.pdf"));
        when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);

        ByteArrayOutputStream result = service.downloadFileDirectly(fileInfo);

        verify(blobClient).downloadStream(any(ByteArrayOutputStream.class));
        assertThat(result).isNotNull();
    }

    // -------------------------------------------------------------------------
    // getBlobNameByDocumentId
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getBlobNameByDocumentId: returns blob name from repo")
    void getBlobNameByDocumentId_found() {
        FileMetadata meta = FileMetadata.builder().blobName("folder/docId/file.pdf").build();
        when(fileMetadataRepository.findByDocumentId("docId")).thenReturn(Optional.of(meta));

        String result = service.getBlobNameByDocumentId("docId");

        assertThat(result).isEqualTo("folder/docId/file.pdf");
    }

    @Test
    @DisplayName("getBlobNameByDocumentId: throws when not found")
    void getBlobNameByDocumentId_notFound() {
        when(fileMetadataRepository.findByDocumentId("bad-id")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getBlobNameByDocumentId("bad-id"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("bad-id");
    }

    // -------------------------------------------------------------------------
    // getAccessibleStableFiles
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAccessibleStableFiles: no restrictions returns all stable files")
    void getAccessibleStableFiles_noRestrictions() {
        List<FileInIndex> expected = List.of(FileInIndex.builder().blobUri("blob/a").build());
        when(fileInIndexRepository.findAllStableFiles()).thenReturn(expected);

        List<FileInIndex> result = service.getAccessibleStableFiles(List.of());

        assertThat(result).isSameAs(expected);
    }

    @Test
    @DisplayName("getAccessibleStableFiles: restricted folders filters properly")
    void getAccessibleStableFiles_withRestrictions() {
        List<Integer> restricted = List.of(5, 9);
        List<FileInIndex> expected = List.of(FileInIndex.builder().blobUri("blob/b").build());
        when(fileInIndexRepository.findAccessibleStableFiles(restricted)).thenReturn(expected);

        List<FileInIndex> result = service.getAccessibleStableFiles(List.of("5", "9"));

        assertThat(result).isSameAs(expected);
    }

    // -------------------------------------------------------------------------
    // updateFileStatus
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("updateFileStatus: updates existing metadata status")
    void updateFileStatus_existing() {
        FileMetadata meta = FileMetadata.builder().status(FileMetadata.FileStatus.UPLOADED).build();
        when(fileMetadataRepository.findByDocumentId("doc1")).thenReturn(Optional.of(meta));

        service.updateFileStatus("doc1", FileMetadata.FileStatus.INDEXED);

        assertThat(meta.getStatus()).isEqualTo(FileMetadata.FileStatus.INDEXED);
        verify(fileMetadataRepository).save(meta);
    }

    @Test
    @DisplayName("updateFileStatus: noop when documentId not found")
    void updateFileStatus_notFound() {
        when(fileMetadataRepository.findByDocumentId("x")).thenReturn(Optional.empty());
        service.updateFileStatus("x", FileMetadata.FileStatus.FAILED);
        verify(fileMetadataRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // FilePath validation
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("uploadFile: invalid empty filePath throws IllegalArgumentException")
    void uploadFile_invalidFilePath_throws() {
        FilePath invalid = FilePath.of(List.of());
        MockMultipartFile file = new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes());

        assertThatThrownBy(() -> service.uploadFile(invalid, file, "UPLOADED", "vivek"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("filePath");
    }

    @Test
    @DisplayName("syncAllMetadataFromAzure: syncs blobs from container to DB")
    void syncAllMetadataFromAzure_success() throws JsonProcessingException {
        // Mock a blob item with name, size, and content type
        BlobItem blobItem = mock(BlobItem.class);
        BlobItemProperties props = mock(BlobItemProperties.class);

        when(blobItem.getName()).thenReturn("folder/doc/file.pdf");
        when(blobItem.getProperties()).thenReturn(props);
        when(props.getContentLength()).thenReturn(1024L * 1024L); // 1MB
        when(props.getContentType()).thenReturn("application/pdf");

        // Mock the listBlobs() response
        PagedIterable<BlobItem> pagedIterable = mock(PagedIterable.class);
        when(blobContainerClient.listBlobs()).thenReturn(pagedIterable);

        // Simulate the forEach iteration over the paged result
        doAnswer(inv -> {
            java.util.function.Consumer<BlobItem> consumer = inv.getArgument(0);
            consumer.accept(blobItem);
            return null;
        }).when(pagedIterable).forEach(any());

        // Mock repository calls for flat metadata and hierarchical tree
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"filePath\":[\"folder\",\"doc\",\"file.pdf\"]}");
        when(fileMetadataRepository.findByFilePathJson(any())).thenReturn(Optional.empty());

        // Mock prestage repository (hierarchical tree)
        when(prestageDocumentRepository.findByNameAndParentAndFile(anyString(), any(), anyBoolean()))
                .thenReturn(Optional.empty());
        when(prestageDocumentRepository.save(any(PrestageDocument.class))).thenAnswer(i -> i.getArgument(0));

        // Execute sync
        service.syncAllMetadataFromAzure();

        // Verify that flat metadata was saved
        verify(fileMetadataRepository).save(argThat(m ->
                m.getBlobName().equals("folder/doc/file.pdf") &&
                m.getCreatedBy().equals("SYSTEM_SYNC")
        ));

        // Verify that tree nodes (folder, doc, file.pdf) were saved
        verify(prestageDocumentRepository, atLeast(3)).save(any(PrestageDocument.class));
    }
}
