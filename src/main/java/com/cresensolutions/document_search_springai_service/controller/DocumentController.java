package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.dto.DownloadedFile;
import com.cresensolutions.document_search_springai_service.dto.DownloadedDocument;
import com.cresensolutions.document_search_springai_service.domain.FilePath;
import com.cresensolutions.document_search_springai_service.service.DocumentService;
import com.cresensolutions.document_search_springai_service.dto.DocumentLinkResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;


/**
 * REST Controller for document management operations.
 * Handles file upload and download operations using Azure Blob Storage.
 * Provides endpoints for uploading documents and retrieving them.
 */
@RestController
@RequestMapping("/api/documents")
@RequiredArgsConstructor
@Tag(name = "Document Management", description = "Upload and download files from Azure Blob Storage")
@CrossOrigin(origins = "*")
@Slf4j
public class DocumentController {

    private final DocumentService documentService;

    /**
     * Uploads a document to Azure Blob Storage using a hierarchical JSON path.
     *
     * @param fileInfo the {@link FilePath} JSON defining the blob path segments
     * @param file     the multipart file to upload
     * @param username the username of the uploader
     * @return ResponseEntity with success status
     */
    @PostMapping(value = "/upload")
    @Operation(summary = "Upload a file", description = "Upload a file to Azure Blob Storage using a hierarchical JSON path")
    public ResponseEntity<?> uploadDocument(
            @RequestPart("fileInfo") FilePath fileInfo,
            @RequestPart("file") MultipartFile file,
            @RequestParam("username") String username) {
        try {
            boolean success = documentService.uploadDocument(fileInfo, file, username);
            return success ? ResponseEntity.ok("File uploaded successfully.")
                    : ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to upload file");
        } catch (Exception e) {
            log.error("Failed to upload document", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to upload file: " + e.getMessage());
        }
    }



    /**
     * Downloads a document from Azure Blob Storage.
     * Returns the file as a downloadable resource with appropriate headers.
     *
     * @param documentId the unique identifier of the document to download
     * @return ResponseEntity containing the file resource or error response
     */
    @GetMapping("/download/{documentId}")
    @Operation(summary = "Download a file", description = "Download a file from Azure Blob Storage")
    public ResponseEntity<Resource> downloadDocument(@PathVariable String documentId) {
        try {
            DownloadedDocument document = documentService.getDownloadedDocument(documentId);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, document.getContentDisposition())
                    .body(document.getResource());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Downloads a file identified by its structured JSON path.
     *
     * @param fileInfo the {@link FilePath} identifying the file
     * @return the file bytes
     */
    @PostMapping("/download-file")
    @Operation(summary = "Download a file by JSON path", description = "Download a file using the stored filePath JSON")
    public ResponseEntity<byte[]> downloadFile(@RequestBody FilePath fileInfo) {
        try {
            DownloadedFile downloadedFile = documentService.getDownloadedFile(fileInfo);
            if (!downloadedFile.hasContent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, downloadedFile.getContentDisposition())
                    .body(downloadedFile.getContent());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Downloads a document using the documentId returned at upload time.
     * <p>
     * Flow: DB lookup by documentId → reads stored blob_name → downloads from Azure
     * Storage.
     *
     * @param documentId the UUID returned by {@code /upload} or
     *                   {@code /upload-file}
     */
    @GetMapping("/download-by-document-id/{documentId}")
    @Operation(summary = "Download file by document ID", description = "Fetch blob_name from DB using documentId, then stream the file from Azure Storage")
    public ResponseEntity<byte[]> downloadByDocumentId(@PathVariable String documentId) {
        try {
            DownloadedFile downloadedFile = documentService.getDownloadedFileByDocumentId(documentId);
            if (!downloadedFile.hasContent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, downloadedFile.getContentDisposition())
                    .body(downloadedFile.getContent());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Downloads a file directly using its blob path (e.g.,
     * folder/uuid/filename.pdf).
     * This bypasses the DB metadata lookup.
     *
     * @param path the full Azure blob path
     * @return the file bytes
     */
    @GetMapping("/download-by-path")
    @Operation(summary = "Download a file by blob path", description = "Download a file using the full blob path e.g. folder/uuid/filename.txt")
    public ResponseEntity<byte[]> downloadByPath(@RequestParam("path") String path) {
        try {
            DownloadedFile downloadedFile = documentService.getDownloadedFileByPath(path);
            if (!downloadedFile.hasContent()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, downloadedFile.getContentDisposition())
                    .body(downloadedFile.getContent());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(("Error: " + e.getMessage()).getBytes());
        }
    }

    /**
     * Retrieves temporary SAS links for viewing and downloading a document in a
     * browser.
     * 
     * @param documentId the UUID identifier
     * @return the SAS link response
     */
    @GetMapping("/{documentId}/links")
    @Operation(summary = "Create document links", description = "Create temporary Azure Blob SAS links for download and browser viewing")
    public ResponseEntity<DocumentLinkResponse> getDocumentLinks(@PathVariable String documentId) {
        try {
            return ResponseEntity.ok(documentService.getDocumentLinks(documentId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    /**
     * Simple health check endpoint for monitoring.
     */
    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Document Service is running");
    }

    /**
     * Triggers a manual synchronization of Azure blobs to the database.
     * This will populate both 'file_metadata' and the hierarchical
     * 'prestage.documents' tree.
     */
    @PostMapping("/sync-from-azure")
    @Operation(summary = "Sync all blobs from Azure", description = "Scan Azure container and ensure all blobs have metadata records in DB (both flat metadata and hierarchical tree)")
    public ResponseEntity<String> syncFromAzure() {
        try {
            log.info("REST request to sync all metadata from Azure");
            documentService.syncAllMetadataFromAzure();
            return ResponseEntity.ok("Synchronization triggered successfully");
        } catch (Exception e) {
            log.error("Failed to trigger synchronization", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to trigger synchronization: " + e.getMessage());
        }
    }

}
