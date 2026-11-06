package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** A sandbox as returned by create/get/list. {@code name} is {@code sandboxes/<id>}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Sandbox(String name, String uid, String displayName, SandboxCore core, ApiError failure) {

    public static final String RUNNING = "running";
    public static final String FAILED = "failed";

    /** The immutable id: the last segment of {@code name}, falling back to {@code uid}. */
    @JsonIgnore
    public String id() {
        if (name != null && !name.isEmpty()) {
            return name.substring(name.lastIndexOf('/') + 1);
        }
        return uid;
    }

    @JsonIgnore
    public String status() {
        return core == null || core.status() == null ? "" : core.status().toLowerCase(java.util.Locale.ROOT);
    }

    @JsonIgnore
    public String endpointUri() {
        return core == null || core.endpoint() == null ? null : core.endpoint().uri();
    }
}
