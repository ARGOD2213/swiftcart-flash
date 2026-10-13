package dev.mahindra.swiftcart.config;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** Everything configurable, bound from {@code swiftcart.*}. Validated at startup so misconfiguration fails fast. */
@ConfigurationProperties("swiftcart")
public record SwiftcartProperties(Security security, Payments payments, Aws aws, Jobs jobs, Worker worker,
        Scaling scaling, Warehouse warehouse) {

    public SwiftcartProperties {
        requireSecret("swiftcart.security.jwt-secret", security.jwtSecret());
        requireSecret("swiftcart.security.entry-token-secret", security.entryTokenSecret());
        if (security.jwtSecret().equals(security.entryTokenSecret())) {
            throw new IllegalStateException("jwt-secret and entry-token-secret must differ, so an access token can never pass as an entry token");
        }
        payments.validate();
    }

    private static void requireSecret(String name, String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(name + " must be at least 32 bytes");
        }
    }

    public record Security(String jwtSecret, String entryTokenSecret, Duration accessTokenTtl, Duration entryTokenTtl,
            List<String> adminEmails, int loginMaxFailures, Duration loginLockout) {

        public boolean isAdminEmail(String email) {
            return adminEmails != null && adminEmails.stream()
                    .anyMatch(a -> a.trim().toLowerCase(Locale.ROOT).equals(email.trim().toLowerCase(Locale.ROOT)));
        }
    }

    public record Payments(String gateway, Razorpay razorpay, Stripe stripe, Simulator simulator, Duration staleAfter,
            Duration lateWindow) {

        void validate() {
            switch (gateway) {
                case "razorpay" -> {
                    if (!StringUtils.hasText(razorpay.keyId()) || !StringUtils.hasText(razorpay.keySecret())
                            || !StringUtils.hasText(razorpay.webhookSecret())) {
                        throw new IllegalStateException("Razorpay needs key-id, key-secret and webhook-secret");
                    }
                }
                case "stripe" -> {
                    if (!StringUtils.hasText(stripe.secretKey()) || !StringUtils.hasText(stripe.webhookSecret())) {
                        throw new IllegalStateException("Stripe needs secret-key and webhook-secret");
                    }
                }
                case "simulator" -> {
                    if (!StringUtils.hasText(simulator.webhookSecret())) {
                        throw new IllegalStateException("The simulator needs a webhook-secret");
                    }
                }
                default -> throw new IllegalStateException("swiftcart.payments.gateway must be razorpay, stripe or simulator");
            }
        }
    }

    public record Razorpay(String baseUrl, String keyId, String keySecret, String webhookSecret) {
    }

    public record Stripe(String baseUrl, String secretKey, String webhookSecret, String successUrl, String cancelUrl,
            Duration tolerance) {
    }

    public record Simulator(String webhookSecret) {
    }

    public record Aws(String region, String endpoint, String fulfilmentQueueUrl, String sesFromAddress) {
    }

    /** Background jobs (reaper, admissions, reconciler, outbox relay). On in API pods, off in workers. */
    public record Jobs(boolean enabled) {
    }

    public record Worker(boolean enabled, int maxReceives) {
    }

    /** Drop-aware pre-scaling, read by KEDA's metrics-api scaler. */
    public record Scaling(int minReplicas, int maxReplicas, int rpsPerPod, Duration lead) {
    }

    public record Warehouse(String url, String apiKey) {
    }
}
