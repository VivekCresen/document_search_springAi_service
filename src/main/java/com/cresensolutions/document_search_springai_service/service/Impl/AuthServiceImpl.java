package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.domain.User;
import com.cresensolutions.document_search_springai_service.dto.auth.AuthResponse;
import com.cresensolutions.document_search_springai_service.dto.auth.LoginRequest;
import com.cresensolutions.document_search_springai_service.dto.auth.RegisterRequest;
import com.cresensolutions.document_search_springai_service.repository.UserRepository;
import com.cresensolutions.document_search_springai_service.security.CustomUserDetails;
import com.cresensolutions.document_search_springai_service.security.JwtUtils;
import com.cresensolutions.document_search_springai_service.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    @Override
    public AuthResponse register(RegisterRequest registerRequest) {
        if (userRepository.existsByUserName(registerRequest.getUserName())) {
            throw new RuntimeException("Error: Username is already taken!");
        }

        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new RuntimeException("Error: Email is already in use!");
        }

        // Create new user's account
        User user = User.builder()
                .userName(registerRequest.getUserName())
                .fullName(registerRequest.getFullName())
                .email(registerRequest.getEmail())
                .password(passwordEncoder.encode(registerRequest.getPassword()))
                .build();

        userRepository.save(user);

        return AuthResponse.builder()
                .message("User registered successfully!")
                .build();
    }

    @Override
    public AuthResponse login(LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUserNameOrEmail(), loginRequest.getPassword()));

        SecurityContextHolder.getContext().setAuthentication(authentication);
        String jwt = jwtUtils.generateJwtToken(authentication);

        CustomUserDetails userDetails = (CustomUserDetails) authentication.getPrincipal();

        return AuthResponse.builder()
                .token(jwt)
                .id(userDetails.getId())
                .userName(userDetails.getUsername())
                .email(userDetails.getEmail())
                .message("User logged in successfully!")
                .build();
    }
}
