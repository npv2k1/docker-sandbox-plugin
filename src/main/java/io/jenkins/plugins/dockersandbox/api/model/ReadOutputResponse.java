package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** One page of ordered process output; {@code exited} is set once the process ended and no output follows. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReadOutputResponse(List<ProcessChunk> chunks, Long nextResumeFrom, ProcessExited exited) {}
