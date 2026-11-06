package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** One kit in a {@link CreateSandboxRequest}; the spec's only shape is {@code {"artifact": {...}}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KitInput(KitArtifactInput artifact) {

    /** A kit referenced by name, e.g. {@code shell}. */
    public static KitInput byRef(String ref) {
        return new KitInput(new KitArtifactInput(new KitRef(ref, ""), null));
    }
}
