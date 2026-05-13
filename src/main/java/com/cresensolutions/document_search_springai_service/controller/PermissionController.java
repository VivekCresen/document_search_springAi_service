package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.dto.PermissionCheckRequest;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckResponse;
import com.cresensolutions.document_search_springai_service.service.UserAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private static final String X_USERNAME = "X-Username";
    private static final String X_USER_EMAIL = "X-User-Email";

    private final UserAccessService userAccessService;

    @PostMapping("/check")
    public PermissionCheckResponse checkPermissions(
            @RequestBody PermissionCheckRequest request,
            @RequestHeader HttpHeaders headers
    ) {
        String username = currentUser(headers);
        List<String> folderIds = request.getFolderIds() == null ? List.of() : request.getFolderIds();
        Map<String, Boolean> permissions = folderIds.stream()
                .collect(Collectors.toMap(
                        folderId -> folderId,
                        folderId -> userAccessService.hasAccessToFolder(username, folderId),
                        (first, second) -> first
                ));
        int accessibleCount = (int) permissions.values().stream().filter(Boolean::booleanValue).count();

        return PermissionCheckResponse.builder()
                .username(username)
                .permissions(permissions)
                .accessibleCount(accessibleCount)
                .restrictedCount(permissions.size() - accessibleCount)
                .build();
    }

    @GetMapping("/my-access")
    public Map<String, Object> myAccess(@RequestHeader HttpHeaders headers) {
        String username = currentUser(headers);
        List<String> restrictedFolders = userAccessService.getRestrictedFolders(username).stream()
                .sorted(Comparator.naturalOrder())
                .toList();

        return Map.of(
                "username", username,
                "total_restricted_folders", restrictedFolders.size(),
                "restricted_folder_ids", restrictedFolders,
                "note", "You can access all folders except those listed above"
        );
    }

    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearPermissionCache(@RequestHeader HttpHeaders headers) {
        String username = currentUser(headers);
        // Match Python behavior: clear only the requesting user's cache entry
        userAccessService.clearUserRestrictionsCache(username);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Permission cache cleared for user: " + username,
                "username", username
        ));
    }

    private String currentUser(HttpHeaders headers) {
        String username = firstHeader(headers, X_USERNAME);
        if (username != null && !username.isBlank()) {
            return username;
        }
        String email = firstHeader(headers, X_USER_EMAIL);
        if (email != null && !email.isBlank()) {
            return email;
        }
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authentication required. Provide X-Username or X-User-Email header."
        );
    }

    private String firstHeader(HttpHeaders headers, String name) {
        List<String> values = headers.get(name);
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
