package io.jenkins.plugins.dockersandbox.api;

import io.jenkins.plugins.dockersandbox.api.model.CreateProcessRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecResponse;
import io.jenkins.plugins.dockersandbox.api.model.Process;
import io.jenkins.plugins.dockersandbox.api.model.ReadOutputResponse;
import java.io.IOException;
import java.nio.file.Path;

/** Process and file services served by one running sandbox. */
public interface SandboxEndpointApi {

    ExecResponse exec(ExecRequest request) throws IOException;

    Process startProcess(CreateProcessRequest request) throws IOException;

    /** Reads output after stream sequence {@code resumeFrom}; {@code maxBytes <= 0} means the server default. */
    ReadOutputResponse readOutput(String processId, long resumeFrom, int maxBytes) throws IOException;

    /** Sends a signal ({@code term}, {@code kill}, ...). */
    void signal(String processId, String signal) throws IOException;

    /** Uploads a local file to {@code remotePath} (absolute path inside the sandbox) with Unix {@code mode}. */
    void putFile(String remotePath, int mode, Path localFile) throws IOException;

    /** Downloads {@code remotePath} into {@code target}; returns the byte count. */
    long getFile(String remotePath, Path target) throws IOException;
}
