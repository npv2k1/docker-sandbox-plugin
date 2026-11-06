package io.jenkins.plugins.dockersandbox.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jenkins.plugins.dockersandbox.api.model.ApiError;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

/** Error returned by the Docker Sandboxes API (or the token endpoint). Never carries secrets. */
public class SandboxApiException extends IOException {

    private static final long serialVersionUID = 1L;
    private static final int MAX_BODY_CHARS = 300;

    private final int httpStatus;
    private final String code;
    private final long retryAfterMillis;

    public SandboxApiException(int httpStatus, String code, String message, long retryAfterMillis) {
        super("HTTP " + httpStatus + (code.isEmpty() ? "" : " " + code) + ": " + message);
        this.httpStatus = httpStatus;
        this.code = code;
        this.retryAfterMillis = retryAfterMillis;
    }

    /** Builds an exception from an error response; {@code retryAfter} is the raw header value or null. */
    static SandboxApiException fromResponse(int status, byte[] body, String retryAfter) {
        String code = "";
        String message = null;
        if (body != null && body.length > 0) {
            try {
                ApiError err = new ObjectMapper().readValue(body, ApiError.class);
                if (err != null) {
                    code = normalize(err.code());
                    message = err.message();
                }
            } catch (IOException | RuntimeException e) {
                // not JSON: fall back to a truncated raw body below
            }
            if (message == null) {
                String raw = new String(body, StandardCharsets.UTF_8).trim();
                message = raw.length() > MAX_BODY_CHARS ? raw.substring(0, MAX_BODY_CHARS) + "..." : raw;
            }
        }
        if (message == null || message.isEmpty()) {
            message = "no error body";
        }
        return new SandboxApiException(status, code, message, parseRetryAfter(retryAfter));
    }

    /** Error codes are matched case-insensitively and without underscores ({@code RESOURCE_EXHAUSTED} == {@code resourceExhausted}). */
    static String normalize(String code) {
        return code == null ? "" : code.replace("_", "").toLowerCase(Locale.ROOT);
    }

    private static long parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return -1;
        }
        try {
            double seconds = Double.parseDouble(header.trim());
            return Math.max(0, (long) (seconds * 1000));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /** Normalized (lower-case, no underscores) error code, or empty. */
    public String getCode() {
        return code;
    }

    /** Server-requested wait, or {@code null} when none was sent. */
    public Duration getRetryAfter() {
        return retryAfterMillis < 0 ? null : Duration.ofMillis(retryAfterMillis);
    }

    public boolean isQuotaExhausted() {
        return httpStatus == 429 || "resourceexhausted".equals(code);
    }

    public boolean isAuthError() {
        return httpStatus == 401
                || httpStatus == 403
                || "unauthenticated".equals(code)
                || "permissiondenied".equals(code);
    }

    public boolean isNotFound() {
        return httpStatus == 404 || "notfound".equals(code);
    }

    /** Whether the same request may be retried: 502/503/504, and 429 unless it is a plain quota refusal. */
    public boolean retryable() {
        if (httpStatus == 502 || httpStatus == 503 || httpStatus == 504) {
            return true;
        }
        if (httpStatus == 429) {
            return retryAfterMillis >= 0 || !"resourceexhausted".equals(code);
        }
        return false;
    }
}
