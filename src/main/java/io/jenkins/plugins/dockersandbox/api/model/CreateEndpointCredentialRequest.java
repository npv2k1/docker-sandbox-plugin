package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Body of {@code POST /v1/sandboxes/{id}/endpoint-credentials}; {@code ttl} is a duration such as {@code "300s"}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateEndpointCredentialRequest(List<String> permissions, String ttl) {}
