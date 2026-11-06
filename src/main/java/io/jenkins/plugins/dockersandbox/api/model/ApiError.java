package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** The spec's {@code Error}: stable {@code code}, message and typed details (kept raw). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(String code, String message, List<JsonNode> details) {}
