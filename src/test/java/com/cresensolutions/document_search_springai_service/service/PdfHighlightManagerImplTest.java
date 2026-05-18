package com.cresensolutions.document_search_springai_service.service;

import com.azure.core.util.BinaryData;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.cresensolutions.document_search_springai_service.config.CloudProperty;
import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;
import com.cresensolutions.document_search_springai_service.service.Impl.PdfHighlightManagerImpl;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
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

     BinaryData binaryData = mock(BinaryData.class);
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

     BinaryData binaryData = mock(BinaryData.class);
        when(blobClient.downloadContent()).thenReturn(binaryData);
        when(binaryData.toBytes()).thenReturn(pdfBytes);
        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/container/blob.pdf");
        when(blobClient.generateSas(any(BlobServiceSasSignatureValues.class))).thenReturn("sig=xyz");

        DiPageSpan span = new DiPageSpan();
        span.setPage(1);
        span.setPolygon(List.of(0.1, 0.1, 1.0, 0.1, 1.0, 0.5, 0.1, 0.5));
        span.setParagraphText("Hello World");

        DiPageSpan invalidPage = new DiPageSpan();
        invalidPage.setPage(-1); // Invalid page

        DiPageSpan invalidPolygon = new DiPageSpan();
        invalidPolygon.setPage(1);
        invalidPolygon.setPolygon(List.of(0.1, 0.1)); // Too few points

        DiPageSpan tokenOverlapSpan = new DiPageSpan();
        tokenOverlapSpan.setPage(1);
        tokenOverlapSpan.setPolygon(List.of(0.1, 0.1, 1.0, 0.1, 1.0, 0.5, 0.1, 0.5));
        tokenOverlapSpan.setParagraphText("Hello World from DI system");

        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder/doc.pdf",
                List.of("Hello World from", "No match here"),
                Color.CYAN,
                "conv1", 2, USER_ID,
                List.of(span, invalidPage, invalidPolygon, tokenOverlapSpan)
        );

        assertThat(result.getDownloadLink()).isNotBlank();
        assertThat(result.getHighlightedPages()).contains(1);
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: fallback to sentence and word windows")
    void highlight_fallbackToSentenceAndWordWindows() throws Exception {
        byte[] pdfBytes = createMinimalPdf();

     BinaryData binaryData = mock(BinaryData.class);
        when(blobClient.downloadContent()).thenReturn(binaryData);
        when(binaryData.toBytes()).thenReturn(pdfBytes);
        when(blobClient.getBlobUrl()).thenReturn("https://account.blob.core.windows.net/container/blob.pdf");
        when(blobClient.generateSas(any(BlobServiceSasSignatureValues.class))).thenReturn("sig=xyz");

        // The PDF contains "Hello World". 
        // We will search for a long string where only "Hello World" matches a fragment.
        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder/doc.pdf",
                List.of("This is a really long string that is definitely not entirely in the PDF. But Hello World is in the PDF."),
                Color.CYAN,
                "conv1", 2, USER_ID,
                Collections.emptyList()
        );

        assertThat(result.getDownloadLink()).isNotBlank();
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
    @DisplayName("highlightMultiplePassagesInPdf: succeeds on decoded blob name")
    void highlight_urlEncodedBlobName_succeedsOnDecode() throws Exception {
        // First call with encoded name fails
        BlobClient encodedBlobClient = mock(BlobClient.class);
        BlobClient decodedBlobClient = mock(BlobClient.class);
        when(blobServiceClient.getBlobContainerClient(anyString())).thenReturn(blobContainerClient);
        
        when(blobContainerClient.getBlobClient("folder%2Ffile%20name.pdf")).thenReturn(encodedBlobClient);
        when(encodedBlobClient.downloadContent()).thenThrow(new RuntimeException("Not found"));
        
        when(blobContainerClient.getBlobClient("folder/file name.pdf")).thenReturn(decodedBlobClient);
     BinaryData binaryData = mock(BinaryData.class);
        when(decodedBlobClient.downloadContent()).thenReturn(binaryData);
        when(binaryData.toBytes()).thenReturn(createMinimalPdf());
        lenient().when(decodedBlobClient.getBlobUrl()).thenReturn("https://example.com/file.pdf");
        lenient().when(decodedBlobClient.generateSas(any())).thenReturn("token");

        HighlightedPdfResult result = service.highlightMultiplePassagesInPdf(
                "folder%2Ffile%20name.pdf",
                List.of("Hello World"),
                Color.YELLOW,
                "conv", 1, USER_ID,
                null
        );
        assertThat(result.getDownloadLink()).isNotEmpty();
    }

    @Test
    @DisplayName("highlightMultiplePassagesInPdf: null color defaults to yellow")
    void highlight_nullColor_usesYellow() throws Exception {
        byte[] pdfBytes = createMinimalPdf();
     BinaryData binaryData = mock(BinaryData.class);
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
    // Private Method Reflection Tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Reflection: helper methods behavior")
    void reflection_helperMethods() throws Exception {
        // normalizeWhitespace
        Method normalize = PdfHighlightManagerImpl.class.getDeclaredMethod("normalizeWhitespace", String.class);
        normalize.setAccessible(true);
        assertThat(normalize.invoke(service, (String) null)).isEqualTo("");
        assertThat(normalize.invoke(service, "  a   b\tc  ")).isEqualTo("a b c");

        // hasText
        Method hasText = PdfHighlightManagerImpl.class.getDeclaredMethod("hasText", String.class);
        hasText.setAccessible(true);
        assertThat((Boolean) hasText.invoke(service, (String) null)).isFalse();
        assertThat((Boolean) hasText.invoke(service, "   ")).isFalse();
        assertThat((Boolean) hasText.invoke(service, " a ")).isTrue();

        // filename
        Method filename = PdfHighlightManagerImpl.class.getDeclaredMethod("filename", String.class);
        filename.setAccessible(true);
        assertThat(filename.invoke(service, "folder/doc.pdf")).isEqualTo("doc.pdf");
        assertThat(filename.invoke(service, "doc.pdf")).isEqualTo("doc.pdf");

        // emptyResult
        Method emptyResult = PdfHighlightManagerImpl.class.getDeclaredMethod("emptyResult");
        emptyResult.setAccessible(true);
        HighlightedPdfResult res = (HighlightedPdfResult) emptyResult.invoke(service);
        assertThat(res.getDownloadLink()).isEmpty();

        // tokens
        Method tokens = PdfHighlightManagerImpl.class.getDeclaredMethod("tokens", String.class);
        tokens.setAccessible(true);
        @SuppressWarnings("unchecked")
        Set<String> toks = (Set<String>) tokens.invoke(service, "Hello world, it's me!");
        assertThat(toks).contains("hello", "world");

        // tokenOverlapRatio
        Method overlap = PdfHighlightManagerImpl.class.getDeclaredMethod("tokenOverlapRatio", String.class, String.class);
        overlap.setAccessible(true);
        assertThat((Double) overlap.invoke(service, "a b", "c d")).isEqualTo(0.0);
        assertThat((Double) overlap.invoke(service, "hello world again", "hello universe")).isGreaterThan(0.0);

        // sentenceSegments
        Method sentences = PdfHighlightManagerImpl.class.getDeclaredMethod("sentenceSegments", String.class);
        sentences.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> segs = (List<String>) sentences.invoke(service, "Hello world. How are you? I am fine!");
        assertThat(segs).hasSize(3).contains("Hello world.", "How are you?", "I am fine!");

        // wordWindows
        Method windows = PdfHighlightManagerImpl.class.getDeclaredMethod("wordWindows", String.class);
        windows.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> wins = (List<String>) windows.invoke(service, "1 2 3 4 5 6 7 8 9 10 11 12");
        assertThat(wins).isNotEmpty();
    }

    // -------------------------------------------------------------------------
    // Helper: creates a minimal 1-page PDF with text using PDFBox
    // -------------------------------------------------------------------------
    private static byte[] createMinimalPdf() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs =
                         new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(org.apache.pdfbox.pdmodel.font.PDType1Font.HELVETICA, 12);
                cs.newLineAtOffset(100, 700);
                cs.showText("Hello World");
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
