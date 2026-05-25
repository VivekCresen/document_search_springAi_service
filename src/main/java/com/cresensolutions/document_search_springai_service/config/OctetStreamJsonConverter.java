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

    /**
     * Constructs the message converter with standard APPLICATION_OCTET_STREAM media type registry.
     *
     * @param objectMapper injected ObjectMapper client
     */
    public OctetStreamJsonConverter(ObjectMapper objectMapper) {
        super(MediaType.APPLICATION_OCTET_STREAM);
        this.objectMapper = objectMapper;
    }

    /**
     * Resolves if the target class is supported by this converter.
     *
     * @param clazz class definition to check
     * @return true if matches FilePath
     */
    @Override
    protected boolean supports(Class<?> clazz) {
        // Only apply this converter to the FilePath class
        return clazz == FilePath.class;
    }

    /**
     * Deserializes binary stream content from input message into the target FilePath object model.
     *
     * @param clazz target class to parse
     * @param inputMessage HTTP input stream container
     * @return deserialized object instance
     * @throws IOException on input serialization errors
     * @throws HttpMessageNotReadableException on malformed input
     */
    @Override
    protected Object readInternal(Class<? extends Object> clazz, HttpInputMessage inputMessage)
             throws IOException, HttpMessageNotReadableException {
        return objectMapper.readValue(inputMessage.getBody(), clazz);
    }

    /**
     * Throws an exception as response serialization writing operations are unsupported.
     *
     * @param o object target
     * @param outputMessage output target container
     * @throws IOException on write errors
     * @throws HttpMessageNotWritableException on conversion errors
     */
    @Override
    protected void writeInternal(Object o, HttpOutputMessage outputMessage)
             throws IOException, HttpMessageNotWritableException {
        // Not used for writing
        throw new UnsupportedOperationException("Writing is not supported by OctetStreamJsonConverter");
    }
}
