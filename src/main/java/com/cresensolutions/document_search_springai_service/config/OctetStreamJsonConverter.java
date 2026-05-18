package com.cresensolutions.document_search_springai_service.config;

import com.cresensolutions.document_search_springai_service.domain.FilePath;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Custom HTTP Message Converter to support deserializing application/octet-stream 
 * parts into FilePath JSON objects during multipart/form-data requests.
 * This resolves issues with clients (like Postman) that do not explicitly set 
 * the Content-Type of text parts to application/json.
 */
@Component
public class OctetStreamJsonConverter extends AbstractHttpMessageConverter<Object> {

    private final ObjectMapper objectMapper;

    public OctetStreamJsonConverter(ObjectMapper objectMapper) {
        super(MediaType.APPLICATION_OCTET_STREAM);
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        // Only apply this converter to the FilePath class
        return clazz == FilePath.class;
    }

    @Override
    protected Object readInternal(Class<? extends Object> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        return objectMapper.readValue(inputMessage.getBody(), clazz);
    }

    @Override
    protected void writeInternal(Object o, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        // Not used for writing
        throw new UnsupportedOperationException("Writing is not supported by OctetStreamJsonConverter");
    }
}
