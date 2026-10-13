package dev.mahindra.swiftcart.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import dev.mahindra.swiftcart.config.SwiftcartProperties;

/** Issues and verifies customer access tokens (HS256). Entry tokens use a different key and type. */
@Component
public class TokenService {

    public static final String ISSUER = "swiftcart";
    public static final String TYPE_ACCESS = "access";

    private final NimbusJwtEncoder encoder;
    private final NimbusJwtDecoder decoder;
    private final SwiftcartProperties.Security security;
    private final Clock clock;

    public TokenService(SwiftcartProperties props, Clock clock) {
        this.security = props.security();
        this.clock = clock;
        SecretKey key = new SecretKeySpec(security.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        this.decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(ISSUER),
                new JwtClaimValidator<String>("typ", TYPE_ACCESS::equals)));
    }

    public IssuedToken issue(Customer customer) {
        Instant now = clock.instant();
        Instant expires = now.plus(security.accessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(customer.id().toString())
                .issuedAt(now)
                .expiresAt(expires)
                .id(UUID.randomUUID().toString())
                .claim("typ", TYPE_ACCESS)
                .claim("email", customer.email())
                .claim("name", customer.name())
                .claim("role", customer.role())
                .build();
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new IssuedToken(token, expires);
    }

    public JwtDecoder decoder() {
        return decoder;
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
