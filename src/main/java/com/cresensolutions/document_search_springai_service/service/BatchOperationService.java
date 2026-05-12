package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.FileMetadata;

import java.util.List;
import java.util.Map;

/**
 * Contract for file metadata operations that work on multiple documents at once.
 */
public interface BatchOperationService {

    /**
     * Updates all matching documents to the same processing status.
     */
    void batchUpdateStatus(List<String> documentIds, FileMetadata.FileStatus status);

    /**
     * Looks up metadata by document id and returns it keyed by the stored full file path.
     */
    Map<String, FileMetadata> getFilesByDocumentIds(List<String> documentIds);

    /**
     * Returns all metadata records that belong to any of the supplied folder ids.
     */
    List<FileMetadata> getFilesByFolderIds(List<String> folderIds);
}
