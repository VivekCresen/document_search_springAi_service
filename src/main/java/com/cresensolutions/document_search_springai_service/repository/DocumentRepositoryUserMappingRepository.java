package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.DocumentRepositoryUserMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentRepositoryUserMappingRepository extends JpaRepository<DocumentRepositoryUserMapping, Long> {

    List<DocumentRepositoryUserMapping> findAllByUserName(String userName);
    List<DocumentRepositoryUserMapping> findAllByUserId(UUID userId);

    boolean existsByUserName(String userName);
    boolean existsByUserId(UUID userId);

    @Query("""
            SELECT DISTINCT m.foldersAccess.id
            FROM DocumentRepositoryUserMapping m
            WHERE (m.userName = :userName OR m.user.id = :userId)
              AND m.foldersAccess IS NOT NULL
            """)
    List<Long> findRestrictedFolderIds(@Param("userName") String userName, @Param("userId") UUID userId);

    @Modifying
    void deleteByUserName(String userName);

    @Modifying
    void deleteByUserId(UUID userId);
}
