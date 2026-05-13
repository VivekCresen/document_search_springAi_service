package com.cresensolutions.document_search_springai_service.domain;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;


@Data
@NoArgsConstructor
public class FilePath implements Serializable {

    private List<String> filePath = new ArrayList<>();

    public static FilePath of(List<String> segments) {
        FilePath fp = new FilePath();
        fp.setFilePath(segments == null ? new ArrayList<>() : new ArrayList<>(segments));
        return fp;
    }

    public boolean isValid() {
        return filePath != null && filePath.stream().anyMatch(segment -> segment != null && !segment.isBlank());
    }

    public FilePath normalized() {
        if (filePath == null) {
            return FilePath.of(Collections.emptyList());
        }
        return FilePath.of(filePath.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(segment -> !segment.isBlank())
                .toList());
    }

 
    public String getFileName() {
        if (filePath == null || filePath.isEmpty()) return "";
        return filePath.get(filePath.size() - 1);
    }

    public List<String> getFolderSegments() {
        if (filePath == null || filePath.size() <= 1) return List.of();
        return filePath.subList(0, filePath.size() - 1);
    }
    
    public String toFullPath() {
        if (filePath == null || filePath.isEmpty()) return "";
        return String.join("/", filePath);
    }
}
