package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.demoDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface demoDocumentRepository extends JpaRepository<demoDocument, Long> {

    @Query("SELECT d.id FROM demoDocument d WHERE d.file = false")
    List<Long> findAllFolderIds();

    @Query("SELECT COUNT(d) FROM demoDocument d WHERE d.file = false")
    long countFolders();

    /**
     * Finds a document entry by name, parent, and its type (file vs folder).
     * Used during synchronization to traverse or build the hierarchical tree.
     * 
     * @param name   the name of the file or folder
     * @param parent the parent document (null for root)
     * @param file   true if searching for a file, false for a folder
     * @return the document if found
     */
    Optional<demoDocument> findByNameAndParentAndFile(String name, demoDocument parent, boolean file);
}
