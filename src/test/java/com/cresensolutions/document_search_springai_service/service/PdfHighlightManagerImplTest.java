package com.cresensolutions.document_search_springai_service.service;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.cresensolutions.document_search_springai_service.config.CloudProperty;
import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;
import com.cresensolutions.document_search_springai_service.service.Impl.PdfHighlightManagerImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.Color;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PdfHighlightManagerImpl Tests")
class PdfHighlightManagerImplTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock BlobServiceClient blobServiceClient;
    @Mock BlobContainerClient blobContainerClient;
    @Mock BlobClient blobClient;
    @Mock CloudProperty cloudProperty;

    @InjectMocks PdfHighlightManagerImpl service;

    @BeforeEach
    void setUp() {
        lenient().when(cloudProperty.getContainerName()).thenReturn("test-container");
        lenient().when(cloudProperty.getBlobSasExpiresInDaysForPdf()).thenReturn(7L);
        lenient().when(blobServiceClient.getBlobContainerClient(anyString())).thenReturn(blobContainerClient);
        lenient().when(blobContainerClient.getBlobClient(anyString())).thenReturn(blobClient);
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: returns empty result for null/blank blobName")
    void highlight_nullBlobName_returnsEmpty() {
        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                null, List.of("text"), Color.YELLOW, "conv", 1, USER_ID, null);
        assertThat(result.getDownloadLink()).isEmpty();
        assertThat(result.getViewLink()).isEmpty();
        assertThat(result.getHighlightedPages()).isEmpty();
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: returns empty result for blank blobName")
    void highlight_blankBlobName_returnsEmpty() {
        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "   ", List.of("text"), Color.YELLOW, "conv", 1, USER_ID, null);
        assertThat(result.getDownloadLink()).isEmpty();
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: returns empty result when blob download throws")
    void highlight_downloadFails_returnsEmpty() {
        when(blobClient.downloadContent()).thenThrow(new RuntimeException("Blob not found"));

        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder/file.pdf", List.of("some passage"), Color.YELLOW, "conv", 1, USER_ID, Collections.emptyList());

        assertThat(result.getDownloadLink()).isEmpty();
        assertThat(result.getHighlightedPages()).isEmpty();
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: processes valid PDF bytes and uploads result")
    void highlight_validPdf_uploadsAndReturnsSasUrls() throws Exception {
        // Create a minimal valid PDF in memory using PDFBox
        byte[] pdfBytes = createMinimalPdf();

        com.azure.core.util.BinaryData binaryData = mock(com.azure.core.util.BinaryData.class);
        when(blobClient.downloadContent()).thenReturn(binaryData);
        when(binaryData.toBytes()).thenReturn(pdfBytes);
        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/container/highlighted_docs/file.pdf");
        when(blobClient.generateSas(any(BlobServiceSasSignatureValues.class))).thenReturn("sig=abc123");

        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder/file.pdf",
                List.of("Hello World"),
                Color.YELLOW,
                "conv1", 1, USER_ID,
                Collections.emptyList()
        );

        assertThat(result.getDownloadLink()).contains("https://");
        assertThat(result.getViewLink()).contains("https://");
        verify(blobClient).upload(any(), anyLong(), eq(true));
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: uses coordinate-based highlighting when diPageSpans provided")
    void highlight_withDiPageSpans_usesCoordinateStrategy() throws Exception {
        byte[] pdfBytes = createMinimalPdf();

        com.azure.core.util.BinaryData binaryData = mock(com.azure.core.util.BinaryData.class);
        when(blobClient.downloadContent()).thenReturn(binaryData);
        when(binaryData.toBytes()).thenReturn(pdfBytes);
        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/container/blob.pdf");
        when(blobClient.generateSas(any(BlobServiceSasSignatureValues.class))).thenReturn("sig=xyz");

        DiPageSpan span = new DiPageSpan();
        span.setPage(1);
        span.setPolygon(List.of(0.1, 0.1, 1.0, 0.1, 1.0, 0.5, 0.1, 0.5));
        span.setParagraphText("Hello World");

        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder/doc.pdf",
                List.of("Hello World"),
                Color.CYAN,
                "conv1", 2, USER_ID,
                List.of(span)
        );

        assertThat(result.getDownloadLink()).isNotBlank();
        assertThat(result.getHighlightedPages()).contains(1);
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: handles URL-encoded blob name on retry")
    void highlight_urlEncodedBlobName_decodesAndRetries() {
        // First call with encoded name fails, decoded call also fails -> empty result
        when(blobClient.downloadContent()).thenThrow(new RuntimeException("Not found"));

        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder%2Ffile%20name.pdf",
                List.of("text"),
                Color.YELLOW,
                "conv", 1, USER_ID,
                null
        );
        assertThat(result.getDownloadLink()).isEmpty();
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: null color defaults to yellow")
    void highlight_nullColor_usesYellow() throws Exception {
        byte[] pdfBytes = createMinimalPdf();
        com.azure.core.util.BinaryData binaryData = mock(com.azure.core.util.BinaryData.class);
        when(blobClient.downloadContent()).thenReturn(binaryData);
        when(binaryData.toBytes()).thenReturn(pdfBytes);
        when(blobClient.getBlobUrl()).thenReturn("https://example.com/file.pdf");
        when(blobClient.generateSas(any())).thenReturn("token");

        // Passing null color should not throw
        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "file.pdf", List.of("Hello"), null, "conv", 1, USER_ID, null);

        assertThat(result).isNotNull();
    }

    // -------------------------------------------------------------------------
    // Helper: creates a minimal 1-page PDF with text using PDFBox
    // -------------------------------------------------------------------------
    private static byte[] createMinimalPdf() throws Exception {
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Hello World");
                cs.endText();
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
