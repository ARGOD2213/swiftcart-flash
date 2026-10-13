package dev.mahindra.swiftcart.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A client-visible failure with a stable machine-readable {@code code}. Rendered as RFC 7807 problem+json by
 * {@link ApiExceptionHandler}. Clients switch on {@code code}, never on the human-readable detail.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> extras = new LinkedHashMap<>();
    private Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public ApiException with(String key, Object value) {
        extras.put(key, value);
        return this;
    }

    public ApiException retryAfter(long seconds) {
        this.retryAfterSeconds = Math.max(1, seconds);
        return this;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> extras() {
        return extras;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }

    public static ApiException notFound(String code, String detail) {
        return new ApiException(HttpStatus.NOT_FOUND, code, detail);
    }

    public static ApiException conflict(String code, String detail) {
        return new ApiException(HttpStatus.CONFLICT, code, detail);
    }

    public static ApiException unavailable(String code, String detail) {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, detail).retryAfter(2);
    }
}
