package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.DocumentLinkResponse;
import com.cresensolutions.document_search_springai_service.dto.DownloadedDocument;
import com.cresensolutions.document_search_springai_service.dto.DownloadedFile;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import com.cresensolutions.document_search_springai_service.domain.FilePath;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
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
     * Uploads a file using a custom hierarchical FilePath object.
     * This method is useful for bulk-upload or migration scripts where the path
     * structure is already defined.
     *
     * @param fileInfo     {@link FilePath} containing the hierarchical path segments
     * @param input        the file content as a MultipartFile
     * @param status       the initial status to set in the DB
     * @param loggedInUser the user performing the upload
     * @return true if both upload and DB persistence succeeded
     * @throws IOException if Azure upload fails
     */
    Boolean uploadFile(FilePath fileInfo, MultipartFile input, String status, String loggedInUser) throws IOException;

    Boolean uploadDocument(FilePath fileInfo, MultipartFile input, String loggedInUser) throws IOException;

    List<Boolean> uploadMultipleDocuments(List<FilePath> fileInfos, MultipartFile[] files, String loggedInUser) throws IOException;

    /**
     * Downloads a file content directly from Azure Storage using a FilePath.
     * Use this when you want to bypass the database lookup entirely.
     *
     * @param fileInfo the target path
     * @return the file bytes as a ByteArrayOutputStream
     */
    ByteArrayOutputStream downloadFileDirectly(FilePath fileInfo);

    DownloadedFile getDownloadedFileByPath(String path);

    /**
     * Downloads a file from Azure Blob Storage by document ID.
     * Reads the canonical blob_name from the DB, then fetches from Azure.
     *
     * @param documentId the UUID stored in filePath[1] at upload time
     * @return a {@link ByteArrayOutputStream} containing the file contents, or {@code null}
     */
    ByteArrayOutputStream downloadFileByDocumentId(String documentId);

    DownloadedFile getDownloadedFileByDocumentId(String documentId);

    /**
     * Downloads a file from Azure Blob Storage using the hierarchical FilePath.
     * Performs a DB lookup first to find the canonical blob name.
     *
     * @param fileInfo the target path
     * @return the file bytes as a ByteArrayOutputStream
     * @throws IOException if database or storage errors occur
     */
    ByteArrayOutputStream downloadFile(FilePath fileInfo) throws IOException;

    DownloadedFile getDownloadedFile(FilePath fileInfo) throws IOException;

    /**
     * Downloads a document from Azure Blob Storage.
     * Returns the file content as a Spring Resource for streaming.
     *
     * @param documentId the unique identifier of the document
     * @return Resource containing the file data
     */
    Resource downloadDocument(String documentId);

    /**
     * Downloads a document with the filename required by HTTP response headers.
     *
     * @param documentId the unique identifier of the document
     * @return downloaded document content and display filename
     */
    DownloadedDocument getDownloadedDocument(String documentId);

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

    /**
     * Synchronizes all blobs from the Azure storage container into the file_metadata table
     * and the prestage.documents hierarchical tree. This ensures that any files directly 
     * uploaded to Azure are properly indexed in the database.
     */
    void syncAllMetadataFromAzure();
}
