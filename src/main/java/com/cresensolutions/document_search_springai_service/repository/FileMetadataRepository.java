package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;


@Repository
public interface FileMetadataRepository extends JpaRepository<FileMetadata, Long> {

    @Query(value = "SELECT * FROM prestage.file_metadata WHERE filepath->'filePath'->>1 = :documentId",
            nativeQuery = true)
    Optional<FileMetadata> findByDocumentId(@Param("documentId") String documentId);

    @Query(value = "SELECT * FROM prestage.file_metadata WHERE filepath = CAST(:filePathJson AS jsonb)",
            nativeQuery = true)
    Optional<FileMetadata> findByFilePathJson(@Param("filePathJson") String filePathJson);

    @Query(value = "SELECT * FROM prestage.file_metadata WHERE filepath->'filePath'->>0 = :folderId",
            nativeQuery = true)
    List<FileMetadata> findByFolderId(@Param("folderId") String folderId);

    @Query("SELECT f FROM FileMetadata f WHERE f.createdBy = :username")
    List<FileMetadata> findByCreatedBy(@Param("username") String username);

    @Query("SELECT f FROM FileMetadata f WHERE f.status = :status")
    List<FileMetadata> findByStatus(@Param("status") FileMetadata.FileStatus status);

    @Query("SELECT f FROM FileMetadata f WHERE f.blobName = :blobName")
    Optional<FileMetadata> findByBlobName(@Param("blobName") String blobName);

    @Query(value = "SELECT * FROM prestage.file_metadata WHERE filepath->'filePath'->>0 = :folderId AND status = :status",
            nativeQuery = true)
    List<FileMetadata> findByFolderIdAndStatus(@Param("folderId") String folderId,
                                               @Param("status") String status);
}
