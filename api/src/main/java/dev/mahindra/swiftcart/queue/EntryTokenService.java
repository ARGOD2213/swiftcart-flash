package dev.mahindra.swiftcart.queue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import dev.mahindra.swiftcart.api.ApiException;
import dev.mahindra.swiftcart.config.SwiftcartProperties;

/**
 * Signed proof that a customer got through the waiting room for one drop. Checkout refuses requests without one,
 * so nobody (and no bot) can skip the line by calling the checkout API directly. The token names the customer
 * and the drop, so it can't be shared or reused for another drop. Signed with its own key, separate from access
 * tokens.
 */
@Component
public class EntryTokenService {

    static final String TYPE = "entry";

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final SwiftcartProperties.Security security;
    private final Clock clock;

    public EntryTokenService(SwiftcartProperties props, Clock clock) {
        this.security = props.security();
        this.clock = clock;
        SecretKey key = new SecretKeySpec(security.entryTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // no clock skew allowance beyond the default; expiry is what bounds a stolen token
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(),
                new JwtIssuerValidator("swiftcart"),
                new JwtClaimValidator<String>("typ", TYPE::equals)));
    }

    public Issued issue(UUID customer, UUID drop, long ticket) {
        Instant now = clock.instant();
        Instant expires = now.plus(security.entryTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("swiftcart")
                .subject(customer.toString())
                .issuedAt(now)
                .expiresAt(expires)
                .claim("typ", TYPE)
                .claim("drop", drop.toString())
                .claim("ticket", ticket)
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new Issued(token, expires);
    }

    /** Throws 401 for a missing, forged or expired token and 403 for someone else's or another drop's token. */
    public void verify(String token, UUID customer, UUID drop) {
        if (token == null || token.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "entry_token_missing",
                    "Join the waiting room first: checkout needs the entry token you get when it's your turn");
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(token);
        } catch (BadJwtException e) {
            boolean expired = e.getMessage() != null && e.getMessage().contains("expired");
            throw new ApiException(HttpStatus.UNAUTHORIZED, expired ? "entry_token_expired" : "entry_token_invalid",
                    expired ? "Your checkout window has closed; rejoin the waiting room" : "The entry token is not valid");
        } catch (JwtException e) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "entry_token_invalid", "The entry token is not valid");
        }
        if (!customer.toString().equals(jwt.getSubject()) || !drop.toString().equals(jwt.getClaimAsString("drop"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "entry_token_mismatch", "This entry token belongs to another shopper or drop");
        }
    }

    public record Issued(String token, Instant expiresAt) {
    }
}
