package dev.mahindra.swiftcart.auth;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import jakarta.servlet.http.HttpServletResponse;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, TokenService tokens) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles());
        http
                .csrf(c -> c.disable()) // bearer tokens only, no cookies to forge
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/internal/scaling").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/drops", "/api/drops/*", "/api/drops/*/stock").permitAll()
                        .requestMatchers(HttpMethod.POST, "/webhooks/*").permitAll()
                        .requestMatchers("/dev/**").permitAll() // controller exists only in the dev profile
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o
                        .jwt(j -> j.decoder(tokens.decoder()).jwtAuthenticationConverter(converter))
                        .authenticationEntryPoint((req, res, e) -> problem(res, HttpStatus.UNAUTHORIZED,
                                "unauthorized", "Sign in again: the access token is missing, invalid or expired")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> problem(res, HttpStatus.UNAUTHORIZED,
                                "unauthorized", "Sign in first"))
                        .accessDeniedHandler((req, res, ex) -> problem(res, HttpStatus.FORBIDDEN,
                                "forbidden", "This account can't do that")));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(TokenService tokens) {
        return tokens.decoder();
    }

    private static Converter<Jwt, Collection<GrantedAuthority>> roles() {
        return jwt -> {
            String role = jwt.getClaimAsString("role");
            return role == null ? List.of() : List.of(new SimpleGrantedAuthority("ROLE_" + role));
        };
    }

    private static void problem(HttpServletResponse res, HttpStatus status, String code, String detail) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        if (status == HttpStatus.UNAUTHORIZED) {
            res.setHeader("WWW-Authenticate", "Bearer");
        }
        res.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + status.getReasonPhrase() + "\",\"status\":"
                + status.value() + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }
}
