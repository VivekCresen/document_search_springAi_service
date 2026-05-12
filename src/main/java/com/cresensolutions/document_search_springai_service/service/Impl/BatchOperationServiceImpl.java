package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.domain.FileMetadata;
import com.cresensolutions.document_search_springai_service.repository.FileMetadataRepository;
import com.cresensolutions.document_search_springai_service.service.BatchOperationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Default batch metadata service backed by FileMetadataRepository.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchOperationServiceImpl implements BatchOperationService {

    private final FileMetadataRepository fileMetadataRepository;

    @Override
    @Transactional
    @CacheEvict(value = {"fileMetadata", "stableFiles"}, allEntries = true)
    public void batchUpdateStatus(List<String> documentIds, FileMetadata.FileStatus status) {
        // Ignore missing document ids so a partial batch can still update valid records.
        List<FileMetadata> files = documentIds.stream()
                .map(fileMetadataRepository::findByDocumentId)
                .flatMap(optionalFile -> optionalFile.map(Stream::of).orElseGet(Stream::empty))
                .peek(file -> file.setStatus(status))
                .collect(Collectors.toList());

        fileMetadataRepository.saveAll(files);
        log.info("Batch updated {} files to status {}", files.size(), status);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, FileMetadata> getFilesByDocumentIds(List<String> documentIds) {
        return documentIds.stream()
                .map(fileMetadataRepository::findByDocumentId)
                .flatMap(optionalFile -> optionalFile.map(Stream::of).orElseGet(Stream::empty))
                .collect(Collectors.toMap(
                        file -> file.getFilepath().toFullPath(),
                        file -> file,
                        (existing, replacement) -> replacement
                ));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FileMetadata> getFilesByFolderIds(List<String> folderIds) {
        return folderIds.stream()
                .flatMap(folderId -> fileMetadataRepository.findByFolderId(folderId).stream())
                .collect(Collectors.toList());
    }
}
