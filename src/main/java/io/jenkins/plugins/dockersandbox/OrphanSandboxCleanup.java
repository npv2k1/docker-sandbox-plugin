package io.jenkins.plugins.dockersandbox;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Run;
import hudson.model.TaskListener;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import java.io.IOException;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Deletes sandboxes that this Jenkins created for builds that are over (or gone), e.g. after a controller crash.
 * Only sandboxes labelled with this instance's id and older than a grace period are touched.
 */
@Extension
public class OrphanSandboxCleanup extends AsyncPeriodicWork {

    static final Duration MIN_AGE = Duration.ofMinutes(15);

    public OrphanSandboxCleanup() {
        super("Docker sandbox orphan cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(10);
    }

    @Override
    protected void execute(TaskListener listener) {
        DockerSandboxGlobalConfiguration config = DockerSandboxGlobalConfiguration.get();
        if (!config.isOrphanCleanupEnabled()) {
            return;
        }
        cleanup(config, SandboxBackend.DEFAULT, Clock.systemUTC(), listener.getLogger());
    }

    /** @return number of sandboxes deleted */
    public static int cleanup(
            DockerSandboxGlobalConfiguration config, SandboxBackend backend, Clock clock, PrintStream log) {
        int deleted = 0;
        String instance = config.getInstanceId();
        for (SandboxAccount account : config.getAccounts()) {
            try {
                DockerSandboxApi api = backend.management(account);
                for (Sandbox sandbox : api.listAllSandboxes()) {
                    if (isOrphan(sandbox, instance, clock)) {
                        try {
                            api.deleteSandbox(sandbox.id(), true);
                            deleted++;
                            log.println("Deleted orphaned Docker sandbox " + sandbox.id() + " of account "
                                    + account.getId());
                        } catch (IOException e) {
                            log.println("Could not delete orphaned sandbox " + sandbox.id() + " of account "
                                    + account.getId() + ": " + SandboxJobRunner.mask(e.getMessage()));
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                log.println("Orphan cleanup skipped account " + account.getId() + ": "
                        + SandboxJobRunner.mask(e.getMessage()));
            }
        }
        return deleted;
    }

    static boolean isOrphan(Sandbox sandbox, String instanceId, Clock clock) {
        if (sandbox.core() == null || sandbox.core().labels() == null) {
            return false;
        }
        Map<String, String> labels = sandbox.core().labels();
        if (!instanceId.equals(labels.get("jenkins-instance")) || labels.containsKey(SandboxJobRunner.KEEP_LABEL)) {
            return false;
        }
        String encoded = labels.get("jenkins-run");
        String runId = encoded == null ? null : SandboxJobRunner.decodeRunId(encoded);
        if (runId == null) {
            return false;
        }
        if (isBuilding(runId)) {
            return false;
        }
        String created = sandbox.core().createdAt();
        if (created == null) {
            return false;
        }
        try {
            return Instant.parse(created).plus(MIN_AGE).isBefore(clock.instant());
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static boolean isBuilding(String externalizableId) {
        try {
            Run<?, ?> run = Run.fromExternalizableId(externalizableId);
            return run != null && run.isBuilding();
        } catch (IllegalArgumentException | SecurityException e) {
            return false; // job is gone
        }
    }
}
