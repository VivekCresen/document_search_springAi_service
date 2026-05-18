package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.service.Impl.CommonResponseServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CommonResponseServiceImplTest {

    private CommonResponseServiceImpl commonResponseService;

    @BeforeEach
    void setUp() {
        commonResponseService = new CommonResponseServiceImpl();
    }

    @Test
    void getCommonResponse_ReturnsEmpty_ForNullOrBlank() {
        assertTrue(commonResponseService.getCommonResponse(null).isEmpty());
        assertTrue(commonResponseService.getCommonResponse("").isEmpty());
        assertTrue(commonResponseService.getCommonResponse("   ").isEmpty());
    }

    @Test
    void getCommonResponse_ReturnsGreeting() {
        Optional<String> response = commonResponseService.getCommonResponse("Hi!");
        assertTrue(response.isPresent());
        assertEquals("Hello! How can I help you today?", response.get());
        
        Optional<String> responseHello = commonResponseService.getCommonResponse("HELLO?");
        assertTrue(responseHello.isPresent());
        assertEquals("Hi there! I'm ready to assist with your documents and data.", responseHello.get());
    }

    @Test
    void getCommonResponse_ReturnsDate() {
        Optional<String> response = commonResponseService.getCommonResponse("what is the date?");
        assertTrue(response.isPresent());
        assertTrue(response.get().contains(LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy"))));
        
        // Custom check logic branch
        Optional<String> responseToday = commonResponseService.getCommonResponse("today date");
        assertTrue(responseToday.isPresent());
        assertTrue(responseToday.get().contains(LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy"))));
    }

    @Test
    void getCommonResponse_ReturnsTime() {
        Optional<String> response = commonResponseService.getCommonResponse("What time is it?");
        assertTrue(response.isPresent());
        assertTrue(response.get().startsWith("The current time is "));
    }

    @Test
    void getCommonResponse_ReturnsIdentity() {
        Optional<String> response = commonResponseService.getCommonResponse("who are you?");
        assertTrue(response.isPresent());
        assertEquals("I am your AI assistant, specialized in searching through your organization's documents and database.", response.get());
    }
    
    @Test
    void getCommonResponse_ReturnsEmpty_ForUnrecognized() {
        Optional<String> response = commonResponseService.getCommonResponse("what is the meaning of life?");
        assertTrue(response.isEmpty());
    }
}
