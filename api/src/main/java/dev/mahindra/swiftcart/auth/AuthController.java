package dev.mahindra.swiftcart.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.config.SwiftcartProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Accounts for shoppers. A limited drop needs accounts (not guest checkout) so per-customer limits mean something.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController {

    /** A real bcrypt hash of a random string: checked when the email doesn't exist, so timing doesn't reveal accounts. */
    private static final String DUMMY_HASH = new BCryptPasswordEncoder().encode("no-such-account-" + UUID.randomUUID());

    private final CustomerRepository customers;
    private final TokenService tokens;
    private final LoginThrottle throttle;
    private final SwiftcartProperties.Security security;
    private final PasswordEncoder passwords = new BCryptPasswordEncoder();

    AuthController(CustomerRepository customers, TokenService tokens, LoginThrottle throttle, SwiftcartProperties props) {
        this.customers = customers;
        this.tokens = tokens;
        this.throttle = throttle;
        this.security = props.security();
    }

    @PostMapping("/register")
    ResponseEntity<Session> register(@Valid @RequestBody Register body) {
        String email = body.email().trim();
        // bcrypt only looks at the first 72 bytes; longer passwords would be silently truncated
        if (body.password().getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("password_too_long", "Passwords can be at most 72 bytes");
        }
        if (body.password().isBlank() || body.password().chars().distinct().count() < 4) {
            throw ApiException.badRequest("password_too_weak", "Use a password with at least 4 different characters");
        }
        String role = security.isAdminEmail(email) ? "ADMIN" : "CUSTOMER";
        Customer customer = new Customer(UUID.randomUUID(), email, passwords.encode(body.password()), body.name().trim(), role);
        try {
            customers.insert(customer);
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("email_taken", "An account with this email already exists");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(session(customer));
    }

    @PostMapping("/login")
    Session login(@Valid @RequestBody Login body) {
        String email = body.email().trim();
        long wait = throttle.lockedForSeconds(email);
        if (wait > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "too_many_attempts",
                    "Too many failed logins for this account. Try again later.").retryAfter(wait);
        }
        Customer customer = customers.findByEmail(email).orElse(null);
        boolean ok = passwords.matches(body.password(), customer == null ? DUMMY_HASH : customer.passwordHash());
        if (customer == null || !ok) {
            throttle.failed(email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Email or password is wrong");
        }
        throttle.succeeded(email);
        // admins are configured by email; promote on login so a config change takes effect without a migration
        if (security.isAdminEmail(customer.email()) && !customer.admin()) {
            customers.updateRole(customer.id(), "ADMIN");
            customer = new Customer(customer.id(), customer.email(), customer.passwordHash(), customer.name(), "ADMIN");
        }
        return session(customer);
    }

    @GetMapping("/me")
    Profile me(JwtAuthenticationToken auth) {
        Customer c = customers.findById(CurrentCustomer.id(auth))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "account_missing", "This account no longer exists"));
        return Profile.of(c);
    }

    private Session session(Customer c) {
        TokenService.IssuedToken token = tokens.issue(c);
        return new Session(token.token(), token.expiresAt(), Profile.of(c));
    }

    record Register(@NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 10, max = 200) String password,
            @NotBlank @Size(max = 80) String name) {
    }

    record Login(@NotBlank @Size(max = 254) String email, @NotBlank @Size(max = 200) String password) {
    }

    record Session(String accessToken, Instant expiresAt, Profile customer) {
    }

    record Profile(UUID id, String email, String name, String role) {
        static Profile of(Customer c) {
            return new Profile(c.id(), c.email(), c.name(), c.role());
        }
    }
}
