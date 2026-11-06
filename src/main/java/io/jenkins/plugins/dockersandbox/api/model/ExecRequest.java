package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Body of {@code POST /v1/processes/exec}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExecRequest(List<String> cmd, Map<String, String> env, String workingDir, String user) {

    public static ExecRequest of(String... cmd) {
        return new ExecRequest(List.of(cmd), null, null, null);
    }
}
