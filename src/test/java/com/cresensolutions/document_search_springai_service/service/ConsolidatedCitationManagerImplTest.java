package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;
import com.cresensolutions.document_search_springai_service.dto.RagSourceDocument;
import com.cresensolutions.document_search_springai_service.dto.SupportingPassage;
import com.cresensolutions.document_search_springai_service.service.Impl.ConsolidatedCitationManagerImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.Color;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsolidatedCitationManagerImpl Tests")
class ConsolidatedCitationManagerImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    PdfHighlightManager pdfHighlightManager;

    @InjectMocks
    ConsolidatedCitationManagerImpl service;

    @Test
    @DisplayName("createCitationsFromPassages: skips passages with no matching source document")
    void createCitationsFromPassages_skipsMissingSource() {
        SupportingPassage passage = SupportingPassage.builder().source("file1.pdf").text("text").build();
        RagSourceDocument sourceDoc = RagSourceDocument.builder().source("file2.pdf").build();

        Map<String, Object> citations = service.createCitationsFromPassages(
                List.of(passage), List.of(sourceDoc), "chat1", 1, USER_ID);

        assertThat(citations).isEmpty();
        verifyNoInteractions(pdfHighlightManager);
    }

    @Test
    @DisplayName("createCitationsFromPassages: resolves blob URI path and delegates to PdfHighlightManager")
    void createCitationsFromPassages_resolvesUri() {
        SupportingPassage passage = SupportingPassage.builder()
                .source("file1.pdf")
                .text("text")
                .individualPassages(List.of("text1"))
                .relevance("matches exact answer")
                .page("3, 4")
                .build();
        // Using an absolute URI from which we extract the path
        RagSourceDocument sourceDoc = RagSourceDocument.builder()
                .source("file1.pdf")
                .filepath("some_path.pdf")
                .blobUri("https://account.blob.core.windows.net/container/folder/file1.pdf")
                .build();

        HighlightedPdfResult mockResult = HighlightedPdfResult.builder().downloadLink("download_url").viewLink("view_url").highlightedPages(List.of(1)).build();
        when(pdfHighlightManager.highlightMultiplePassagesInPdf(
                eq("folder/file1.pdf"), // expected resolved path
                eq(List.of("text1")),
                any(Color.class),
                eq("chat1"),
                eq(1),
                eq(USER_ID),
                anyList()
        )).thenReturn(mockResult);

        Map<String, Object> citations = service.createCitationsFromPassages(
                List.of(passage), List.of(sourceDoc), "chat1", 1, USER_ID);

        assertThat(citations).hasSize(1);
        
        @SuppressWarnings("unchecked")
        Map<String, Object> citation = (Map<String, Object>) citations.get("1");
        assertThat(citation.get("file_name")).isEqualTo("file1.pdf");
        assertThat(citation.get("view_link")).isEqualTo("view_url");
        assertThat(citation.get("download_link")).isEqualTo("download_url");
        assertThat(citation.get("highlighted_pages")).isEqualTo(List.of(1));
        assertThat(citation.get("text")).isEqualTo("text");
        assertThat(citation.get("relevance")).isEqualTo("matches exact answer");
        assertThat(citation.get("page")).isEqualTo("3, 4");
    }

    @Test
    @DisplayName("createCitationsFromPassages: falls back to filepath if blobUri is unparseable or blank")
    void createCitationsFromPassages_fallbackToFilePath() {
        SupportingPassage passage = SupportingPassage.builder().source("file1.pdf").text("text").build();
        RagSourceDocument sourceDoc = RagSourceDocument.builder()
                .source("file1.pdf")
                .filepath("fallback/path.pdf")
                .blobUri("")
                .build();

        HighlightedPdfResult mockResult = HighlightedPdfResult.builder().downloadLink("download_url").viewLink("view_url").highlightedPages(List.of()).build();
        when(pdfHighlightManager.highlightMultiplePassagesInPdf(
                eq("fallback/path.pdf"), // fallback to filepath
                eq(List.of("text")), // fallback to single text when individual passages is empty
                any(Color.class),
                anyString(),
                anyInt(),
                any(java.util.UUID.class),
                any()
        )).thenReturn(mockResult);

        Map<String, Object> citations = service.createCitationsFromPassages(
                List.of(passage), List.of(sourceDoc), "chat1", 1, USER_ID);

        assertThat(citations).hasSize(1);
        verify(pdfHighlightManager).highlightMultiplePassagesInPdf(
                eq("fallback/path.pdf"), anyList(), any(), anyString(), anyInt(), any(java.util.UUID.class), any());
    }

    @Test
    @DisplayName("createCitationsFromPassages: cycles through colors correctly")
    void createCitationsFromPassages_cyclesColors() {
        SupportingPassage p1 = SupportingPassage.builder().source("file1.pdf").text("t1").build();
        SupportingPassage p2 = SupportingPassage.builder().source("file2.pdf").text("t2").build();
        
        RagSourceDocument s1 = RagSourceDocument.builder().source("file1.pdf").filepath("path1").build();
        RagSourceDocument s2 = RagSourceDocument.builder().source("file2.pdf").filepath("path2").build();

        HighlightedPdfResult mockResult = HighlightedPdfResult.builder().downloadLink("d").viewLink("v").highlightedPages(List.of()).build();
        when(pdfHighlightManager.highlightMultiplePassagesInPdf(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(mockResult);

        service.createCitationsFromPassages(List.of(p1, p2), List.of(s1, s2), "chat1", 1, USER_ID);

        verify(pdfHighlightManager).highlightMultiplePassagesInPdf(eq("path1"), any(), eq(Color.YELLOW), any(), any(), any(), any());
        verify(pdfHighlightManager).highlightMultiplePassagesInPdf(eq("path2"), any(), eq(new Color(128, 255, 128)), any(), any(), any(), any());
    }
}
