package io.jenkins.plugins.dockersandbox;

import io.jenkins.plugins.dockersandbox.account.AccountApiFactory;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.HttpSandboxEndpointApi;
import io.jenkins.plugins.dockersandbox.api.SandboxEndpointApi;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import java.io.IOException;

/** Creates API clients for an account / sandbox; the seam used to run {@link SandboxJobRunner} against fakes. */
public interface SandboxBackend {

    DockerSandboxApi management(SandboxAccount account) throws IOException;

    SandboxEndpointApi endpoint(DockerSandboxApi management, Sandbox sandbox) throws IOException;

    /** Real HTTP clients. */
    SandboxBackend DEFAULT = new SandboxBackend() {
        @Override
        public DockerSandboxApi management(SandboxAccount account) throws IOException {
            return AccountApiFactory.apiFor(account);
        }

        @Override
        public SandboxEndpointApi endpoint(DockerSandboxApi management, Sandbox sandbox) throws IOException {
            String uri = sandbox.endpointUri();
            if (uri == null || uri.isBlank()) {
                throw new IOException("Sandbox " + sandbox.id() + " reported no endpoint URI");
            }
            return new HttpSandboxEndpointApi(management, sandbox.id(), uri);
        }
    };
}
