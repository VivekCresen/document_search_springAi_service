package com.cresensolutions.document_search_springai_service.controller;

import com.cresensolutions.document_search_springai_service.commons.Common;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckRequest;
import com.cresensolutions.document_search_springai_service.dto.PermissionCheckResponse;
import com.cresensolutions.document_search_springai_service.service.UserAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST Controller providing operations to check, view, and clear user permissions and folder restrictions.
 */
@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final UserAccessService userAccessService;

    /**
     * Checks if a user has permissions for a set of file IDs and folder IDs.
     *
     * @param request permission evaluation payload containing files and folders
     * @param username request context username
     * @param email request context email
     * @return PermissionCheckResponse indicating allowed/denied resources
     */
    @PostMapping("/check")
    public PermissionCheckResponse checkPermissions(
            @RequestBody PermissionCheckRequest request,
            @RequestHeader(value = Common.HEADER_X_USERNAME, required = false) String username,
            @RequestHeader(value = Common.HEADER_X_USER_EMAIL, required = false) String email
    ) {
        return userAccessService.checkPermissions(userAccessService.resolveCurrentUser(username, email), request);
    }

    /**
     * Resolves currently active access profile details and restricted folders for the requester.
     *
     * @param username request context username
     * @param email request context email
     * @return access specifications map
     */
    @GetMapping("/my-access")
    public Map<String, Object> myAccess(
            @RequestHeader(value = Common.HEADER_X_USERNAME, required = false) String username,
            @RequestHeader(value = Common.HEADER_X_USER_EMAIL, required = false) String email
    ) {
        return userAccessService.getMyAccess(userAccessService.resolveCurrentUser(username, email));
    }

    /**
     * Clears cached permission profiles for the currently authenticated user session.
     *
     * @param username request context username
     * @param email request context email
     * @return response indicating clearing status map
     */
    @PostMapping("/clear-cache")
    public ResponseEntity<Map<String, Object>> clearPermissionCache(
            @RequestHeader(value = Common.HEADER_X_USERNAME, required = false) String username,
            @RequestHeader(value = Common.HEADER_X_USER_EMAIL, required = false) String email
    ) {
        return ResponseEntity.ok(userAccessService.clearPermissionCache(
                userAccessService.resolveCurrentUser(username, email)
        ));
    }
}
