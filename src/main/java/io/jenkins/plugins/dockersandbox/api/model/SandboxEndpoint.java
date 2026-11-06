package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Where the process/file services of a sandbox are served. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SandboxEndpoint(
        String uri, String protocol, String credentialAudience, String sandbox, String sandboxUid, String apiVersion) {}
