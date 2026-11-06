package io.jenkins.plugins.dockersandbox.api.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Explicit sandbox deadline: {@code timeout} is a duration like {@code "3600s"}, {@code onTimeout} e.g. {@code "delete"}. */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimeoutOptions(String timeout, String onTimeout) {}
