package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.DocumentRepositoryUserMapping;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.domain.User;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckRequest;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckResponse;
import com.cresensolutions.document_search_springai_service.repository.DocumentRepositoryUserMappingRepository;
import com.cresensolutions.document_search_springai_service.repository.FileInIndexRepository;
import com.cresensolutions.document_search_springai_service.repository.demoDocumentRepository;
import com.cresensolutions.document_search_springai_service.repository.UserRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.UserAccessServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAccessServiceImpl Tests")
class UserAccessServiceImplTest {

    @Mock DocumentRepositoryUserMappingRepository userMappingRepository;
    @Mock DocumentService documentService;
    @Mock FileInIndexRepository fileInIndexRepository;
    @Mock demoDocumentRepository demoDocumentRepository;
    @Mock UserRepository userRepository;

    @InjectMocks UserAccessServiceImpl service;

    // -------------------------------------------------------------------------
    // getRestrictedFolders
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getRestrictedFolders: returns empty list for blank username")
    void getRestrictedFolders_blankUsername() {
        assertThat(service.getRestrictedFolders("")).isEmpty();
        assertThat(service.getRestrictedFolders(null)).isEmpty();
        verifyNoInteractions(userMappingRepository);
    }

    @Test
    @DisplayName("getRestrictedFolders: finds by user id when user exists")
    void getRestrictedFolders_userExists() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findByUserName("vivek")).thenReturn(Optional.of(user));
        when(userMappingRepository.findRestrictedFolderIds("vivek", userId)).thenReturn(List.of(1L, 2L));

        List<String> result = service.getRestrictedFolders("vivek");
        assertThat(result).containsExactly("1", "2");
    }

    @Test
    @DisplayName("getRestrictedFolders: maps Integer folder IDs to String list")
    void getRestrictedFolders_returnsMappedIds() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(1L, 5L, 9L));

        List<String> result = service.getRestrictedFolders("vivek");

        assertThat(result).containsExactly("1", "5", "9");
    }

    @Test
    @DisplayName("getRestrictedFolders: filters out null folder IDs")
    void getRestrictedFolders_filtersNulls() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(java.util.Arrays.asList(1L, null, 3L));

        List<String> result = service.getRestrictedFolders("vivek");

        assertThat(result).containsExactly("1", "3");
    }

    // -------------------------------------------------------------------------
    // resolveCurrentUser
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("resolveCurrentUser: returns username if present")
    void resolveCurrentUser_usernamePresent() {
        assertThat(service.resolveCurrentUser("vivek", "email")).isEqualTo("vivek");
    }

    @Test
    @DisplayName("resolveCurrentUser: returns email if username is blank")
    void resolveCurrentUser_emailPresent() {
        assertThat(service.resolveCurrentUser("", "email@example.com")).isEqualTo("email@example.com");
    }

    @Test
    @DisplayName("resolveCurrentUser: throws exception if both blank")
    void resolveCurrentUser_bothBlank_throws() {
        assertThatThrownBy(() -> service.resolveCurrentUser("", null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Authentication");
    }

    // -------------------------------------------------------------------------
    // checkPermissions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("checkPermissions: computes access per folder")
    void checkPermissions_success() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L));
        PermissionCheckRequest req = new PermissionCheckRequest();
        req.setFolderIds(List.of("1", "5"));

        PermissionCheckResponse resp = service.checkPermissions("vivek", req);

        assertThat(resp.getUsername()).isEqualTo("vivek");
        assertThat(resp.getAccessibleCount()).isEqualTo(1);
        assertThat(resp.getRestrictedCount()).isEqualTo(1);
        assertThat(resp.getPermissions().get("1")).isTrue();
        assertThat(resp.getPermissions().get("5")).isFalse();
    }

    @Test
    @DisplayName("checkPermissions: null request returns empty permissions")
    void checkPermissions_nullRequest() {
        PermissionCheckResponse resp = service.checkPermissions("vivek", null);
        assertThat(resp.getPermissions()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // getMyAccess
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getMyAccess: returns map of access data")
    void getMyAccess_success() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L, 2L));

        Map<String, Object> map = service.getMyAccess("vivek");

        assertThat(map.get("username")).isEqualTo("vivek");
        assertThat(map.get("total_restricted_folders")).isEqualTo(2);
        assertThat(map.get("restricted_folder_ids")).isEqualTo(List.of("2", "5"));
    }

    // -------------------------------------------------------------------------
    // hasAccessToFolder
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasAccessToFolder: returns true if folder NOT restricted")
    void hasAccessToFolder_notRestricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L));
        assertThat(service.hasAccessToFolder("vivek", "10")).isTrue();
    }

    @Test
    @DisplayName("hasAccessToFolder: returns false if folder IS restricted")
    void hasAccessToFolder_restricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L));
        assertThat(service.hasAccessToFolder("vivek", "5")).isFalse();
    }

    // -------------------------------------------------------------------------
    // hasAccessToAnyFolder
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasAccessToAnyFolder: true when at least one folder is accessible")
    void hasAccessToAnyFolder_oneAccessible() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L));
        assertThat(service.hasAccessToAnyFolder("vivek", Set.of("5", "10"))).isTrue();
    }

    @Test
    @DisplayName("hasAccessToAnyFolder: false when all folders restricted")
    void hasAccessToAnyFolder_allRestricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L, 10L));
        assertThat(service.hasAccessToAnyFolder("vivek", Set.of("5", "10"))).isFalse();
    }

    // -------------------------------------------------------------------------
    // getUnstableFileUris
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getUnstableFileUris: delegates to repository")
    void getUnstableFileUris_delegatesToRepo() {
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of("blob://x", "blob://y"));
        assertThat(service.getUnstableFileUris()).containsExactly("blob://x", "blob://y");
    }

    // -------------------------------------------------------------------------
    // createSearchFilter
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createSearchFilter: builds OData filter from restrictions and unstable URIs")
    void createSearchFilter_buildsODataFilter() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(3L));
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of("blob://bad"));

        String filter = service.createSearchFilter("vivek");

        assertThat(filter).contains("folder_id ne '3'");
        assertThat(filter).contains("blob_uri ne 'blob://bad'");
        assertThat(filter).contains(" and ");
    }

    @Test
    @DisplayName("createSearchFilter: returns empty string when no restrictions")
    void createSearchFilter_noRestrictions_emptyFilter() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of());
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of());

        assertThat(service.createSearchFilter("vivek")).isEmpty();
    }

    @Test
    @DisplayName("createSearchFilter: escapes single quotes in OData values")
    void createSearchFilter_escapesQuotes() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of());
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of("blob://it's-bad"));

        String filter = service.createSearchFilter("vivek");

        // Single quote becomes two single quotes in OData
        assertThat(filter).contains("blob_uri ne 'blob://it''s-bad'");
    }

    // -------------------------------------------------------------------------
    // filterSearchResults
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filterSearchResults: null input returns empty list")
    void filterSearchResults_null_empty() {
        assertThat(service.filterSearchResults("vivek", null)).isEmpty();
    }

    @Test
    @DisplayName("filterSearchResults: removes restricted folder results")
    void filterSearchResults_removesRestricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L));
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of());

        List<Map<String, Object>> results = List.of(
                Map.of("folder_id", "5", "blob_uri", "blob://a"),
                Map.of("folder_id", "10", "blob_uri", "blob://b")
        );

        List<Map<String, Object>> filtered = service.filterSearchResults("vivek", results);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("folder_id")).isEqualTo("10");
    }

    @Test
    @DisplayName("filterSearchResults: removes unstable URI results")
    void filterSearchResults_removesUnstableUris() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of());
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of("blob://bad"));

        List<Map<String, Object>> results = List.of(
                Map.of("folder_id", "1", "blob_uri", "blob://bad"),
                Map.of("folder_id", "2", "blob_uri", "blob://good")
        );

        List<Map<String, Object>> filtered = service.filterSearchResults("vivek", results);

        assertThat(filtered).hasSize(1);
        assertThat(filtered.get(0).get("blob_uri")).isEqualTo("blob://good");
    }

    // -------------------------------------------------------------------------
    // addFolderRestrictions / removeAllRestrictions
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("addFolderRestrictions: deletes old mappings and saves new ones for non-existing user")
    void addFolderRestrictions_replacesExisting_NoUser() {
        when(userRepository.findByUserName("vivek")).thenReturn(Optional.empty());
        when(userMappingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.addFolderRestrictions("vivek", new String[]{"1", "2", "3"});

        verify(userMappingRepository).deleteByUserName("vivek");
        verify(userMappingRepository, times(3)).save(any(DocumentRepositoryUserMapping.class));
    }

    @Test
    @DisplayName("addFolderRestrictions: deletes old mappings and saves new ones for existing user")
    void addFolderRestrictions_replacesExisting_UserExists() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findByUserName("vivek")).thenReturn(Optional.of(user));
        when(userMappingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.addFolderRestrictions("vivek", new String[]{"1"});

        verify(userMappingRepository).deleteByUserId(userId);
        verify(userMappingRepository, times(1)).save(any(DocumentRepositoryUserMapping.class));
    }

    @Test
    @DisplayName("addFolderRestrictions: skips blank folder entries")
    void addFolderRestrictions_skipsBlank() {
        when(userMappingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.addFolderRestrictions("vivek", new String[]{"1", "", null, "5"});

        verify(userMappingRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("removeAllRestrictions: deletes all mappings for user when user is null")
    void removeAllRestrictions_deletesAll_NoUser() {
        when(userRepository.findByUserName("vivek")).thenReturn(Optional.empty());
        service.removeAllRestrictions("vivek");
        verify(userMappingRepository).deleteByUserName("vivek");
    }

    @Test
    @DisplayName("removeAllRestrictions: deletes all mappings for user when user exists")
    void removeAllRestrictions_deletesAll_UserExists() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        when(userRepository.findByUserName("vivek")).thenReturn(Optional.of(user));
        service.removeAllRestrictions("vivek");
        verify(userMappingRepository).deleteByUserId(userId);
    }

    // -------------------------------------------------------------------------
    // cache clears
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("clearPermissionCache: clears cache and returns success map")
    void clearPermissionCache_success() {
        Map<String, Object> map = service.clearPermissionCache("vivek");
        assertThat(map.get("success")).isEqualTo(true);
        assertThat(map.get("username")).isEqualTo("vivek");
    }

    @Test
    @DisplayName("clearAllUserRestrictionsCache: completes without error")
    void clearAllUserRestrictionsCache_success() {
        service.clearAllUserRestrictionsCache();
        // Just verify no exception
        assertThat(true).isTrue();
    }

    // -------------------------------------------------------------------------
    // filterAccessibleFolders
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filterAccessibleFolders: excludes restricted folder IDs")
    void filterAccessibleFolders_filtersOut() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(5L, 9L));

        List<String> result = service.filterAccessibleFolders("vivek", List.of("1", "5", "9", "10"));

        assertThat(result).containsExactly("1", "10");
    }

    // -------------------------------------------------------------------------
    // countAccessibleFolders
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("countAccessibleFolders: total minus restricted")
    void countAccessibleFolders_success() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(1L, 2L));
        when(demoDocumentRepository.countFolders()).thenReturn(10L);

        long count = service.countAccessibleFolders("vivek");

        assertThat(count).isEqualTo(8L);
    }

    // -------------------------------------------------------------------------
    // getAccessibleStableFiles
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAccessibleStableFiles: delegates to documentService with restricted folder list")
    void getAccessibleStableFiles_delegates() {
        when(userMappingRepository.findRestrictedFolderIds("vivek", null)).thenReturn(List.of(3L));
        List<FileInIndex> expected = List.of(FileInIndex.builder().blobUri("ok").build());
        when(documentService.getAccessibleStableFiles(List.of("3"))).thenReturn(expected);

        assertThat(service.getAccessibleStableFiles("vivek")).isSameAs(expected);
    }
}
