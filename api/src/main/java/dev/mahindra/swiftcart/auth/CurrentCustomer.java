package dev.mahindra.swiftcart.auth;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import dev.mahindra.swiftcart.api.ApiException;

public final class CurrentCustomer {

    private CurrentCustomer() {
    }

    public static UUID id(JwtAuthenticationToken auth) {
        if (auth == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "Sign in first");
        }
        try {
            return UUID.fromString(auth.getToken().getSubject());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "The access token has no valid subject");
        }
    }
}
