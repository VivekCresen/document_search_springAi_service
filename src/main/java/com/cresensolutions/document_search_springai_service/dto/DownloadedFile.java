package com.cresensolutions.document_search_springai_service.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DownloadedFile {

    private String filename;
    private String contentDisposition;
    private byte[] content;

    public boolean hasContent() {
        return content != null && content.length > 0;
    }
}
