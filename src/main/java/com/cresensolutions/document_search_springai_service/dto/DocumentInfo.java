package com.cresensolutions.document_search_springai_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentInfo {
    private String documentId;
    private String blobName;
    private String originalFilename;
    private String contentType;
    private Long size;
    private String url;
    private OffsetDateTime uploadedAt;
    private String folderId;
    private String folderName;
    private String createdBy;
    private String status;
}
