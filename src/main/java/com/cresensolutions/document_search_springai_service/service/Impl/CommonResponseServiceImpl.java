package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.service.CommonResponseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

@Service
@Slf4j
public class CommonResponseServiceImpl implements CommonResponseService {

    private final Map<String, Supplier<String>> staticResponses = new HashMap<>();

    public CommonResponseServiceImpl() {
        // Greetings
        staticResponses.put("hi", () -> "Hello! How can I help you today?");
        staticResponses.put("hello", () -> "Hi there! I'm ready to assist with your documents and data.");
        staticResponses.put("hey", () -> "Hello! What can I do for you?");
        staticResponses.put("good morning", () -> "Good morning! How can I help you start your day?");
        staticResponses.put("good afternoon", () -> "Good afternoon! How can I assist you?");
        staticResponses.put("good evening", () -> "Good evening! I'm here if you need any help.");

        // Identity
        staticResponses.put("who are you", () -> "I am your AI assistant, specialized in searching through your organization's documents and database.");
        staticResponses.put("what is your name", () -> "I'm your enterprise AI assistant. You can call me assistant.");
        staticResponses.put("what can you do", () -> "I can search through your PDFs, answer policy questions, and query your database for specific records.");
        staticResponses.put("how can you help", () -> "I can help you find information in documents or get data from your database quickly.");

        // Date and Time
        staticResponses.put("what is the date", () -> "Today's date is " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")));
        staticResponses.put("what is date", () -> "The current date is " + LocalDate.now().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")));
        staticResponses.put("today's date", () -> "Today is " + LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy")));
        staticResponses.put("what time is it", () -> "The current time is " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
        staticResponses.put("current time", () -> "It is currently " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")));

        // Polite closures
        staticResponses.put("thank you", () -> "You're very welcome! Let me know if you need anything else.");
        staticResponses.put("thanks", () -> "Happy to help! Anything else?");
        staticResponses.put("bye", () -> "Goodbye! Have a great day.");
        staticResponses.put("exit", () -> "Goodbye! Feel free to return if you have more questions.");
    }

    @Override
    public Optional<String> getCommonResponse(String question) {
        if (question == null || question.isBlank()) {
            return Optional.empty();
        }

        // Normalize the question: lowercase, remove punctuation at the end, and trim
        String normalized = question.toLowerCase(Locale.ROOT)
                .replaceAll("[?.!]$", "")
                .trim();

        log.debug("Checking common response for normalized question: '{}'", normalized);
        
        Supplier<String> responseSupplier = staticResponses.get(normalized);
        if (responseSupplier != null) {
            return Optional.of(responseSupplier.get());
        }

        // Handle some variations manually if needed
        if (normalized.contains("date") && (normalized.startsWith("what") || normalized.contains("today"))) {
             return Optional.of(staticResponses.get("what is the date").get());
        }
        
        if (normalized.equals("hi") || normalized.equals("hello") || normalized.equals("hey")) {
             return Optional.of(staticResponses.get(normalized).get());
        }

        return Optional.empty();
    }
}
