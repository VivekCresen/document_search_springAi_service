package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.service.DocumentService;
import com.cresensolutions.document_search_springai_service.dto.DocumentLinkResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
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
public class DocumentController {

    private final DocumentService documentService;

    /**
     * Uploads a document to Azure Blob Storage.
     * The file is stored with metadata including folder ID and username for access control.
     *
     * @param file the multipart file to upload
     * @param folderId the folder identifier for organization
     * @param username the username of the uploader for permission tracking
     * @return ResponseEntity with success message or error details
     */
    @PostMapping("/upload")
    @Operation(summary = "Upload a file", description = "Upload a file to Azure Blob Storage")
    public ResponseEntity<?> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("folderId") String folderId,
            @RequestParam("username") String username) {
        try {
            String documentId = documentService.uploadDocument(file, folderId, username);
            return ResponseEntity.ok("File uploaded successfully. Document ID: " + documentId);
        } catch (Exception e) {
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
            Resource resource = documentService.downloadDocument(documentId);
            String filename = documentService.getFilename(documentId);
            
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                    .body(resource);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/{documentId}/links")
    @Operation(summary = "Create document links", description = "Create temporary Azure Blob SAS links for download and browser viewing")
    public ResponseEntity<DocumentLinkResponse> getDocumentLinks(@PathVariable String documentId) {
        try {
            return ResponseEntity.ok(documentService.getDocumentLinks(documentId));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @GetMapping("/health")
    @Operation(summary = "Health check")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Document Service is running");
    }
}
