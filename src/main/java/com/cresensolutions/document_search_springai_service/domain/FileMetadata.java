package com.cresensolutions.document_search_springai_service.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * JPA Entity representing file metadata in the database.
 * Stores information about uploaded files including Azure Blob Storage details,
 * file properties, and processing status.
 */
@Entity
@Table(name = "file_metadata", schema = "prestage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileMetadata {

    /** Primary key for the file metadata record */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** File path information stored as JSONB for flexible structure */
    @Type(JsonBinaryType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private FilePath filepath;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Username of the user who uploaded the file */
    @Column(name = "created_by", nullable = false)
    private String createdBy;

    /** Timestamp when the file was uploaded */
    @Column(name = "create_date", nullable = false)
    @Builder.Default
    private OffsetDateTime createDate = OffsetDateTime.now();

    /** Azure Blob Storage URL for the file */
    @Column(name = "azure_blob_url", nullable = false)
    private String azureBlobUrl;

    /** Unique blob name in Azure Storage */
    @Column(name = "blob_name", nullable = false)
    private String blobName;

    /** File size in megabytes for storage tracking */
    @Column(name = "file_size_in_mb")
    private BigDecimal fileSizeInMb;

    /** MIME content type of the file */
    @Column(name = "content_type")
    private String contentType;

    /** Current processing status of the file */
    @Column(nullable = false)
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private FileStatus status = FileStatus.UPLOADED;

    public enum FileStatus {
        UPLOADED,
        INDEXING,
        INDEXED,
        FAILED,
        DELETED
    }
}
