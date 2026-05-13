package com.cresensolutions.document_search_springai_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Entity representing the hierarchical file/folder structure in the 'prestage.documents' table.
 * Used for folder-based browsing in the UI.
 */
@Entity
@Table(name = "documents", schema = "prestage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrestageDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    /** The name of the file or folder segment. */
    @Column(nullable = false, length = 512)
    private String name;

    /** Flag indicating if this entry is a file (true) or a folder (false). */
    @Column(name = "is_file", nullable = false)
    @Builder.Default
    private boolean file = false;

    /** Recursive relationship to the parent document/folder. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private PrestageDocument parent;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();
}
