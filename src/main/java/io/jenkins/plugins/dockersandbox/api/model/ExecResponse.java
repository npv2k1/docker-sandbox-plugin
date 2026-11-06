package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.nio.charset.StandardCharsets;

/** Result of an exec; {@code stdout}/{@code stderr} are base64 on the wire and decoded to bytes here. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecResponse(int exitCode, byte[] stdout, byte[] stderr, boolean incomplete) {

    @JsonIgnore
    public String stdoutText() {
        return stdout == null ? "" : new String(stdout, StandardCharsets.UTF_8);
    }

    @JsonIgnore
    public String stderrText() {
        return stderr == null ? "" : new String(stderr, StandardCharsets.UTF_8);
    }
}
