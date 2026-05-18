package com.cresensolutions.document_search_springai_service.service;

import com.cresensolutions.document_search_springai_service.domain.User;
import com.cresensolutions.document_search_springai_service.dto.auth.AuthResponse;
import com.cresensolutions.document_search_springai_service.dto.auth.LoginRequest;
import com.cresensolutions.document_search_springai_service.dto.auth.RegisterRequest;
import com.cresensolutions.document_search_springai_service.exception.UserAlreadyExistsException;
import com.cresensolutions.document_search_springai_service.repository.UserRepository;
import com.cresensolutions.document_search_springai_service.security.CustomUserDetails;
import com.cresensolutions.document_search_springai_service.service.Impl.AuthServiceImpl;
import com.cresensolutions.document_search_springai_service.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtUtils jwtUtils;

    @InjectMocks
    private AuthServiceImpl authService;

    private RegisterRequest registerRequest;
    private LoginRequest loginRequest;

    @BeforeEach
    void setUp() {
        registerRequest = new RegisterRequest();
        registerRequest.setUserName("testUser");
        registerRequest.setFullName("Test User");
        registerRequest.setEmail("test@example.com");
        registerRequest.setPassword("password123");

        loginRequest = new LoginRequest();
        loginRequest.setUserNameOrEmail("testUser");
        loginRequest.setPassword("password123");
    }

    @Test
    void register_Success() {
        when(userRepository.existsByUserName(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encodedPassword");
        when(userRepository.save(any(User.class))).thenReturn(new User());

        AuthResponse response = authService.register(registerRequest);

        assertNotNull(response);
        assertEquals("User registered successfully!", response.getMessage());
        verify(userRepository).save(any(User.class));
    }

    @Test
    void register_ThrowsUserAlreadyExistsException_WhenUsernameExists() {
        when(userRepository.existsByUserName(anyString())).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () -> authService.register(registerRequest));
        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void register_ThrowsUserAlreadyExistsException_WhenEmailExists() {
        when(userRepository.existsByUserName(anyString())).thenReturn(false);
        when(userRepository.existsByEmail(anyString())).thenReturn(true);

        assertThrows(UserAlreadyExistsException.class, () -> authService.register(registerRequest));
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void login_Success() {
        Authentication authentication = mock(Authentication.class);
        CustomUserDetails userDetails = mock(CustomUserDetails.class);

        when(authenticationManager.authenticate(any(UsernamePasswordAuthenticationToken.class)))
                .thenReturn(authentication);
        when(jwtUtils.generateJwtToken(authentication)).thenReturn("mockJwtToken");
        when(authentication.getPrincipal()).thenReturn(userDetails);
        
        UUID mockId = UUID.randomUUID();
        when(userDetails.getId()).thenReturn(mockId);
        when(userDetails.getUsername()).thenReturn("testUser");
        when(userDetails.getEmail()).thenReturn("test@example.com");

        AuthResponse response = authService.login(loginRequest);

        assertNotNull(response);
        assertEquals("mockJwtToken", response.getToken());
        assertEquals(mockId, response.getId());
        assertEquals("testUser", response.getUserName());
        assertEquals("test@example.com", response.getEmail());
        assertEquals("User logged in successfully!", response.getMessage());
        
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
        verify(jwtUtils).generateJwtToken(authentication);
    }
}
