package com.cresensolutions.document_search_springai_service.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Type;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Maps to prestage.db_search_schema_versions — stores the JSON schema for each view version.
 */
@Entity
@Table(name = "db_search_schema_versions", schema = "prestage")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DbSearchSchemaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_id", nullable = false)
    private DbSearchSource source;

    @Type(JsonBinaryType.class)
    @Column(name = "schema_json", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> schemaJson;

    @Column(name = "version", nullable = false)
    @Builder.Default
    private int version = 1;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
