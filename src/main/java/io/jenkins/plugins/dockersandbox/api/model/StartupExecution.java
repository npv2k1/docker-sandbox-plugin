package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** The spec's only shape is {@code {"rawImage": {"start": {"argv": [...]}}}}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StartupExecution(RawImageStartup rawImage) {

    /** Replaces the image command with the given argv. */
    public static StartupExecution rawImageStart(List<String> argv) {
        return new StartupExecution(new RawImageStartup(new StartupArgv(argv)));
    }
}
