package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/** Backend-neutral sandbox view (subset used by the plugin). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxCore(
        String status,
        Resources resources,
        Map<String, String> labels,
        Map<String, String> environment,
        String etag,
        String createdAt,
        SandboxEndpoint endpoint) {}
