package com.cresensolutions.document_search_springai_service.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {
    @NotBlank
    private String userNameOrEmail;

    @NotBlank
    private String password;
}
