package com.cresensolutions.document_search_springai_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

/**
 * Main application class for the Document Search Spring AI Service.
 * This is a Spring Boot application that provides document search capabilities
 * using Azure OpenAI and Azure Blob Storage for document indexing and retrieval.
 *
 * Features:
 * - Document upload and indexing
 * - AI-powered document querying using Azure OpenAI
 * - User access control and permissions
 * - Chat history management
 * - Integration with Eureka service discovery
 */
@SpringBootApplication
@EnableDiscoveryClient
public class DocumentSearchSpringAiServiceApplication {

    /**
     * Main method to start the Spring Boot application.
     * This method bootstraps the application context and starts the embedded Tomcat server.
     *
     * @param args command line arguments passed to the application
     */
    public static void main(String[] args) {
        SpringApplication.run(DocumentSearchSpringAiServiceApplication.class, args);
    }

}
