package com.cresensolutions.document_search_springai_service.service.Impl;

import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.cresensolutions.document_search_springai_service.config.CloudProperty;
import com.cresensolutions.document_search_springai_service.dto.DiPageSpan;
import com.cresensolutions.document_search_springai_service.dto.HighlightedPdfResult;
import com.cresensolutions.document_search_springai_service.service.PdfHighlightManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDColor;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Implementation of the PDF Highlighting Manager.
 * 
 * This service handles PDF annotations using two strategies:
 * 1. Coordinate-based highlighting (Strategy 1): Uses DI polygons (in inches) 
 *    converted to PDF points (72 DPI) for precise annotation.
 * 2. Text-search fallback (Strategies 2-4): Uses PDFBox to extract text lines 
 *    and match them against target passages (sentence/word based fallback).
 * 
 * It also manages downloading from and uploading to Azure Blob Storage with SAS URLs.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PdfHighlightManagerImpl implements PdfHighlightManager {

    private static final String HIGHLIGHTED_FOLDER = "highlighted_docs";
    private static final float DI_TO_POINTS = 72.0f;

    private final BlobServiceClient blobServiceClient;
    private final CloudProperty cloudProperty;

    /**
     * Highlights multiple passages in a PDF sourced from blob storage.
     * 
     * @param pdfBlobName     Source blob name (e.g., "manuals/file.pdf").
     * @param textPassages    List of text chunks to highlight.
     * @param color           Highlight color.
     * @param conversationId  ID for unique destination filename.
     * @param questionId      ID for unique destination filename.
     * @param userId          ID for unique destination filename.
     * @param diPageSpans     List of DI coordinates (if available).
     * @return Result containing download/view links and highlighted page numbers.
     */
    @Override
    public HighlightedPdfResult highlightMultiplePassagesInPdf(
            String pdfBlobName,
            List<String> textPassages,
            Color color,
            String conversationId,
            Integer questionId,
            Long userId,
            List<DiPageSpan> diPageSpans
    ) {
        if (!hasText(pdfBlobName)) {
            return emptyResult();
        }

        try {
            // 1. Download original PDF from Azure Blob Storage
            byte[] pdfBytes = downloadBlob(pdfBlobName);
            Set<Integer> highlightedPages = new LinkedHashSet<>();
            int totalHighlights;

            try (PDDocument document = PDDocument.load(pdfBytes)) {
                Color highlightColor = color == null ? Color.YELLOW : color;
                
                // 2. Try Strategy 1: Coordinate-based highlighting (Fast & Precise)
                totalHighlights = applyCoordinateHighlights(document, diPageSpans, textPassages, highlightColor, highlightedPages);

                // 3. Fallback to Strategy 2-4: Text-search based highlighting
                if (totalHighlights == 0) {
                    totalHighlights = applyTextFallbackHighlights(document, textPassages, highlightColor, highlightedPages);
                }

                // 4. Upload the annotated PDF to a unique location in blob storage
                String destinationBlobName = buildDestBlobName(pdfBlobName, conversationId, questionId, userId);
                byte[] highlightedBytes = writeDocument(document);
                uploadBlob(destinationBlobName, highlightedBytes);

                // 5. Generate SAS URLs for viewing and downloading
                List<Integer> pages = highlightedPages.stream().sorted().toList();
                Integer firstPage = pages.isEmpty() ? null : pages.get(0);
                return HighlightedPdfResult.builder()
                        .downloadLink(generateSasUrl(destinationBlobName, "attachment", firstPage))
                        .viewLink(generateSasUrl(destinationBlobName, "inline", firstPage))
                        .highlightedPages(pages)
                        .build();
            }
        } catch (Exception e) {
            log.warn("PDF highlighting failed for {}", pdfBlobName, e);
            return emptyResult();
        }
    }

    /**
     * Strategy 1: Applies highlights using DI polygon coordinates.
     * Converts inches (DI) to points (PDF standard, 72 DPI) and handles coordinate inversion.
     */
    private int applyCoordinateHighlights(
            PDDocument document,
            List<DiPageSpan> spans,
            List<String> exactTexts,
            Color color,
            Set<Integer> highlightedPages
    ) throws IOException {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }
        List<DiPageSpan> relevantSpans = matchSpansToExtractions(spans, exactTexts);
        int hits = 0;
        for (DiPageSpan span : relevantSpans) {
            if (span.getPage() == null || span.getPage() < 1 || span.getPage() > document.getNumberOfPages()) {
                continue;
            }
            List<Double> polygon = span.getPolygon();
            if (polygon == null || polygon.size() < 8) {
                continue;
            }

            // Calculate bounding box from polygon vertices
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            float maxY = Float.MIN_VALUE;
            for (int i = 0; i + 1 < polygon.size(); i += 2) {
                float x = (float) (polygon.get(i) * DI_TO_POINTS);
                float y = (float) (polygon.get(i + 1) * DI_TO_POINTS);
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }

            PDPage page = document.getPage(span.getPage() - 1);
            PDRectangle mediaBox = page.getMediaBox();
            
            // PDFBox uses lower-left as origin (0,0); DI uses top-left. Invert Y accordingly.
            float pdfMinY = mediaBox.getHeight() - maxY;
            float pdfMaxY = mediaBox.getHeight() - minY;
            
            addHighlight(page, new PDRectangle(minX, pdfMinY, maxX - minX, pdfMaxY - pdfMinY), color);
            highlightedPages.add(span.getPage());
            hits++;
        }
        return hits;
    }

    private int applyTextFallbackHighlights(
            PDDocument document,
            List<String> textPassages,
            Color color,
            Set<Integer> highlightedPages
    ) throws IOException {
        if (textPassages == null || textPassages.isEmpty()) {
            return 0;
        }

        List<PageLine> lines = extractPageLines(document);
        int hits = 0;
        for (String passage : textPassages) {
            String cleanPassage = normalizeWhitespace(passage);
            if (cleanPassage.length() < 5) {
                continue;
            }

            int passageHits = highlightText(lines, cleanPassage, color, highlightedPages);
            if (passageHits == 0) {
                for (String segment : sentenceSegments(cleanPassage)) {
                    if (segment.length() > 15) {
                        passageHits += highlightText(lines, segment, color, highlightedPages);
                    }
                }
            }
            if (passageHits == 0) {
                for (String segment : wordWindows(cleanPassage)) {
                    if (segment.length() > 15) {
                        passageHits += highlightText(lines, segment, color, highlightedPages);
                    }
                }
            }
            hits += passageHits;
        }
        return hits;
    }

    private int highlightText(List<PageLine> lines, String text, Color color, Set<Integer> highlightedPages) throws IOException {
        String normalizedText = normalizeWhitespace(text).toLowerCase();
        int hits = 0;
        for (PageLine line : lines) {
            String normalizedLine = normalizeWhitespace(line.text()).toLowerCase();
            if (normalizedLine.contains(normalizedText) || normalizedText.contains(normalizedLine)) {
                addHighlight(line.page(), line.rectangle(), color);
                highlightedPages.add(line.pageNumber());
                hits++;
            }
        }
        return hits;
    }

    private List<DiPageSpan> matchSpansToExtractions(List<DiPageSpan> spans, List<String> exactTexts) {
        if (exactTexts == null || exactTexts.isEmpty()) {
            return spans;
        }
        List<DiPageSpan> relevant = new ArrayList<>();
        for (DiPageSpan span : spans) {
            String paragraph = normalizeWhitespace(span.getParagraphText());
            if (!hasText(paragraph)) {
                continue;
            }
            for (String exactText : exactTexts) {
                String cleanExactText = normalizeWhitespace(exactText);
                if (!hasText(cleanExactText)) {
                    continue;
                }
                String fragment = cleanExactText.substring(0, Math.min(80, cleanExactText.length()));
                if (paragraph.contains(cleanExactText) || cleanExactText.contains(paragraph)
                        || tokenOverlapRatio(cleanExactText, paragraph) > 0.60
                        || (fragment.length() > 20 && paragraph.contains(fragment))) {
                    relevant.add(span);
                    break;
                }
            }
        }
        return relevant.isEmpty() ? spans : relevant;
    }

    private double tokenOverlapRatio(String left, String right) {
        Set<String> leftTokens = tokens(left);
        if (leftTokens.isEmpty()) {
            return 0.0;
        }
        Set<String> rightTokens = tokens(right);
        long matches = leftTokens.stream().filter(rightTokens::contains).count();
        return (double) matches / leftTokens.size();
    }

    private Set<String> tokens(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : value.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.length() > 2) {
                result.add(token);
            }
        }
        return result;
    }

    private List<PageLine> extractPageLines(PDDocument document) throws IOException {
        List<PageLine> lines = new ArrayList<>();
        for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
            LineTextStripper stripper = new LineTextStripper(document.getPage(pageIndex), pageIndex + 1);
            stripper.setStartPage(pageIndex + 1);
            stripper.setEndPage(pageIndex + 1);
            stripper.getText(document);
            lines.addAll(stripper.lines());
        }
        return lines;
    }

    private void addHighlight(PDPage page, PDRectangle rectangle, Color color) throws IOException {
        PDAnnotationTextMarkup annotation = new PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT);
        annotation.setRectangle(rectangle);
        annotation.setQuadPoints(new float[]{
                rectangle.getLowerLeftX(), rectangle.getUpperRightY(),
                rectangle.getUpperRightX(), rectangle.getUpperRightY(),
                rectangle.getLowerLeftX(), rectangle.getLowerLeftY(),
                rectangle.getUpperRightX(), rectangle.getLowerLeftY()
        });
        annotation.setColor(new PDColor(new float[]{
                color.getRed() / 255.0f,
                color.getGreen() / 255.0f,
                color.getBlue() / 255.0f
        }, PDDeviceRGB.INSTANCE));
        page.getAnnotations().add(annotation);
    }

    private byte[] downloadBlob(String blobName) {
        try {
            return blobClient(blobName).downloadContent().toBytes();
        } catch (Exception e) {
            String decoded = URLDecoder.decode(blobName, StandardCharsets.UTF_8);
            if (!decoded.equals(blobName)) {
                return blobClient(decoded).downloadContent().toBytes();
            }
            throw e;
        }
    }

    private void uploadBlob(String blobName, byte[] data) {
        BlobClient client = blobClient(blobName);
        client.upload(new ByteArrayInputStream(data), data.length, true);
        client.setHttpHeaders(new BlobHttpHeaders().setContentType("application/pdf"));
    }

    private BlobClient blobClient(String blobName) {
        return blobServiceClient
                .getBlobContainerClient(cloudProperty.getContainerName())
                .getBlobClient(blobName);
    }

    private byte[] writeDocument(PDDocument document) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.save(out);
            return out.toByteArray();
        }
    }

    private String buildDestBlobName(String sourceBlobName, String conversationId, Integer questionId, Long userId) {
        String filename = filename(sourceBlobName);
        String baseName = filename.toLowerCase().endsWith(".pdf")
                ? filename.substring(0, filename.length() - 4)
                : filename;
        List<String> suffixParts = new ArrayList<>();
        if (hasText(conversationId)) {
            suffixParts.add("conv" + conversationId);
        }
        if (questionId != null) {
            suffixParts.add("q" + questionId);
        }
        if (userId != null) {
            suffixParts.add("user" + userId);
        }
        String suffix = suffixParts.isEmpty() ? "" : "_" + String.join("_", suffixParts);
        return HIGHLIGHTED_FOLDER + "/" + baseName + suffix + ".pdf";
    }

    private String generateSasUrl(String blobName, String disposition, Integer page) {
        BlobClient client = blobClient(blobName);
        BlobSasPermission permission = new BlobSasPermission().setReadPermission(true);
        OffsetDateTime expiresAt = OffsetDateTime.now().plusDays(Math.max(1, cloudProperty.getBlobSasExpiresInDaysForPdf()));
        BlobServiceSasSignatureValues values = new BlobServiceSasSignatureValues(expiresAt, permission)
                .setContentType("application/pdf")
                .setContentDisposition(disposition + "; filename=\"" + filename(blobName) + "\"");
        String url = client.getBlobUrl() + "?" + client.generateSas(values);
        return page == null || page < 1 ? url : url + "#page=" + page;
    }

    private List<String> sentenceSegments(String text) {
        return Pattern.compile("(?<=[.?!])\\s+").splitAsStream(text).map(String::trim).filter(this::hasText).toList();
    }

    private List<String> wordWindows(String text) {
        String[] words = text.split("\\s+");
        if (words.length <= 10) {
            return Collections.emptyList();
        }
        List<String> windows = new ArrayList<>();
        for (int i = 0; i < words.length; i += 6) {
            int end = Math.min(words.length, i + 8);
            windows.add(String.join(" ", java.util.Arrays.copyOfRange(words, i, end)));
            if (end == words.length) {
                break;
            }
        }
        return windows;
    }

    private HighlightedPdfResult emptyResult() {
        return HighlightedPdfResult.builder()
                .downloadLink("")
                .viewLink("")
                .highlightedPages(Collections.emptyList())
                .build();
    }

    private String filename(String blobName) {
        int slash = blobName.lastIndexOf('/');
        return slash >= 0 ? blobName.substring(slash + 1) : blobName;
    }

    private String normalizeWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PageLine(PDPage page, int pageNumber, String text, PDRectangle rectangle) {
    }

    private static final class LineTextStripper extends PDFTextStripper {
        private final PDPage page;
        private final int pageNumber;
        private final List<PageLine> lines = new ArrayList<>();
        private final StringBuilder currentText = new StringBuilder();
        private float minX;
        private float minY;
        private float maxX;
        private float maxY;

        private LineTextStripper(PDPage page, int pageNumber) throws IOException {
            this.page = page;
            this.pageNumber = pageNumber;
            resetLine();
        }

        private List<PageLine> lines() {
            flushLine();
            return lines;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            for (TextPosition position : textPositions) {
                if (currentText.length() > 0 && Math.abs(position.getYDirAdj() - minY) > 4.0f) {
                    flushLine();
                }
                currentText.append(position.getUnicode());
                minX = Math.min(minX, position.getXDirAdj());
                minY = Math.min(minY, position.getYDirAdj());
                maxX = Math.max(maxX, position.getXDirAdj() + position.getWidthDirAdj());
                maxY = Math.max(maxY, position.getYDirAdj() + position.getHeightDir());
            }
        }

        private void flushLine() {
            String text = currentText.toString().trim();
            if (!text.isBlank() && maxX > minX && maxY > minY) {
                PDRectangle mediaBox = page.getMediaBox();
                float lowerY = mediaBox.getHeight() - maxY;
                float upperY = mediaBox.getHeight() - minY;
                lines.add(new PageLine(page, pageNumber, text, new PDRectangle(minX, lowerY, maxX - minX, upperY - lowerY)));
            }
            resetLine();
        }

        private void resetLine() {
            currentText.setLength(0);
            minX = Float.MAX_VALUE;
            minY = Float.MAX_VALUE;
            maxX = Float.MIN_VALUE;
            maxY = Float.MIN_VALUE;
        }
    }
}
