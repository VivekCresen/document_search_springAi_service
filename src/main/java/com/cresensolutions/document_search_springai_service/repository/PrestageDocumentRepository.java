package com.cresensolutions.document_search_springai_service.repository;

import com.cresensolutions.document_search_springai_service.domain.PrestageDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PrestageDocumentRepository extends JpaRepository<PrestageDocument, Integer> {

    @Query("SELECT d.id FROM PrestageDocument d WHERE d.file = false")
    List<Integer> findAllFolderIds();

    @Query("SELECT COUNT(d) FROM PrestageDocument d WHERE d.file = false")
    long countFolders();
}
