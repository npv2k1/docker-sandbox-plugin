package io.jenkins.plugins.dockersandbox.api;

import io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest;
import io.jenkins.plugins.dockersandbox.api.model.EndpointCredential;
import io.jenkins.plugins.dockersandbox.api.model.ListSandboxesResponse;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Management API of one Docker account ({@code https://connect.docker.com/sandboxes}). */
public interface DockerSandboxApi {

    /** Creates a sandbox (201 or 202 - poll {@link #getSandbox} until running). */
    Sandbox createSandbox(CreateSandboxRequest request, String idempotencyKey) throws IOException;

    Sandbox getSandbox(String sandboxId) throws IOException;

    ListSandboxesResponse listSandboxes(int pageSize, String pageToken) throws IOException;

    /**
     * Deletes a sandbox. The API demands an {@code If-Match} etag, so implementations read the current etag first
     * and retry once on a stale precondition. A sandbox that is already gone is not an error.
     */
    void deleteSandbox(String sandboxId, boolean force) throws IOException;

    /** Issues a short-lived endpoint credential for the sandbox. */
    EndpointCredential createEndpointCredential(String sandboxId, List<String> permissions, int ttlSeconds)
            throws IOException;

    /** Lists all sandboxes by following page tokens. */
    default List<Sandbox> listAllSandboxes() throws IOException {
        List<Sandbox> all = new ArrayList<>();
        String token = null;
        do {
            ListSandboxesResponse page = listSandboxes(100, token);
            if (page.sandboxes() != null) {
                all.addAll(page.sandboxes());
            }
            token = page.nextPageToken();
        } while (token != null && !token.isEmpty());
        return all;
    }
}
