package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.DbSearchSchemaVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DbSearchSchemaVersionRepository extends JpaRepository<DbSearchSchemaVersion, Long> {

    @Query("""
            SELECT v FROM DbSearchSchemaVersion v
            WHERE v.source.id = :sourceId AND v.active = true
            ORDER BY v.version DESC
            LIMIT 1
            """)
    Optional<DbSearchSchemaVersion> findLatestActiveBySourceId(@Param("sourceId") Long sourceId);
}
