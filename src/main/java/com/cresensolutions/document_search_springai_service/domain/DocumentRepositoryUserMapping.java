package com.cresensolutions.document_search_springai_service.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "document_repository_user_mapping", schema = "prestage")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRepositoryUserMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_name", nullable = false, length = 200)
    private String userName;

    @Column(name = "folders_access")
    private Integer foldersAccess;

    @Column(name = "created_at")
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at")
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
