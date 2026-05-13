package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.DbSearchSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DbSearchSourceRepository extends JpaRepository<DbSearchSource, Long> {

    List<DbSearchSource> findByActiveTrue();

    @Query("""
            SELECT s FROM DbSearchSource s
            JOIN FETCH DbSearchSchemaVersion v ON v.source = s AND v.active = true
            WHERE s.active = true
            ORDER BY s.viewName
            """)
    List<DbSearchSource> findActiveWithLatestSchema();
}
