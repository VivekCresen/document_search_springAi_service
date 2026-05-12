package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.DocumentRepositoryUserMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DocumentRepositoryUserMappingRepository extends JpaRepository<DocumentRepositoryUserMapping, Long> {

    List<DocumentRepositoryUserMapping> findAllByUserName(String userName);

    boolean existsByUserName(String userName);

    @Query("""
            SELECT DISTINCT m.foldersAccess
            FROM DocumentRepositoryUserMapping m
            WHERE m.userName = :userName
              AND m.foldersAccess IS NOT NULL
            """)
    List<Integer> findRestrictedFolderIds(@Param("userName") String userName);

    @Modifying
    void deleteByUserName(String userName);
}
