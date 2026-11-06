package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Sandbox CPU / memory request or allocation. {@code memoryMib} is an int64 (the API may send it as a string). */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Resources(Integer cpus, Long memoryMib) {}
