package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DocumentLinkResponse;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Service interface for handling document operations with Azure Blob Storage.
 * Provides functionality for uploading, downloading, and managing documents.
 * Integrates with database repositories for metadata and indexing information.
 * Uses caching for performance optimization.
 */
public interface DocumentService {

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
    String uploadDocument(MultipartFile file, String folderId, String username) throws IOException;

    /**
     * Downloads a document from Azure Blob Storage.
     * Returns the file content as a Spring Resource for streaming.
     *
     * @param documentId the unique identifier of the document
     * @return Resource containing the file data
     */
    Resource downloadDocument(String documentId);

    /**
     * Retrieves the blob name associated with a document ID.
     * Uses caching to improve performance for repeated lookups.
     *
     * @param documentId the document identifier
     * @return the blob name in Azure Storage
     */
    String getBlobNameByDocumentId(String documentId);

    /**
     * Retrieves the filename associated with a document ID.
     *
     * @param documentId the document identifier
     * @return the filename
     */
    String getFilename(String documentId);

    /**
     * Retrieves download and view links for a document.
     *
     * @param documentId the document identifier
     * @return DocumentLinkResponse containing links
     */
    DocumentLinkResponse getDocumentLinks(String documentId);

    /**
     * Retrieves accessible stable files based on restricted folders.
     *
     * @param restrictedFolders list of restricted folder IDs
     * @return list of accessible files
     */
    List<FileInIndex> getAccessibleStableFiles(List<String> restrictedFolders);

    /**
     * Updates the status of a file.
     *
     * @param documentId the document identifier
     * @param status the new status
     */
    void updateFileStatus(String documentId, FileMetadata.FileStatus status);

    /**
     * Retrieves files by folder ID.
     *
     * @param folderId the folder identifier
     * @return list of file metadata
     */
    List<FileMetadata> getFilesByFolder(String folderId);
}