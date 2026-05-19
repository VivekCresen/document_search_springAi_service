package com.cresensolutions.document_search_springai_service.dto.auth;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AuthResponse {
    private String token;
    private UUID id;
    private String userName;
    private String email;
    private boolean isAdmin;
    private List<String> roles;
    private String message;
}
