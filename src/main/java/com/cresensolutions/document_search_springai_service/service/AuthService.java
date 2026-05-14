package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.auth.AuthResponse;
import com.cresensolutions.document_search_springai_service.dto.auth.LoginRequest;
import com.cresensolutions.document_search_springai_service.dto.auth.RegisterRequest;

/**
 * Service interface for authentication operations.
 */
public interface AuthService {
    /**
     * Registers a new user in the system.
     *
     * @param registerRequest the registration details
     * @return AuthResponse containing registration status
     */
    AuthResponse register(RegisterRequest registerRequest);

    /**
     * Authenticates a user and generates a JWT token.
     *
     * @param loginRequest the login credentials
     * @return AuthResponse containing the JWT token and user details
     */
    AuthResponse login(LoginRequest loginRequest);
}
