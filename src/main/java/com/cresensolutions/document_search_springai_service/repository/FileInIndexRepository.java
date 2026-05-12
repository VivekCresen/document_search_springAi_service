package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileInIndexRepository extends JpaRepository<FileInIndex, Long> {

    Optional<FileInIndex> findByBlobUri(String blobUri);

    List<FileInIndex> findByFolderId(Integer folderId);

    List<FileInIndex> findByStatus(String status);

    @Query("SELECT f FROM FileInIndex f WHERE f.status = 'stable'")
    List<FileInIndex> findAllStableFiles();

    @Query("SELECT f FROM FileInIndex f WHERE f.folderId = :folderId AND f.status = 'stable'")
    List<FileInIndex> findStableFilesByFolderId(@Param("folderId") Integer folderId);

    @Query("""
            SELECT f
            FROM FileInIndex f
            WHERE f.status = 'stable'
              AND (f.folderId IS NULL OR f.folderId NOT IN :restrictedFolders)
            """)
    List<FileInIndex> findAccessibleStableFiles(@Param("restrictedFolders") List<Integer> restrictedFolders);

    @Query("SELECT f.blobUri FROM FileInIndex f WHERE f.status <> 'stable'")
    List<String> findUnstableBlobUris();

    @Query("SELECT f FROM FileInIndex f WHERE f.folderId IN :folderIds AND f.status = 'stable'")
    List<FileInIndex> findStableFilesByFolderIds(@Param("folderIds") List<Integer> folderIds);

    @Query("SELECT COUNT(f) FROM FileInIndex f WHERE f.folderId = :folderId AND f.status = 'stable'")
    long countStableFilesByFolderId(@Param("folderId") Integer folderId);
}
