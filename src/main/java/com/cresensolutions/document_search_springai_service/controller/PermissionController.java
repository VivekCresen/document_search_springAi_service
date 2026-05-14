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

@RestController
@RequestMapping("/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final UserAccessService userAccessService;

    @PostMapping("/check")
    public PermissionCheckResponse checkPermissions(
            @RequestBody PermissionCheckRequest request,
            @RequestHeader(value = Common.HEADER_X_USERNAME, required = false) String username,
            @RequestHeader(value = Common.HEADER_X_USER_EMAIL, required = false) String email
    ) {
        return userAccessService.checkPermissions(userAccessService.resolveCurrentUser(username, email), request);
    }

    @GetMapping("/my-access")
    public Map<String, Object> myAccess(
            @RequestHeader(value = Common.HEADER_X_USERNAME, required = false) String username,
            @RequestHeader(value = Common.HEADER_X_USER_EMAIL, required = false) String email
    ) {
        return userAccessService.getMyAccess(userAccessService.resolveCurrentUser(username, email));
    }

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
