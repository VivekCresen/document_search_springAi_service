package com.cresensolutions.document_search_springai_service.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Maps to prestage.db_search_sources — one row per DB view registered for NL-to-SQL.
 */
@Entity
@Table(name = "db_search_sources", schema = "prestage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbSearchSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "view_name", nullable = false, unique = true)
    private String viewName;

    @Column(name = "description")
    private String description;

    @Type(JsonBinaryType.class)
    @Column(name = "routing_metadata", columnDefinition = "jsonb")
    private Map<String, Object> routingMetadata;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
