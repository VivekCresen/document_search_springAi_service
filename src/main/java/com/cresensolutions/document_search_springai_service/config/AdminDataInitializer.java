package com.cresensolutions.document_search_springai_service.config;

import com.cresensolutions.document_search_springai_service.domain.User;
import com.cresensolutions.document_search_springai_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Initializes default admin user if one doesn't exist.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminDataInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * Executes logic on application startup to ensure default admin user exists in database.
     *
     * @param args command line arguments
     */
    @Override
    public void run(String... args) {
        if (!userRepository.existsByUserName("admin")) {
            log.info("Creating default admin user...");
            User admin = User.builder()
                    .userName("admin")
                    .fullName("System Admin")
                    .email("admin@admin.com")
                    .password(passwordEncoder.encode("admin123"))
                    .isAdmin(true)
                    .build();
            userRepository.save(admin);
            log.info("Default admin user created successfully. Username: admin, Password: admin123");
        } else {
            log.info("Admin user already exists. Skipping creation.");
        }
    }
}
