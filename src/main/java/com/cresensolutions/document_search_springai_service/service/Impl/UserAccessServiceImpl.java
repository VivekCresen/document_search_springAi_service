package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.domain.DocumentRepositoryUserMapping;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckRequest;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckResponse;
import com.cresensolutions.document_search_springai_service.repository.DocumentRepositoryUserMappingRepository;
import com.cresensolutions.document_search_springai_service.repository.FileInIndexRepository;
import com.cresensolutions.document_search_springai_service.repository.PrestageDocumentRepository;
import com.cresensolutions.document_search_springai_service.service.DocumentService;
import com.cresensolutions.document_search_springai_service.service.UserAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Applies per-user folder restrictions and excludes files that are not stable in the index.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserAccessServiceImpl implements UserAccessService {

    private final DocumentRepositoryUserMappingRepository userMappingRepository;
    private final DocumentService documentService;
    private final FileInIndexRepository fileInIndexRepository;
    private final PrestageDocumentRepository prestageDocumentRepository;
    private final com.cresensolutions.document_search_springai_service.repository.UserRepository userRepository;

    @Override
    public String resolveCurrentUser(String username, String email) {
        if (username != null && !username.isBlank()) {
            return username;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, Common.AUTHENTICATION_REQUIRED_MESSAGE);
    }

    @Override
    public PermissionCheckResponse checkPermissions(String username, PermissionCheckRequest request) {
        List<String> folderIds = request == null || request.getFolderIds() == null ? List.of() : request.getFolderIds();
        Map<String, Boolean> permissions = folderIds.stream()
                .collect(Collectors.toMap(
                        folderId -> folderId,
                        folderId -> hasAccessToFolder(username, folderId),
                        (first, second) -> first
                ));
        int accessibleCount = (int) permissions.values().stream().filter(Boolean::booleanValue).count();

        return PermissionCheckResponse.builder()
                .username(username)
                .permissions(permissions)
                .accessibleCount(accessibleCount)
                .restrictedCount(permissions.size() - accessibleCount)
                .build();
    }

    @Override
    public Map<String, Object> getMyAccess(String username) {
        List<String> restrictedFolders = getRestrictedFolders(username).stream()
                .sorted(Comparator.naturalOrder())
                .toList();

        return Map.of(
                "username", username,
                "total_restricted_folders", restrictedFolders.size(),
                "restricted_folder_ids", restrictedFolders,
                "note", Common.PERMISSIONS_NOTE
        );
    }

    @Override
    public Map<String, Object> clearPermissionCache(String username) {
        clearUserRestrictionsCache(username);
        return Map.of(
                "success", true,
                "message", "Permission cache cleared for user: " + username,
                "username", username
        );
    }

    @Override
    @Cacheable(value = "userRestrictions", key = "#username")
    public List<String> getRestrictedFolders(String username) {
        if (username == null || username.isBlank()) {
            return Collections.emptyList();
        }
        
        UUID userId = userRepository.findByUserName(username)
                .map(com.cresensolutions.document_search_springai_service.domain.User::getId)
                .orElse(null);

        return userMappingRepository.findRestrictedFolderIds(username, userId).stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .toList();
    }

    @Override
    public boolean hasAccessToFolder(String username, String folderId) {
        List<String> restrictedFolders = getRestrictedFolders(username);
        return !restrictedFolders.contains(folderId);
    }

    @Override
    public boolean hasAccessToAnyFolder(String username, Set<String> folderIds) {
        List<String> restrictedFolders = getRestrictedFolders(username);
        return folderIds.stream().anyMatch(folderId -> !restrictedFolders.contains(folderId));
    }

    @Override
    public List<FileInIndex> getAccessibleStableFiles(String username) {
        List<String> restrictedFolders = getRestrictedFolders(username);
        return documentService.getAccessibleStableFiles(restrictedFolders);
    }

    @Override
    @Cacheable(value = "unstableUris")
    public List<String> getUnstableFileUris() {
        return fileInIndexRepository.findUnstableBlobUris();
    }

    @Override
    public String createSearchFilter(String username) {
        List<String> restrictedFolders = getRestrictedFolders(username);
        List<String> unstableUris = getUnstableFileUris();

        // Azure AI Search filter syntax uses single-quoted OData string values.
        List<String> filterParts = restrictedFolders.stream()
                .map(folderId -> "folder_id ne '" + escapeODataValue(folderId) + "'")
                .collect(Collectors.toList());

        unstableUris.stream()
                .filter(Objects::nonNull)
                .map(uri -> "blob_uri ne '" + escapeODataValue(uri) + "'")
                .forEach(filterParts::add);

        return String.join(" and ", filterParts);
    }

    @Override
    public List<Map<String, Object>> filterSearchResults(String username, List<Map<String, Object>> searchResults) {
        if (searchResults == null || searchResults.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> restrictedFolders = Set.copyOf(getRestrictedFolders(username));
        Set<String> unstableUris = Set.copyOf(getUnstableFileUris());

        if (restrictedFolders.isEmpty() && unstableUris.isEmpty()) {
            return searchResults;
        }

        return searchResults.stream()
                .filter(result -> {
                    // Some result projections use blob_uri, while older ones expose filepath.
                    String folderId = Objects.toString(result.getOrDefault("folder_id", "0"));
                    String blobUri = Objects.toString(
                            result.getOrDefault("blob_uri", result.getOrDefault("filepath", "")));
                    return !restrictedFolders.contains(folderId) && !unstableUris.contains(blobUri);
                })
                .toList();
    }

    @Override
    public List<String> filterAccessibleFolders(String username, List<String> folderIds) {
        List<String> restrictedFolders = getRestrictedFolders(username);
        return folderIds.stream()
                .filter(folderId -> !restrictedFolders.contains(folderId))
                .collect(Collectors.toList());
    }

    @Override
    public long countAccessibleFolders(String username) {
        return Math.max(0, prestageDocumentRepository.countFolders() - getRestrictedFolders(username).size());
    }

    @Override
    @Transactional
    @CacheEvict(value = "userRestrictions", key = "#username")
    public void addFolderRestrictions(String username, String[] restrictedFolders) {
        // Fetch User to link by ID
        com.cresensolutions.document_search_springai_service.domain.User user = userRepository.findByUserName(username).orElse(null);

        // Replace instead of patching so the stored mapping exactly matches the request.
        if (user != null) {
            userMappingRepository.deleteByUserId(user.getId());
        } else {
            userMappingRepository.deleteByUserName(username);
        }

        if (restrictedFolders != null) {
            for (String folderId : restrictedFolders) {
                if (folderId == null || folderId.isBlank()) {
                    continue;
                }
                userMappingRepository.save(DocumentRepositoryUserMapping.builder()
                        .user(user)
                        .userName(username)
                        .foldersAccess(prestageDocumentRepository.findById(Long.valueOf(folderId)).orElse(null))
                        .build());
            }
        }

        log.info("Updated folder restrictions for user {}", username);
    }

    @Override
    @Transactional
    @CacheEvict(value = "userRestrictions", key = "#username")
    public void removeAllRestrictions(String username) {
        userRepository.findByUserName(username).ifPresentOrElse(
                user -> userMappingRepository.deleteByUserId(user.getId()),
                () -> userMappingRepository.deleteByUserName(username)
        );
        log.info("Removed all folder restrictions for user {}", username);
    }

    @Override
    @CacheEvict(value = "userRestrictions", allEntries = true)
    public void clearAllUserRestrictionsCache() {
        log.info("Cleared all user restrictions cache");
    }

    @Override
    @CacheEvict(value = "userRestrictions", key = "#username")
    public void clearUserRestrictionsCache(String username) {
        log.info("Cleared restrictions cache for user {}", username);
    }

    private String escapeODataValue(String value) {
        // OData represents a literal single quote as two single quotes.
        return value == null ? "" : value.replace("'", "''");
    }
}
