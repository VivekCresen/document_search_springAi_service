package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.dto.auth.AuthResponse;
import com.cresensolutions.document_search_springai_service.dto.auth.LoginRequest;
import com.cresensolutions.document_search_springai_service.dto.auth.RegisterRequest;

public interface AuthService {
    AuthResponse register(RegisterRequest registerRequest);
    AuthResponse login(LoginRequest loginRequest);
}
