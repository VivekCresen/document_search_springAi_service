package com.cresensolutions.document_search_springai_service.service.Impl;

import com.azure.core.exception.AzureException;
import com.azure.core.exception.ClientAuthenticationException;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.config.CloudProperty;
import com.cresensolutions.document_search_springai_service.dto.DocumentLinkResponse;
import com.cresensolutions.document_search_springai_service.dto.DownloadedDocument;
import com.cresensolutions.document_search_springai_service.dto.DownloadedFile;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import com.cresensolutions.document_search_springai_service.domain.FilePath;
import com.cresensolutions.document_search_springai_service.domain.demoDocument;
import com.cresensolutions.document_search_springai_service.repository.FileInIndexRepository;
import com.cresensolutions.document_search_springai_service.repository.FileMetadataRepository;
import com.cresensolutions.document_search_springai_service.repository.demoDocumentRepository;
import com.cresensolutions.document_search_springai_service.repository.UserRepository;
import com.cresensolutions.document_search_springai_service.domain.User;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.cresensolutions.document_search_springai_service.service.IndexingCallbackService;

import java.io.BufferedInputStream;
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
 * Follows the company delegation pattern:
 *   uploadFile → uploadAndSaveFile → fileUploader → azureFileUploader
 *   downloadFile → fileDownloader → azureFileDownloader
 * Uses {@link FilePath} (JSON array) as the canonical file-path contract.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentServiceImpl implements com.cresensolutions.document_search_springai_service.service.DocumentService {

    // -------------------------------------------------------------------------
    // Dependencies
    // -------------------------------------------------------------------------
    private final BlobServiceClient blobServiceClient;
    private final FileMetadataRepository fileMetadataRepository;
    private final FileInIndexRepository fileInIndexRepository;
    private final demoDocumentRepository demoDocumentRepository;
    private final CloudProperty cloudProperty;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    /** Notifies the Azure Indexing Service to queue a blob immediately after upload. */
    private final IndexingCallbackService indexingCallbackService;

    /** Eagerly-resolved container client, created once at startup. */
    private BlobContainerClient containerClient;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Post-construct initialization to establish or verify the presence of the default
     * Azure Storage Blob container specified in configuration.
     * Swallows runtime connection errors on startup to enable offline initialization.
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

    // =========================================================================
    // Public API — Upload
    // =========================================================================



    /**
     * Method to upload a file to the cloud and save file information to the database.
     *
     * @param fileInfo     {@link FilePath} containing the hierarchical file path segments
     * @param input        {@link MultipartFile} to be uploaded
     * @param status       processing status string (e.g. "UPLOADED")
     * @param loggedInUser the user who is uploading the file
     * @return {@code true} if the file was successfully uploaded and saved
     * @throws IOException if file upload or saving to database fails
     */
    @Override
    @Transactional
    @CacheEvict(value = {"fileMetadata", "blobNames", "stableFiles"}, allEntries = true)
    public Boolean uploadFile(FilePath fileInfo, MultipartFile input, String status, String loggedInUser) throws IOException {
        return uploadAndSaveFile(fileInfo, input, status, loggedInUser);
    }

    /**
     * Uploads a single document file to storage and saves metadata with default uploaded status.
     * Evicts file-related caches.
     *
     * @param fileInfo hierarchical FilePath segments
     * @param input MultipartFile payload containing the file stream and size
     * @param loggedInUser username of the uploader
     * @return true if successful
     * @throws IOException on connection or stream failure
     */
    @Override
    @Transactional
    @CacheEvict(value = {"fileMetadata", "blobNames", "stableFiles"}, allEntries = true)
    public Boolean uploadDocument(FilePath fileInfo, MultipartFile input, String loggedInUser) throws IOException {
        return uploadAndSaveFile(fileInfo, input, Common.FILE_STATUS_UPLOADED, loggedInUser);
    }

    /**
     * Performs a batch upload of multiple documents, matching lists of FilePaths and files.
     * Collects success status flags individually.
     *
     * @param fileInfos list of FilePath structures for each file
     * @param files array of MultipartFile objects matching the fileInfos
     * @param loggedInUser username of the uploader
     * @return list of boolean status indicators for each file's upload result
     * @throws IOException on validation error or general stream failures
     */
    @Override
    @Transactional
    @CacheEvict(value = {"fileMetadata", "blobNames", "stableFiles"}, allEntries = true)
    public List<Boolean> uploadMultipleDocuments(List<FilePath> fileInfos, MultipartFile[] files, String loggedInUser) throws IOException {
        if (fileInfos == null || files == null || fileInfos.size() != files.length) {
            throw new IllegalArgumentException("Files and file info counts must match");
        }

        List<Boolean> results = new java.util.ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            try {
                boolean success = uploadAndSaveFile(fileInfos.get(i), files[i], Common.FILE_STATUS_UPLOADED, loggedInUser);
                results.add(success);
            } catch (Exception e) {
                log.error("Failed to upload file at index " + i + ": " + files[i].getOriginalFilename(), e);
                results.add(false);
            }
        }
        return results;
    }

    // =========================================================================
    // Public API — Download
    // =========================================================================

    /**
     * Downloads a file from cloud storage directly by path — skips the DB lookup.
     * Use this only when the caller is certain the blob exists (e.g. internal pipeline use).
     *
     * @param fileInfo a {@link FilePath} containing the hierarchical path segments
     * @return a {@link ByteArrayOutputStream} containing the file contents, or {@code null} on error
     */
    @Override
    public ByteArrayOutputStream downloadFileDirectly(FilePath fileInfo) {
        FilePath filePath = normalizeFilePath(fileInfo);
        String blobName = filePath.toFullPath();
        log.info("Direct download — blob path: {}", blobName);
        return downloadByBlobName(blobName);
    }

    /**
     * Downloads a file and structures it into a DownloadedFile by its full physical string path.
     *
     * @param path full slash-separated path string in storage
     * @return DownloadedFile containing filename, headers, and content bytes
     */
    @Override
    public DownloadedFile getDownloadedFileByPath(String path) {
        FilePath filePath = FilePath.of(splitBlobPath(path));
        ByteArrayOutputStream output = downloadFileDirectly(filePath);
        return DownloadedFile.builder()
                .filename(filePath.getFileName())
                .contentDisposition(attachment(filePath.getFileName()))
                .content(output == null ? null : output.toByteArray())
                .build();
    }

    /**
     * Downloads a file from Azure Blob Storage using the canonical {@code blob_name} stored in the DB.
     * <p>
     * Flow:
     * <ol>
     *   <li>Look up {@link FileMetadata} from {@code file_metadata} table by JSONB filepath match.</li>
     *   <li>Read the stored {@code blob_name} — this is the exact Azure blob key used at upload time.</li>
     *   <li>Download the blob bytes from Azure using that key.</li>
     * </ol>
     *
     * @param fileInfo a {@link FilePath} whose segments identify the file in the DB
     * @return a {@link ByteArrayOutputStream} containing the file contents,
     *         or {@code null} if no DB record exists for the given path
     * @throws IOException if an I/O error occurs during the download
     */
    @Override
    public ByteArrayOutputStream downloadFile(FilePath fileInfo) throws IOException {
        FilePath filePath = normalizeFilePath(fileInfo);
        FileMetadata metadata = findByFilePath(filePath);
        if (metadata == null) {
            log.warn("No metadata found in DB for path: {} — returning null", filePath.toFullPath());
            return null;
        }
        // Use the canonical blob_name persisted at upload time — never recompute it.
        String blobName = metadata.getBlobName();
        log.info("Fetching blob '{}' from Azure (DB record id={})", blobName, metadata.getId());
        return downloadByBlobName(blobName);
    }

    /**
     * Downloads a file and maps it into a structured DownloadedFile container using hierarchical FilePath info.
     *
     * @param fileInfo structural FilePath identifying the document
     * @return DownloadedFile metadata and byte payload
     * @throws IOException on database metadata miss or stream errors
     */
    @Override
    public DownloadedFile getDownloadedFile(FilePath fileInfo) throws IOException {
        FilePath filePath = normalizeFilePath(fileInfo);
        ByteArrayOutputStream output = downloadFile(filePath);
        return DownloadedFile.builder()
                .filename(filePath.getFileName())
                .contentDisposition(attachment(filePath.getFileName()))
                .content(output == null ? null : output.toByteArray())
                .build();
    }

    /**
     * Downloads a file from Azure Blob Storage using a known document ID.
     * Looks up the {@code blob_name} from the DB by {@code filePath[1]} (document-ID segment),
     * then fetches the blob bytes from Azure.
     *
     * @param documentId the UUID stored in filePath[1] at upload time
     * @return a {@link ByteArrayOutputStream} containing the file contents, or {@code null}
     */
    @Override
    public ByteArrayOutputStream downloadFileByDocumentId(String documentId) {
        String blobName = getBlobNameByDocumentId(documentId);
        log.info("Fetching blob '{}' by documentId '{}'", blobName, documentId);
        return downloadByBlobName(blobName);
    }

    /**
     * Downloads a file payload by its unique document UUID, packaging it as a DownloadedFile response.
     *
     * @param documentId the document's unique UUID string
     * @return DownloadedFile descriptor and content bytes
     */
    @Override
    public DownloadedFile getDownloadedFileByDocumentId(String documentId) {
        ByteArrayOutputStream output = downloadFileByDocumentId(documentId);
        return DownloadedFile.builder()
                .filename(getFilename(documentId))
                .contentDisposition(attachment(getFilename(documentId)))
                .content(output == null ? null : output.toByteArray())
                .build();
    }

    /**
     * Downloads a document as a Resource for streaming in a Spring Controller.
     * Uses the documentId to look up the Azure blob key.
     *
     * @param documentId the UUID identifier
     * @return a {@link Resource} representing the file content
     */
    @Override
    public Resource downloadDocument(String documentId) {
        BlobClient blobClient = getBlobClientByDocumentId(documentId);
        log.info("Downloading document with ID: {}", documentId);
        return new InputStreamResource(blobClient.openInputStream());
    }

    /**
     * Retrieves both the filename and the file content (as a Resource) for a document.
     * Useful for building download responses with proper filenames.
     *
     * @param documentId the UUID identifier
     * @return a {@link DownloadedDocument} containing filename and resource
     */
    @Override
    public DownloadedDocument getDownloadedDocument(String documentId) {
        return DownloadedDocument.builder()
                .filename(getFilename(documentId))
                .contentDisposition(attachment(getFilename(documentId)))
                .resource(downloadDocument(documentId))
                .build();
    }

    // =========================================================================
    // Public API — Metadata helpers
    // =========================================================================

    @Override
    @Cacheable(value = "blobNames", key = "#documentId")
    public String getBlobNameByDocumentId(String documentId) {
        return fileMetadataRepository.findByDocumentId(documentId)
                .map(FileMetadata::getBlobName)
                .orElseThrow(() -> new RuntimeException("Document not found with ID: " + documentId));
    }

    /**
     * Resolves the original filename for a document using its documentId.
     *
     * @param documentId the UUID identifier
     * @return the filename string, or "document" if not found
     */
    @Override
    public String getFilename(String documentId) {
        return fileMetadataRepository.findByDocumentId(documentId)
                .map(metadata -> metadata.getFilepath().getFileName())
                .orElse("document");
    }

    /**
     * Generates temporary SAS (Shared Access Signature) links for viewing or downloading a document.
     * SAS links provide secure, time-limited access to private Azure blobs.
     *
     * @param documentId the UUID identifier
     * @return a {@link DocumentLinkResponse} containing the SAS URLs
     */
    @Override
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

    /**
     * Retrieves metadata for all files that have reached a 'stable' indexing status.
     * Supports folder-level access restrictions.
     *
     * @param restrictedFolders list of folder IDs the user is ALLOWED to access
     * @return list of files indexed and ready for search
     */
    @Override
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

    /**
     * Updates the status (e.g., UPLOADED -> INDEXED) of a file record in the database.
     *
     * @param documentId the UUID identifier
     * @param status     the new status
     */
    @Override
    @Transactional
    @CacheEvict(value = {"fileMetadata", "stableFiles"}, allEntries = true)
    public void updateFileStatus(String documentId, FileMetadata.FileStatus status) {
        fileMetadataRepository.findByDocumentId(documentId).ifPresent(metadata -> {
            metadata.setStatus(status);
            fileMetadataRepository.save(metadata);
            log.info("Updated status for document {} to {}", documentId, status);
        });
    }

    /**
     * Retrieves all file metadata records associated with a specific folder ID.
     *
     * @param folderId the folder identifier
     * @return list of file metadata
     */
    @Override
    @Cacheable(value = "fileMetadata", key = "#folderId")
    public List<FileMetadata> getFilesByFolder(String folderId) {
        return fileMetadataRepository.findByFolderId(folderId);
    }

    /**
     * Synchronizes all blobs from the Azure storage container into the database.
     * This method lists every blob in the container, parses its path, and ensures
     * that both the flat metadata table and the hierarchical document tree are updated.
     */
    @Override
    @Transactional
    @CacheEvict(value = {"fileMetadata", "blobNames", "stableFiles"}, allEntries = true)
    public void syncAllMetadataFromAzure() {
        log.info("Starting synchronization of all blobs from container '{}'", cloudProperty.getContainerName());

        containerClient.listBlobs().forEach(blobItem -> {
            try {
                String blobName = blobItem.getName();
                // Skip directories or empty names
                if (blobName == null || blobName.isBlank() || blobName.endsWith("/")) {
                    return;
                }

                // Split the blob name (e.g., "folder/sub/file.pdf") into segments
                List<String> segments = Arrays.asList(blobName.split("/"));
                FilePath filePath = FilePath.of(segments);

                // 1. Sync the flat metadata table (file_metadata) which is used for rapid searches
                syncFileMetadata(blobItem, filePath);

                // 2. Sync the hierarchical tree (demo.documents) which is used for folder browsing
                syncdemoDocuments(segments);

            } catch (Exception e) {
                log.error("Failed to sync metadata for blob: {}", blobItem.getName(), e);
            }
        });

        log.info("Completed synchronization of all blobs from container '{}'", cloudProperty.getContainerName());
    }

    /**
     * Updates or creates a record in the file_metadata table for a specific blob.
     * 
     * @param blobItem the blob data from Azure
     * @param filePath the structured file path object
     */
    private void syncFileMetadata(BlobItem blobItem, FilePath filePath) throws JsonProcessingException {
        String blobName = blobItem.getName();
        String accountUrl = cloudProperty.getAccountUrl();
        if (!accountUrl.endsWith("/")) {
            accountUrl += "/";
        }
        // Construct the full public/access URL for the blob
        String blobUrl = accountUrl + cloudProperty.getContainerName() + "/" + blobName;

        // Fetch properties like size and content type
        long sizeInBytes = blobItem.getProperties() != null ? blobItem.getProperties().getContentLength() : 0L;
        Double fileSizeInMb = toMb(sizeInBytes);
        String contentType = blobItem.getProperties() != null ? blobItem.getProperties().getContentType() : "application/octet-stream";

        FileMetadata existingFile = findByFilePath(filePath);
        if (existingFile == null) {
            // Create new record if it doesn't exist
            log.info("Sync: Creating new metadata record for blob: {}", blobName);
            fileMetadataRepository.save(FileMetadata.builder()
                    .filepath(filePath)
                    .createdBy(Common.SYSTEM_SYNC_USER)
                    .azureBlobUrl(blobUrl)
                    .blobName(blobName)
                    .fileSizeInMb(BigDecimal.valueOf(fileSizeInMb).setScale(2, RoundingMode.HALF_UP))
                    .contentType(contentType)
                    .status(FileMetadata.FileStatus.UPLOADED)
                    .build());
        } else {
            // Update existing record with the latest Azure properties
            log.debug("Sync: Updating existing metadata record for blob: {}", blobName);
            existingFile.setAzureBlobUrl(blobUrl);
            existingFile.setBlobName(blobName);
            existingFile.setFileSizeInMb(BigDecimal.valueOf(fileSizeInMb).setScale(2, RoundingMode.HALF_UP));
            existingFile.setContentType(contentType);
            fileMetadataRepository.save(existingFile);
        }
    }

    /**
     * Ensures that every segment of a file path exists in the hierarchical 'demo.documents' table.
     * For example, for "A/B/C.pdf", it ensures folder 'A' exists, folder 'B' exists under 'A', 
     * and file 'C.pdf' exists under 'B'.
     * 
     * @param segments the list of path segments
     */
    private void syncdemoDocuments(List<String> segments) {
        demoDocument parent = null;
        for (int i = 0; i < segments.size(); i++) {
            String segment = segments.get(i);
            boolean isFile = (i == segments.size() - 1);
            
            final demoDocument currentParent = parent;
            // Find existing segment at this level or create a new one
            parent = demoDocumentRepository.findByNameAndParentAndFile(segment, currentParent, isFile)
                    .orElseGet(() -> {
                        log.info("Sync: Creating demo document entry: {} (file={})", segment, isFile);
                        return demoDocumentRepository.save(demoDocument.builder()
                                .name(segment)
                                .file(isFile)
                                .parent(currentParent)
                                .build());
                    });
        }
    }

    // =========================================================================
    // Mid-layer delegation — public, so other internal services may reuse them
    // =========================================================================

    /**
     * Uploads a file to the specified cloud container.
     *
     * @param inputStream        {@link InputStream} of the file to be uploaded
     * @param filePath           {@link FilePath} defining the blob path
     * @param azureContainerName name of the Azure container
     * @return {@code true} if the file was successfully uploaded, {@code false} otherwise
     * @throws IOException if there is an error while processing the stream
     */
    public boolean fileUploader(InputStream inputStream, FilePath filePath, String azureContainerName) throws IOException {
        return azureFileUploader(inputStream, filePath, azureContainerName);
    }

    /**
     * Downloads a file from the specified cloud container by computed path.
     * Kept for internal/direct-path access; production downloads should prefer
     * {@link #downloadByBlobName(String)} which uses the DB-persisted canonical name.
     *
     * @param filePath           {@link FilePath} defining the blob path
     * @param azureContainerName name of the Azure container
     * @return a {@link ByteArrayOutputStream} containing the file content, or {@code null} on error
     */
    public ByteArrayOutputStream fileDownloader(FilePath filePath, String azureContainerName) {
        return downloadByBlobName(filePath.toFullPath());
    }

    // =========================================================================
    // Private — Azure low-level operations
    // =========================================================================

    /**
     * Uploads a file to Azure Blob Storage.
     * Creates the container if it does not exist.
     * Deletes an existing blob with the same path before uploading.
     *
     * @param inputStream        the input stream of the file to be uploaded
     * @param filePath           the hierarchical {@link FilePath}
     * @param azureContainerName the target Azure container name
     * @return {@code true} if successfully uploaded, {@code false} on any Azure error
     * @throws IOException if there is an error closing the input stream
     */
    private boolean azureFileUploader(InputStream inputStream, FilePath filePath,
                                      String azureContainerName) throws IOException {
        BlobContainerClient blobContainerClient = getBlobContainerClient(azureContainerName);

        if (!blobContainerClient.exists()) {
            blobContainerClient.create();
        }

        try (InputStream bufferedIn = new BufferedInputStream(inputStream)) {
            BlobClient blobClient = blobContainerClient.getBlobClient(getBlobPath(filePath));

            if (Boolean.TRUE.equals(blobClient.exists())) {
                blobClient.delete();
            }

            log.info("Uploading a new object to Azure storage");
            blobClient.upload(bufferedIn, true);
            log.info("File uploaded successfully to Azure storage");

            return true;
        } catch (ClientAuthenticationException e) {
            log.error(Common.AZURE_CLIENT_AUTHENTICATION_ERROR, e);
        } catch (AzureException e) {
            log.error(Common.AZURE_STORAGE_ERROR, e);
        } catch (Exception e) {
            log.error("Failed to upload file", e);
        }

        return false;
    }

    /**
     * Core Azure download operation.
     * Downloads a blob by its canonical stored name — the exact key used at upload time.
     * <p>
     * This is the single point of truth for all Azure download operations:
     * <ul>
     *   <li>{@link #downloadFile} — reads blobName from DB, then calls here.</li>
     *   <li>{@link #downloadFileDirectly} — computes path from FilePath, then calls here.</li>
     *   <li>{@link #downloadByDocumentId} — looks up blobName by documentId, then calls here.</li>
     * </ul>
     *
     * @param blobName the exact blob key in Azure Storage (e.g. "folder/uuid/file.pdf")
     * @return a {@link ByteArrayOutputStream} containing the file data, or {@code null} on error
     */
    private ByteArrayOutputStream downloadByBlobName(String blobName) {
        BlobContainerClient containerClient = getBlobContainerClient(cloudProperty.getContainerName());
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            log.info("Downloading blob '{}' from Azure storage container '{}'",
                    blobName, cloudProperty.getContainerName());
            BlobClient blobClient = containerClient.getBlobClient(blobName);
            blobClient.downloadStream(outputStream);
            log.info("Blob '{}' downloaded successfully ({} bytes)", blobName, outputStream.size());
            return outputStream;
        } catch (ClientAuthenticationException e) {
            log.error(Common.AZURE_CLIENT_AUTHENTICATION_ERROR, e);
        } catch (AzureException e) {
            log.error(Common.AZURE_STORAGE_ERROR, e);
        } catch (Exception e) {
            log.error("Failed to download blob '{}'", blobName, e);
        }
        return null;
    }

    // =========================================================================
    // Private — Business logic helpers
    // =========================================================================

    /**
     * Core upload-and-persist logic used by both {@link #uploadDocument} and {@link #uploadFile}.
     * Matches the company pattern exactly:
     *   1. Upload blob to Azure (via {@link #fileUploader})
     *   2. If not uploaded → return false
     *   3. Check DB for existing record → save or update
     */
    private boolean uploadAndSaveFile(FilePath fileInfo, MultipartFile input,
                                      String status, String username) throws IOException {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("File is empty");
        }

        FilePath filePath = normalizeFilePath(fileInfo);
        Double fileSizeInMb = toMb(input.getSize());
        FileMetadata.FileStatus fileStatus = parseStatus(status);

        log.info("Created file upload request for path: {}", filePath.toFullPath());

        try (InputStream inputStream = input.getInputStream()) {
            boolean fileUploaded = fileUploader(inputStream, filePath, cloudProperty.getContainerName());
            log.info("File has been uploaded successfully using the cloud process {}", fileUploaded);

            if (!fileUploaded) {
                return false;
            }

            String blobName = getBlobPath(filePath);
            String blobUrl = cloudProperty.getAccountUrl() + cloudProperty.getContainerName() + "/" + blobName;

            FileMetadata existingFile = findByFilePath(filePath);
            User userObj = userRepository.findByUserNameOrEmail(username, username).orElse(null);

            if (existingFile == null) {
                fileMetadataRepository.save(FileMetadata.builder()
                        .filepath(filePath)
                        .createdBy(username)
                        .user(userObj)
                        .azureBlobUrl(blobUrl)
                        .blobName(blobName)
                        .fileSizeInMb(BigDecimal.valueOf(fileSizeInMb).setScale(2, RoundingMode.HALF_UP))
                        .contentType(input.getContentType())
                        .status(fileStatus)
                        .build());
            } else {
                existingFile.setFilepath(filePath);
                existingFile.setAzureBlobUrl(blobUrl);
                existingFile.setBlobName(blobName);
                existingFile.setFileSizeInMb(BigDecimal.valueOf(fileSizeInMb).setScale(2, RoundingMode.HALF_UP));
                existingFile.setContentType(input.getContentType());
                existingFile.setStatus(fileStatus);
                existingFile.setCreatedBy(username);
                existingFile.setUser(userObj);
                fileMetadataRepository.save(existingFile);
            }

            log.info("Updated the file record in metadata table");

            // Dynamically register and sync uploaded folder path segments inside PostgreSQL documents hierarchy tree
            try {
                syncdemoDocuments(filePath.getFilePath());
                log.info("Successfully synchronized documents folder tree segments in DB: {}", filePath.getFilePath());
            } catch (Exception e) {
                log.error("Failed to dynamically synchronize documents folder tree segments in DB: {}", filePath.getFilePath(), e);
            }

            // Notify the Azure Indexing Service to immediately queue this blob for indexing.
            // This call is non-blocking: any failure is swallowed inside the callback service.
            String callbackFileName = filePath.getFileName();
            indexingCallbackService.triggerIndexing(blobUrl, blobName, callbackFileName);

        } catch (Exception e) {
            log.error("Failed to upload file", e);
            throw e;
        }

        return true;
    }

    // =========================================================================
    // Private — Utility
    // =========================================================================

    /** Resolves the blob path string from a {@link FilePath} after normalising it. */
    private String getBlobPath(FilePath filePath) {
        String blobPath = normalizeFilePath(filePath).toFullPath();
        log.info("Resolved blob path: {}", blobPath);
        return blobPath;
    }

    /**
     * Normalizes a FilePath object to ensure it is not null and is valid.
     * Validates that at least one path segment exists.
     * 
     * @param filePath the path to normalize
     * @return a non-null, valid FilePath
     */
    private FilePath normalizeFilePath(FilePath filePath) {
        FilePath normalized = filePath == null ? FilePath.of(List.of()) : filePath.normalized();
        if (!normalized.isValid()) {
            throw new IllegalArgumentException("filePath JSON must contain at least one path segment");
        }
        return normalized;
    }

    /**
     * Finds file metadata in the DB using a JSONB filepath match.
     *
     * @param filePath the path to search for
     * @return the metadata if found, null otherwise
     */
    private FileMetadata findByFilePath(FilePath filePath) throws JsonProcessingException {
        return fileMetadataRepository.findByFilePathJson(objectMapper.writeValueAsString(filePath)).orElse(null);
    }

    /**
     * Splits a raw blob path string into a list of segments.
     * Useful for converting "folder/file.pdf" back into a structured path.
     * 
     * @param path the raw path string
     * @return list of non-empty path segments
     */
    private List<String> splitBlobPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        return Arrays.stream(path.split("/"))
                .filter(segment -> segment != null && !segment.isBlank())
                .toList();
    }

    /**
     * Parses a status string into a FileStatus enum, defaulting to UPLOADED if invalid or blank.
     *
     * @param status the raw string status
     * @return the parsed FileMetadata.FileStatus enum
     */
    private FileMetadata.FileStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return FileMetadata.FileStatus.UPLOADED;
        }
        try {
            return FileMetadata.FileStatus.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.warn("Unknown file status '{}'; using UPLOADED", status);
            return FileMetadata.FileStatus.UPLOADED;
        }
    }

    /**
     * Converts a size in bytes to megabytes (MB).
     * 
     * @param bytes the size in bytes
     * @return the size in megabytes
     */
    private Double toMb(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    /**
     * Obtains the Azure BlobContainerClient for the specified container name.
     *
     * @param azureContainerName the storage container name
     * @return the BlobContainerClient instance
     */
    private BlobContainerClient getBlobContainerClient(String azureContainerName) {
        return blobServiceClient.getBlobContainerClient(azureContainerName);
    }

    /**
     * Resolves the Azure BlobClient corresponding to a specific document ID.
     *
     * @param documentId the document's unique UUID string
     * @return the BlobClient matching the document
     */
    private BlobClient getBlobClientByDocumentId(String documentId) {
        return getBlobContainerClient(cloudProperty.getContainerName())
                .getBlobClient(getBlobNameByDocumentId(documentId));
    }

    /**
     * Formats a standard Content-Disposition header value with a filename.
     * 
     * @param filename the display name of the file
     * @return the formatted header value (e.g., "attachment; filename=\"doc.pdf\"")
     */
    private String attachment(String filename) {
        return Common.CONTENT_DISPOSITION_FILENAME_FORMAT.formatted(
                Common.CONTENT_DISPOSITION_ATTACHMENT,
                filename
        );
    }

    /**
     * Creates a Shared Access Signature (SAS) link for a blob.
     * 
     * @param blobClient the target blob
     * @param contentDisposition how the browser should handle the file (inline vs attachment)
     * @return the full URL with SAS token
     */
    private String createSasLink(BlobClient blobClient, String contentDisposition) {
        BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusSeconds(cloudProperty.getBlobSasExpiresInSeconds());
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(expiresAt, permission)
                .setContentDisposition(contentDisposition);
        return blobClient.getBlobUrl() + "?" + blobClient.generateSas(values);
    }
}
