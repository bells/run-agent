package cn.watsonzhu.runagent.controller;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

final class PrivateApiAccess {
    private final byte[] credential;

    PrivateApiAccess(String token) {
        if (!token.matches("[a-f0-9]{48}")) {
            throw new IllegalArgumentException("Private API requires a 48-character hexadecimal access token");
        }
        credential = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }

    boolean allows(String authorization) {
        return authorization != null && MessageDigest.isEqual(credential, authorization.getBytes(StandardCharsets.UTF_8));
    }

    static ResponseEntity<?> unauthorized(String requestId) {
        return error(401, requestId, "UNAUTHORIZED", "A valid access token is required.");
    }

    static ResponseEntity<?> error(int status, String requestId, String code, String message) {
        var response = ResponseEntity.status(status).cacheControl(CacheControl.noStore());
        if (status == 401) {
            response.header(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        }
        return response.body(new ApiError(requestId, code, message));
    }

    private record ApiError(String requestId, String code, String message) {}
}
