package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.security.CustomUserDetails;
import com.cresensolutions.document_search_springai_service.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST Controller for managing chat history database persistence.
 * Exposes endpoints to retrieve and clear chat history for the logged-in user.
 */
@RestController
@RequestMapping("/api/chat-history")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * Retrieves the chat history conversations map for the logged-in user.
     *
     * @return the conversations map retrieved from Postgres
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getChatHistory() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        Map<String, Object> conversations = chatHistoryService.getConversations(userDetails.getId());
        return ResponseEntity.ok(conversations);
    }

    /**
     * Clears all conversation history from PostgreSQL for the logged-in user.
     *
     * @return 200 OK
     */
    @DeleteMapping
    public ResponseEntity<Void> clearChatHistory() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof CustomUserDetails userDetails)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        chatHistoryService.clearConversations(userDetails.getId());
        return ResponseEntity.ok().build();
    }
}
