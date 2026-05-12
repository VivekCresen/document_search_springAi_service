package com.cresensolutions.document_search_springai_service.service.Impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.cresensolutions.document_search_springai_service.config.CloudProperty;
import com.cresensolutions.document_search_springai_service.dto.DocumentLinkResponse;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import com.cresensolutions.document_search_springai_service.domain.FilePath;
import com.cresensolutions.document_search_springai_service.repository.FileInIndexRepository;
import com.cresensolutions.document_search_springai_service.repository.FileMetadataRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Service implementation for handling document operations with Azure Blob Storage.
 * Provides functionality for uploading, downloading, and managing documents.
 * Integrates with database repositories for metadata and indexing information.
 * Uses caching for performance optimization.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements com.cresensolutions.document_search_springai_service.service.DocumentService {

    // Error message constants for consistent error handling
    private static final String CLIENT_AUTHENTICATION_EXCEPTION_ERR_MSG = "Azure authentication failed";
    private static final String AZURE_EXCEPTION_ERR_MSG = "Azure storage error occurred";

    // Azure Blob Storage client for blob operations
    private final BlobServiceClient blobServiceClient;
    // Repository for file metadata persistence
    private final FileMetadataRepository fileMetadataRepository;
    // Repository for file indexing information
    private final FileInIndexRepository fileInIndexRepository;
    // Configuration properties for cloud services
    private final CloudProperty cloudProperty;

    // Blob container client initialized during startup
    private BlobContainerClient containerClient;

    /**
     * Initializes the Azure Blob Storage container client.
     * Called after dependency injection is complete.
     * Creates the container if it doesn't exist.
     */
    @PostConstruct
    public void init() {
        containerClient = getBlobContainerClient(cloudProperty.getContainerName());
        try {
            if (!containerClient.exists()) {
                containerClient.create();
                log.info("Created container: {}", cloudProperty.getContainerName());
            }
        } catch (RuntimeException e) {
            log.warn("Azure Blob container check skipped during startup: {}", e.getMessage());
        }
    }

    /**
     * Uploads a document to Azure Blob Storage and saves metadata to database.
     * Generates a unique document ID and associates the file with a folder and user.
     * Clears relevant caches after successful upload.
     *
     * @param file the multipart file to upload
     * @param folderId the folder identifier for organization
     * @param username the username of the uploader
     * @return the generated document ID
     * @throws IOException if upload fails
     */
    @Transactional
    @CacheEvict(value = {"fileMetadata", "blobNames"}, allEntries = true)
    public String uploadDocument(MultipartFile file, String folderId, String username) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }
        String documentId = UUID.randomUUID().toString();
        boolean uploaded = uploadAndSaveFile(documentId, file, folderId, username);
        if (!uploaded) {
            throw new IOException("Failed to upload file to Azure storage");
        }
        return documentId;
    }

    /**
     * Downloads a document from Azure Blob Storage.
     * Returns the file content as a Spring Resource for streaming.
     *
     * @param documentId the unique identifier of the document
     * @return Resource containing the file data
     */
    public Resource downloadDocument(String documentId) {
        BlobClient blobClient = getBlobClientByDocumentId(documentId);
        log.info("Downloaded document with ID: {}", documentId);
        return new InputStreamResource(blobClient.openInputStream());
    }

    /**
     * Retrieves the blob name associated with a document ID.
     * Uses caching to improve performance for repeated lookups.
     *
     * @param documentId the document identifier
     * @return the blob name in Azure Storage
     */
    @Cacheable(value = "blobNames", key = "#documentId")
    public String getBlobNameByDocumentId(String documentId) {
        return fileMetadataRepository.findByDocumentId(documentId)
                .map(FileMetadata::getBlobName)
                .orElseThrow(() -> new RuntimeException("Document not found with ID: " + documentId));
    }

    public String getFilename(String documentId) {
        return fileMetadataRepository.findByDocumentId(documentId)
                .map(metadata -> metadata.getFilepath().getFileName())
                .orElse("document");
    }

    public DocumentLinkResponse getDocumentLinks(String documentId) {
        String blobName = getBlobNameByDocumentId(documentId);
        String fileName = getFilename(documentId);
        BlobClient blobClient = getBlobContainerClient(cloudProperty.getContainerName()).getBlobClient(blobName);

        return DocumentLinkResponse.builder()
                .documentId(documentId)
                .fileName(fileName)
                .downloadLink(createSasLink(blobClient, "attachment; filename=\"" + fileName + "\""))
                .viewLink(createSasLink(blobClient, "inline; filename=\"" + fileName + "\""))
                .build();
    }

    @Cacheable(value = "stableFiles", key = "#restrictedFolders.hashCode()")
    public List<FileInIndex> getAccessibleStableFiles(List<String> restrictedFolders) {
        if (restrictedFolders.isEmpty()) {
            return fileInIndexRepository.findAllStableFiles();
        }
        List<Integer> restrictedFolderIds = restrictedFolders.stream()
                .map(Integer::valueOf)
                .toList();
        return fileInIndexRepository.findAccessibleStableFiles(restrictedFolderIds);
    }

    @Transactional
    @CacheEvict(value = {"fileMetadata", "stableFiles"}, allEntries = true)
    public void updateFileStatus(String documentId, FileMetadata.FileStatus status) {
        fileMetadataRepository.findByDocumentId(documentId).ifPresent(metadata -> {
            metadata.setStatus(status);
            fileMetadataRepository.save(metadata);
            log.info("Updated status for document {} to {}", documentId, status);
        });
    }

    @Cacheable(value = "fileMetadata", key = "#folderId")
    public List<FileMetadata> getFilesByFolder(String folderId) {
        return fileMetadataRepository.findByFolderId(folderId);
    }


    private String getFilePath(FilePath filePath) {
        String uploadFilePath = filePath.toFullPath();
        log.info("Upload file path {}", uploadFilePath);
        return uploadFilePath;
    }

   
    public BlobContainerClient getBlobContainerClient(String azureContainerName) {
        return blobServiceClient.getBlobContainerClient(azureContainerName);
    }

    public boolean fileUploader(InputStream inputStream, FilePath filePath,
                                String azureContainerName) throws IOException {
        return azureFileUploader(inputStream, filePath, azureContainerName);
    }

    public ByteArrayOutputStream fileDownloader(FilePath filePath, String azureContainerName) {
        return azureFileDownloader(filePath, azureContainerName);
    }


    private boolean azureFileUploader(InputStream inputStream, FilePath filePath,
                                      String azureContainerName) throws IOException {
        try {
            BlobClient blobClient = getBlobContainerClient(azureContainerName).getBlobClient(getFilePath(filePath));
            blobClient.upload(inputStream, true);
            return true;
        } finally {
            inputStream.close();
        }
    }

    private ByteArrayOutputStream azureFileDownloader(FilePath filePath, String azureContainerName) {
        BlobContainerClient blobContainerClient = getBlobContainerClient(azureContainerName);
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        try {
            log.info("Downloading a new object from Azure storage");
            BlobClient blobClient = blobContainerClient.getBlobClient(getFilePath(filePath));
            blobClient.downloadStream(byteArrayOutputStream);
            log.info("File downloaded successfully from azure");
            return byteArrayOutputStream;
        } catch (com.azure.core.exception.ClientAuthenticationException e) {
            log.error(CLIENT_AUTHENTICATION_EXCEPTION_ERR_MSG, e);
        } catch (com.azure.core.exception.AzureException e) {
            log.error(AZURE_EXCEPTION_ERR_MSG, e);
        } catch (Exception e) {
            log.error("Failed to download file", e);
        }
        return byteArrayOutputStream;
    }

   
    private boolean uploadAndSaveFile(String documentId, MultipartFile input,
                                      String folderId, String username) throws IOException {
        boolean fileUploaded;
        Double fileSizeInMb = toMb(input.getSize());
        String originalFilename = input.getOriginalFilename();
        FilePath filePath = FilePath.of(Arrays.asList(folderId, documentId, originalFilename));

        log.info("Created transaction attachment object");

        try (InputStream inputStream = input.getInputStream()) {
            fileUploaded = fileUploader(inputStream, filePath, cloudProperty.getContainerName());
            log.info("File has been uploaded successfully with filepath {}", filePath);

            if (fileUploaded) {
                String blobName = getFilePath(filePath);
                String blobUrl = cloudProperty.getAccountUrl() + cloudProperty.getContainerName() + "/" + blobName;

                FileMetadata existingFile = fileMetadataRepository
                        .findByDocumentId(documentId).orElse(null);
                if (existingFile == null) {
                    fileMetadataRepository.save(FileMetadata.builder()
                            .filepath(filePath)
                            .createdBy(username)
                            .azureBlobUrl(blobUrl)
                            .blobName(blobName)
                            .fileSizeInMb(BigDecimal.valueOf(fileSizeInMb).setScale(2, RoundingMode.HALF_UP))
                            .contentType(input.getContentType())
                            .status(FileMetadata.FileStatus.UPLOADED)
                            .build());
                } else {
                    existingFile.setFilepath(filePath);
                    existingFile.setAzureBlobUrl(blobUrl);
                    existingFile.setBlobName(blobName);
                    existingFile.setFileSizeInMb(BigDecimal.valueOf(fileSizeInMb).setScale(2, RoundingMode.HALF_UP));
                    existingFile.setStatus(FileMetadata.FileStatus.UPLOADED);
                    existingFile.setCreatedBy(username);
                    fileMetadataRepository.save(existingFile);
                }
            }
        }
        return fileUploaded;
    }


    private FilePath buildFilePath(String documentId, String blobName) {
        return FilePath.of(Arrays.asList(blobName));
    }

    private Double toMb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    private BlobClient getBlobClientByDocumentId(String documentId) {
        return getBlobContainerClient(cloudProperty.getContainerName())
                .getBlobClient(getBlobNameByDocumentId(documentId));
    }

    private String createSasLink(BlobClient blobClient, String contentDisposition) {
        BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(cloudProperty.getBlobSasExpiresInSeconds());
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(expiresAt, permission)
                .setContentDisposition(contentDisposition);
        return blobClient.getBlobUrl() + "?" + blobClient.generateSas(values);
    }
}