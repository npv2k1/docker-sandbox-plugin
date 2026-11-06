package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import com.fasterxml.jackson.annotation.JsonIgnore;

/** Handle of a started process. {@code name} is scoped to the endpoint; the id is its last path segment. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Process(String uid, Integer pid, String state, Integer exitCode, String label, String name) {

    @JsonIgnore
    public String id() {
        if (uid != null && !uid.isEmpty()) {
            return uid;
        }
        if (name != null && !name.isEmpty()) {
            return name.substring(name.lastIndexOf('/') + 1);
        }
        return label;
    }
}
