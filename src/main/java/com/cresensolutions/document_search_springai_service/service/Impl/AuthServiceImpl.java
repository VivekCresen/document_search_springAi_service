package com.cresensolutions.document_search_springai_service.service.Impl;

import com.cresensolutions.document_search_springai_service.domain.User;
import com.cresensolutions.document_search_springai_service.dto.auth.AuthResponse;
import com.cresensolutions.document_search_springai_service.dto.auth.LoginRequest;
import com.cresensolutions.document_search_springai_service.dto.auth.RegisterRequest;
import com.cresensolutions.document_search_springai_service.exception.UserAlreadyExistsException;
import com.cresensolutions.document_search_springai_service.repository.UserRepository;
import com.cresensolutions.document_search_springai_service.security.CustomUserDetails;
import com.cresensolutions.document_search_springai_service.utils.JwtUtils;
import com.cresensolutions.document_search_springai_service.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Implementation of AuthService for handling user registration and login.
 */
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtils jwtUtils;

    /**
     * Registers a new user. Performs validation on username and email uniqueness.
     *
     * @param registerRequest the registration details
     * @return AuthResponse containing success message
     * @throws UserAlreadyExistsException if username or email already exists
     */
    @Override
    public AuthResponse register(RegisterRequest registerRequest) {
        // Validate uniqueness of username
        if (userRepository.existsByUserName(registerRequest.getUserName())) {
            throw new UserAlreadyExistsException("Username is already taken!");
        }

        // Validate uniqueness of email
        if (userRepository.existsByEmail(registerRequest.getEmail())) {
            throw new UserAlreadyExistsException("Email is already in use!");
        }

        // Create new user's account with encoded password
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

    /**
     * Authenticates a user and returns a JWT token.
     *
     * @param loginRequest the login credentials
     * @return AuthResponse containing JWT and user profile
     */
    @Override
    public AuthResponse login(LoginRequest loginRequest) {
        // Authenticate the user
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(loginRequest.getUserNameOrEmail(), loginRequest.getPassword()));

        // Set security context
        SecurityContextHolder.getContext().setAuthentication(authentication);
        
        // Generate JWT token
        String jwt = jwtUtils.generateJwtToken(authentication);

        // Extract user details from principal
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
