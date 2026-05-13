package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.FilePath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

@DisplayName("FilePath Domain Tests")
class FilePathTest {

    @Test
    @DisplayName("of: creates FilePath with given segments")
    void of_createWithSegments() {
        FilePath fp = FilePath.of(Arrays.asList("CMRUS", "USA-2023-651", "ABC.png"));
        assertThat(fp.getFilePath()).containsExactly("CMRUS", "USA-2023-651", "ABC.png");
    }

    @Test
    @DisplayName("of: null list results in empty FilePath")
    void of_nullList() {
        FilePath fp = FilePath.of(null);
        assertThat(fp.getFilePath()).isEmpty();
    }

    @Test
    @DisplayName("isValid: true when at least one non-blank segment exists")
    void isValid_true() {
        assertThat(FilePath.of(List.of("folder", "file.pdf")).isValid()).isTrue();
    }

    @Test
    @DisplayName("isValid: false when all segments are blank")
    void isValid_false() {
        assertThat(FilePath.of(List.of("", "  ")).isValid()).isFalse();
        assertThat(FilePath.of(List.of()).isValid()).isFalse();
        assertThat(FilePath.of(null).isValid()).isFalse();
    }

    @Test
    @DisplayName("normalized: removes null and blank segments")
    void normalized_removesBlankAndNull() {
        FilePath fp = FilePath.of(Arrays.asList("a", "", null, "  ", "b"));
        FilePath norm = fp.normalized();
        assertThat(norm.getFilePath()).containsExactly("a", "b");
    }

    @Test
    @DisplayName("normalized: trims surrounding whitespace from each segment")
    void normalized_trimsSegments() {
        FilePath fp = FilePath.of(List.of("  hello  ", " world "));
        assertThat(fp.normalized().getFilePath()).containsExactly("hello", "world");
    }

    @Test
    @DisplayName("getFileName: returns last segment")
    void getFileName_lastSegment() {
        assertThat(FilePath.of(List.of("a", "b", "file.pdf")).getFileName()).isEqualTo("file.pdf");
    }

    @Test
    @DisplayName("getFileName: empty string for empty list")
    void getFileName_empty() {
        assertThat(FilePath.of(List.of()).getFileName()).isEmpty();
    }

    @Test
    @DisplayName("getFolderSegments: returns all segments except last")
    void getFolderSegments() {
        List<String> folders = FilePath.of(List.of("a", "b", "c", "file.pdf")).getFolderSegments();
        assertThat(folders).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("getFolderSegments: empty when only one segment")
    void getFolderSegments_singleSegment() {
        assertThat(FilePath.of(List.of("file.pdf")).getFolderSegments()).isEmpty();
    }

    @Test
    @DisplayName("toFullPath: joins segments with forward slash")
    void toFullPath_joined() {
        assertThat(FilePath.of(List.of("CMRUS", "USA-2023-651", "ABC.png")).toFullPath())
                .isEqualTo("CMRUS/USA-2023-651/ABC.png");
    }

    @Test
    @DisplayName("toFullPath: empty string for empty list")
    void toFullPath_empty() {
        assertThat(FilePath.of(List.of()).toFullPath()).isEmpty();
    }

    @Test
    @DisplayName("Full path matches company example format")
    void fullPath_companyFormat() {
        // Company example: ["CMRUS","USA-2023-651","NGRD","AB553...","Nominee","UND","LOV_DOC_CVS","ABC.png"]
        FilePath fp = FilePath.of(Arrays.asList(
                "CMRUS", "USA-2023-651", "NGRD",
                "AB553B8FF3E7F2B3A5D80BACBA78480E872013A7",
                "Nominee", "UND", "LOV_DOC_CVS", "ABC.png"));
        assertThat(fp.toFullPath())
                .isEqualTo("CMRUS/USA-2023-651/NGRD/AB553B8FF3E7F2B3A5D80BACBA78480E872013A7/Nominee/UND/LOV_DOC_CVS/ABC.png");
        assertThat(fp.getFileName()).isEqualTo("ABC.png");
        assertThat(fp.getFolderSegments()).hasSize(7);
    }
}
