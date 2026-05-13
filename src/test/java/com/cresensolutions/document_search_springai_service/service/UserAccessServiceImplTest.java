package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.DocumentRepositoryUserMapping;
import com.cresensolutions.document_search_springai_service.domain.FileInIndex;
import com.cresensolutions.document_search_springai_service.repository.DocumentRepositoryUserMappingRepository;
import com.cresensolutions.document_search_springai_service.repository.FileInIndexRepository;
import com.cresensolutions.document_search_springai_service.repository.PrestageDocumentRepository;
import com.cresensolutions.document_search_springai_service.service.Impl.UserAccessServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserAccessServiceImpl Tests")
class UserAccessServiceImplTest {

    @Mock DocumentRepositoryUserMappingRepository userMappingRepository;
    @Mock DocumentService documentService;
    @Mock FileInIndexRepository fileInIndexRepository;
    @Mock PrestageDocumentRepository prestageDocumentRepository;

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
    @DisplayName("getRestrictedFolders: maps Integer folder IDs to String list")
    void getRestrictedFolders_returnsMappedIds() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(1, 5, 9));

        List<String> result = service.getRestrictedFolders("vivek");

        assertThat(result).containsExactly("1", "5", "9");
    }

    @Test
    @DisplayName("getRestrictedFolders: filters out null folder IDs")
    void getRestrictedFolders_filtersNulls() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(1, null, 3));

        List<String> result = service.getRestrictedFolders("vivek");

        assertThat(result).containsExactly("1", "3");
    }

    // -------------------------------------------------------------------------
    // hasAccessToFolder
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasAccessToFolder: returns true if folder NOT restricted")
    void hasAccessToFolder_notRestricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(5));
        assertThat(service.hasAccessToFolder("vivek", "10")).isTrue();
    }

    @Test
    @DisplayName("hasAccessToFolder: returns false if folder IS restricted")
    void hasAccessToFolder_restricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(5));
        assertThat(service.hasAccessToFolder("vivek", "5")).isFalse();
    }

    // -------------------------------------------------------------------------
    // hasAccessToAnyFolder
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("hasAccessToAnyFolder: true when at least one folder is accessible")
    void hasAccessToAnyFolder_oneAccessible() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(5));
        assertThat(service.hasAccessToAnyFolder("vivek", Set.of("5", "10"))).isTrue();
    }

    @Test
    @DisplayName("hasAccessToAnyFolder: false when all folders restricted")
    void hasAccessToAnyFolder_allRestricted() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(5, 10));
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
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(3));
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of("blob://bad"));

        String filter = service.createSearchFilter("vivek");

        assertThat(filter).contains("folder_id ne '3'");
        assertThat(filter).contains("blob_uri ne 'blob://bad'");
        assertThat(filter).contains(" and ");
    }

    @Test
    @DisplayName("createSearchFilter: returns empty string when no restrictions")
    void createSearchFilter_noRestrictions_emptyFilter() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of());
        when(fileInIndexRepository.findUnstableBlobUris()).thenReturn(List.of());

        assertThat(service.createSearchFilter("vivek")).isEmpty();
    }

    @Test
    @DisplayName("createSearchFilter: escapes single quotes in OData values")
    void createSearchFilter_escapesQuotes() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of());
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
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(5));
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
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of());
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
    @DisplayName("addFolderRestrictions: deletes old mappings and saves new ones")
    void addFolderRestrictions_replacesExisting() {
        when(userMappingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.addFolderRestrictions("vivek", new String[]{"1", "2", "3"});

        verify(userMappingRepository).deleteByUserName("vivek");
        verify(userMappingRepository, times(3)).save(any(DocumentRepositoryUserMapping.class));
    }

    @Test
    @DisplayName("addFolderRestrictions: skips blank folder entries")
    void addFolderRestrictions_skipsBlank() {
        when(userMappingRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.addFolderRestrictions("vivek", new String[]{"1", "", null, "5"});

        verify(userMappingRepository, times(2)).save(any());
    }

    @Test
    @DisplayName("removeAllRestrictions: deletes all mappings for user")
    void removeAllRestrictions_deletesAll() {
        service.removeAllRestrictions("vivek");
        verify(userMappingRepository).deleteByUserName("vivek");
    }

    // -------------------------------------------------------------------------
    // filterAccessibleFolders
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("filterAccessibleFolders: excludes restricted folder IDs")
    void filterAccessibleFolders_filtersOut() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(5, 9));

        List<String> result = service.filterAccessibleFolders("vivek", List.of("1", "5", "9", "10"));

        assertThat(result).containsExactly("1", "10");
    }

    // -------------------------------------------------------------------------
    // getAccessibleStableFiles
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getAccessibleStableFiles: delegates to documentService with restricted folder list")
    void getAccessibleStableFiles_delegates() {
        when(userMappingRepository.findRestrictedFolderIds("vivek")).thenReturn(List.of(3));
        List<FileInIndex> expected = List.of(FileInIndex.builder().blobUri("ok").build());
        when(documentService.getAccessibleStableFiles(List.of("3"))).thenReturn(expected);

        assertThat(service.getAccessibleStableFiles("vivek")).isSameAs(expected);
    }
}
