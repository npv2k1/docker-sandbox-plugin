package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Short-lived (at most 5 minutes) sandbox-scoped bearer. The token must never be logged. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EndpointCredential(
        String token, String expireTime, List<String> permissions, String sandbox, String audience) {

    @Override
    public String toString() {
        return "EndpointCredential[sandbox=" + sandbox + ", expireTime=" + expireTime + ", token=****]";
    }
}
