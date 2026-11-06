package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Body of {@code POST /v1/processes/{id}/signal}; {@code signal} is one of term, kill, int, hup, quit, usr1, usr2. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SignalRequest(String signal) {}
