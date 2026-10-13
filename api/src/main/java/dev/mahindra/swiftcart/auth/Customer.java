package dev.mahindra.swiftcart.auth;

import java.util.UUID;

public record Customer(UUID id, String email, String passwordHash, String name, String role) {

    public boolean admin() {
        return "ADMIN".equals(role);
    }
}
