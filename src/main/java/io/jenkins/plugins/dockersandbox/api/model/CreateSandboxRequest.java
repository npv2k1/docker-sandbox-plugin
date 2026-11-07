package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/** Body of {@code POST /v1/sandboxes}. Unknown fields are rejected by the server, so only spec fields appear here. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CreateSandboxRequest(
        String displayName,
        Resources resources,
        List<KitInput> kits,
        Map<String, String> environment,
        Map<String, String> labels,
        FeatureOptions features,
        String image,
        String imageRef,
        StartupExecution startupExecution) {

    /** Kit-mode request (no image). */
    public CreateSandboxRequest(
            String displayName,
            Resources resources,
            List<KitInput> kits,
            Map<String, String> environment,
            Map<String, String> labels,
            FeatureOptions features) {
        this(displayName, resources, kits, environment, labels, features, null, null, null);
    }
}
