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

    List<FileInIndex> findByFolderId(Long folderId);

    List<FileInIndex> findByStatus(String status);

    @Query("SELECT f FROM FileInIndex f WHERE f.status = 'stable'")
    List<FileInIndex> findAllStableFiles();

    @Query("SELECT f FROM FileInIndex f WHERE f.folder.id = :folderId AND f.status = 'stable'")
    List<FileInIndex> findStableFilesByFolderId(@Param("folderId") Long folderId);

    @Query("""
            SELECT f
            FROM FileInIndex f
            WHERE f.status = 'stable'
              AND (f.folder IS NULL OR f.folder.id NOT IN :restrictedFolders)
            """)
    List<FileInIndex> findAccessibleStableFiles(@Param("restrictedFolders") List<Integer> restrictedFolderIds);

    @Query("SELECT f.blobUri FROM FileInIndex f WHERE f.status <> 'stable'")
    List<String> findUnstableBlobUris();

    @Query("SELECT f FROM FileInIndex f WHERE f.folder.id IN :folderIds AND f.status = 'stable'")
    List<FileInIndex> findStableFilesByFolderIds(@Param("folderIds") List<Long> folderIds);

    @Query("SELECT COUNT(f) FROM FileInIndex f WHERE f.folder.id = :folderId AND f.status = 'stable'")
    long countStableFilesByFolderId(@Param("folderId") Long folderId);
}
