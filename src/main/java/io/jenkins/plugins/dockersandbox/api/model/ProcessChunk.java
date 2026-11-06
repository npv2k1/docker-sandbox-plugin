package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Output bytes of one stream ({@code stdout}, {@code stderr} or {@code pty}); {@code data} is base64 on the wire. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProcessChunk(String stream, byte[] data, Long streamSequence) {}
