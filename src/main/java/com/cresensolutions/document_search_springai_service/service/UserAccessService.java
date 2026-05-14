package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckRequest;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckResponse;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract for applying folder restrictions and filtering searchable documents for a user.
 */
public interface UserAccessService {

    /**
     * Returns folder ids that the user is not allowed to search.
     */
    List<String> getRestrictedFolders(String username);

    /**
     * Resolves the active user identity from request-provided username/email values.
     */
    String resolveCurrentUser(String username, String email);

    /**
     * Builds a permission check response for the supplied folders.
     */
    PermissionCheckResponse checkPermissions(String username, PermissionCheckRequest request);

    /**
     * Builds the current user's access summary.
     */
    Map<String, Object> getMyAccess(String username);

    /**
     * Clears the current user's permission cache and returns response payload.
     */
    Map<String, Object> clearPermissionCache(String username);

    /**
     * Checks access to one folder id.
     */
    boolean hasAccessToFolder(String username, String folderId);

    /**
     * Checks whether at least one folder is accessible.
     */
    boolean hasAccessToAnyFolder(String username, Set<String> folderIds);

    /**
     * Returns stable index files after user folder restrictions are applied.
     */
    List<FileInIndex> getAccessibleStableFiles(String username);

    /**
     * Returns files that should be excluded from search because indexing is not stable.
     */
    List<String> getUnstableFileUris();

    /**
     * Builds the Azure AI Search OData filter for restricted folders and unstable files.
     */
    String createSearchFilter(String username);

    /**
     * Applies the same restrictions to search results already returned from another source.
     */
    List<Map<String, Object>> filterSearchResults(String username, List<Map<String, Object>> searchResults);

    /**
     * Removes restricted folder ids from the supplied folder list.
     */
    List<String> filterAccessibleFolders(String username, List<String> folderIds);

    /**
     * Counts folders visible to the user after restrictions are applied.
     */
    long countAccessibleFolders(String username);

    /**
     * Replaces all folder restrictions for the given user.
     */
    void addFolderRestrictions(String username, String[] restrictedFolders);

    /**
     * Removes all folder restrictions for the given user.
     */
    void removeAllRestrictions(String username);

    /**
     * Clears the cached restriction lookup for all users.
     */
    void clearAllUserRestrictionsCache();

    /**
     * Clears the cached restriction lookup for a single user.
     * Mirrors Python UserPermissionManager.clear_cache(username).
     */
    void clearUserRestrictionsCache(String username);
}
