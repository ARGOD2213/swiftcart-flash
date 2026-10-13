package dev.mahindra.swiftcart.auth;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import dev.mahindra.swiftcart.config.SwiftcartProperties;

/**
 * Locks an email out after N failed logins for a while, so passwords can't be brute-forced one account at a time.
 * (Spraying from many IPs is the WAF's job.) Keyed by email, not IP, so a shared office IP doesn't lock everyone.
 */
@Component
public class LoginThrottle {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);

    private final StringRedisTemplate redis;
    private final SwiftcartProperties.Security security;

    public LoginThrottle(StringRedisTemplate redis, SwiftcartProperties props) {
        this.redis = redis;
        this.security = props.security();
    }

    /** Seconds the caller must wait, or 0 if a login attempt is allowed. */
    public long lockedForSeconds(String email) {
        try {
            String failures = redis.opsForValue().get(key(email));
            if (failures != null && Integer.parseInt(failures) >= security.loginMaxFailures()) {
                Long ttl = redis.getExpire(key(email), TimeUnit.SECONDS);
                return ttl == null || ttl < 1 ? 1 : ttl;
            }
            return 0;
        } catch (DataAccessException e) {
            log.warn("Login throttle unavailable: {}", e.getMessage());
            return 0;
        }
    }

    public void failed(String email) {
        try {
            Long count = redis.opsForValue().increment(key(email));
            if (count != null && count == 1) {
                redis.expire(key(email), security.loginLockout());
            }
        } catch (DataAccessException e) {
            log.warn("Login throttle unavailable: {}", e.getMessage());
        }
    }

    public void succeeded(String email) {
        try {
            redis.delete(key(email));
        } catch (DataAccessException e) {
            log.warn("Login throttle unavailable: {}", e.getMessage());
        }
    }

    private static String key(String email) {
        return "sc:login:" + email.trim().toLowerCase(Locale.ROOT);
    }
}
