package com.cresensolutions.document_search_springai_service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;


@Entity
@Table(name = "files_in_index", schema = "demo")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileInIndex {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blob_uri", nullable = false, unique = true)
    private String blobUri;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "folder_id")
    private demoDocument folder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "indexed_by")
    private User indexedBy;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "ingestion_inp";

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
