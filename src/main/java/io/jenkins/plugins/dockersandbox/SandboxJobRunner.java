package io.jenkins.plugins.dockersandbox;

import hudson.AbortException;
import hudson.FilePath;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.util.DirScanner;
import hudson.util.io.ArchiverFactory;
import io.jenkins.plugins.dockersandbox.account.AccountPool;
import io.jenkins.plugins.dockersandbox.account.AccountSelector;
import io.jenkins.plugins.dockersandbox.account.SandboxAccount;
import io.jenkins.plugins.dockersandbox.api.DockerSandboxApi;
import io.jenkins.plugins.dockersandbox.api.SandboxApiException;
import io.jenkins.plugins.dockersandbox.api.SandboxEndpointApi;
import io.jenkins.plugins.dockersandbox.api.model.CreateProcessRequest;
import io.jenkins.plugins.dockersandbox.api.model.CreateSandboxRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecRequest;
import io.jenkins.plugins.dockersandbox.api.model.ExecResponse;
import io.jenkins.plugins.dockersandbox.api.model.FeatureOptions;
import io.jenkins.plugins.dockersandbox.api.model.Process;
import io.jenkins.plugins.dockersandbox.api.model.ProcessChunk;
import io.jenkins.plugins.dockersandbox.api.model.ReadOutputResponse;
import io.jenkins.plugins.dockersandbox.api.model.Sandbox;
import io.jenkins.plugins.dockersandbox.api.model.StartupExecution;
import io.jenkins.plugins.dockersandbox.api.model.TimeoutOptions;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs one script in a Docker Cloud Sandbox: lease an account, create the sandbox, upload the workspace, run the
 * script while streaming its output, fetch artifacts and always delete the sandbox and release the lease.
 */
public class SandboxJobRunner {

    /** Polling intervals; tests shorten them. */
    public static final class Timing {
        final Duration createPoll;
        final Duration createTimeout;
        final Duration outputMinPoll;
        final Duration outputMaxPoll;
        final Duration killGrace;
        final Duration deleteRetryPause;

        public Timing(
                Duration createPoll,
                Duration createTimeout,
                Duration outputMinPoll,
                Duration outputMaxPoll,
                Duration killGrace,
                Duration deleteRetryPause) {
            this.createPoll = createPoll;
            this.createTimeout = createTimeout;
            this.outputMinPoll = outputMinPoll;
            this.outputMaxPoll = outputMaxPoll;
            this.killGrace = killGrace;
            this.deleteRetryPause = deleteRetryPause;
        }

        static Timing defaults() {
            return new Timing(
                    Duration.ofSeconds(2),
                    Duration.ofMinutes(5),
                    Duration.ofMillis(300),
                    Duration.ofSeconds(2),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(1));
        }
    }

    private static volatile Timing timing = Timing.defaults();

    /** Test hook: pass {@code null} to restore the defaults. */
    public static void setTiming(Timing t) {
        timing = t == null ? Timing.defaults() : t;
    }

    public static final String KEEP_LABEL = "jenkins-keep";
    static final String PREFLIGHT_COMMAND = "command -v bash >/dev/null && command -v tar >/dev/null";
    static final String MANAGED_IMAGE_PREFIX = "images/";
    static final String UPLOAD_PATH = "/tmp/jenkins-ws.tgz";
    static final String ARTIFACT_PATH = "/tmp/jenkins-out.tgz";
    private static final String SCRIPT_NAME = ".jenkins-sandbox.sh";
    private static final Pattern SECRET_PATTERNS = Pattern.compile(
            "dckr_pat_[A-Za-z0-9_-]+|(?i:bearer)\\s+[A-Za-z0-9._~+/=-]+|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*");
    private static final Pattern ENV_KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern ARTIFACT_PATTERN = Pattern.compile("[A-Za-z0-9_./*?@+=\\[\\]-]+");

    private final DockerSandboxGlobalConfiguration config;
    private final SandboxBackend backend;

    public SandboxJobRunner() {
        this(DockerSandboxGlobalConfiguration.get(), SandboxBackend.DEFAULT);
    }

    public SandboxJobRunner(DockerSandboxGlobalConfiguration config, SandboxBackend backend) {
        this.config = config;
        this.backend = backend;
    }

    /**
     * @param workspace needed when the workspace is uploaded or artifacts are collected; may be null otherwise
     */
    public SandboxRunResult run(Run<?, ?> run, FilePath workspace, TaskListener listener, SandboxRunSpec spec)
            throws IOException, InterruptedException {
        validate(spec, workspace);
        SandboxSize size;
        try {
            size = SandboxSize.parse(spec.getSize() != null ? spec.getSize() : config.getDefaultSize());
        } catch (IllegalArgumentException e) {
            throw new AbortException(e.getMessage());
        }
        // precedence: step image > step kit > global default image > global default kit
        String image = spec.getImage();
        if (image == null && spec.getKit() == null && !config.getDefaultImage().isEmpty()) {
            image = config.getDefaultImage();
        }
        String kit = image != null ? "" : spec.getKit() != null ? spec.getKit() : config.getDefaultKit();
        String workDir = spec.getWorkDir() != null
                ? spec.getWorkDir()
                : image != null ? SandboxRunSpec.DEFAULT_IMAGE_WORK_DIR : SandboxRunSpec.DEFAULT_WORK_DIR;
        int ttl = spec.getTtlMinutes() > 0 ? spec.getTtlMinutes() : config.getDefaultTtlMinutes();
        AccountPool pool = config.getPool();
        Duration cooldown = Duration.ofMinutes(config.getCooldownMinutes());
        Duration leaseTimeout = Duration.ofMinutes(config.getLeaseTimeoutMinutes());
        PrintStream logger = listener.getLogger();

        AccountSelector selector = AccountSelector.of(spec.getAccount(), spec.getAccountLabel());
        Set<String> tried = new HashSet<>();
        String lastFailure = null;
        SandboxRunResult result = null;
        for (int attempt = 1; attempt <= config.getMaxFailoverAttempts() && result == null; attempt++) {
            AccountPool.Lease lease;
            try {
                lease = pool.lease(selector.excluding(tried), config.getStrategy(), leaseTimeout, m -> log(logger, m));
            } catch (AbortException e) {
                if (tried.isEmpty()) {
                    throw e;
                }
                throw new AbortException(
                        "No Docker sandbox account left to fail over to (last error: " + lastFailure + ")");
            }
            try (AccountPool.Lease held = lease) {
                SandboxAccount account = held.getAccount();
                DockerSandboxApi api;
                Sandbox created;
                String name = displayName(run);
                try {
                    api = backend.management(account);
                    Map<String, String> labels = labels(run, account, spec.isKeepSandbox());
                    boolean managed = image != null && image.startsWith(MANAGED_IMAGE_PREFIX);
                    if (image != null) {
                        labels.put("jenkins-image", sanitizeLabel(image));
                    }
                    // The live API rejects kits given only by name (it requires the kit artifact
                    // inline), so a kit is launched as its sandbox template image instead, with the
                    // template's own start command.
                    String templateRef = image == null && !kit.isEmpty() ? kitImageRef(kit) : null;
                    CreateSandboxRequest request = new CreateSandboxRequest(
                            name,
                            size.toResources(),
                            null,
                            null,
                            labels,
                            new FeatureOptions(new TimeoutOptions(ttl * 60 + "s", "delete")),
                            managed ? image : null,
                            image != null && !managed ? image : templateRef,
                            image == null ? null : StartupExecution.rawImageStart(imageStartArgv(spec.getImageCommand())));
                    created = api.createSandbox(request, UUID.randomUUID().toString());
                } catch (SandboxApiException e) {
                    if (!(e.isQuotaExhausted() || e.isAuthError())) {
                        throw new AbortException(
                                "Creating sandbox on account '" + account.getId() + "' failed: " + mask(e.getMessage()));
                    }
                    lastFailure = mask(e.getMessage());
                    pool.markFailure(account, e, cooldown);
                    tried.add(account.getId());
                    log(logger, "Account '" + account.getId() + "' cannot create a sandbox (" + lastFailure
                            + "); trying another account");
                    continue;
                } catch (AbortException e) {
                    // typically missing credentials: a configuration problem of this account only
                    lastFailure = mask(e.getMessage());
                    pool.markFailure(account, e, cooldown);
                    tried.add(account.getId());
                    log(logger, lastFailure + "; trying another account");
                    continue;
                }
                result = runInSandbox(api, created, account, workspace, listener, spec, workDir, image);
            }
        }
        if (result == null) {
            throw new AbortException(
                    "Could not create a Docker sandbox on any account after " + config.getMaxFailoverAttempts()
                            + " attempt(s) (last error: " + lastFailure + ")");
        }
        if (result.getExitCode() != 0 && !spec.isReturnStatus()) {
            throw new AbortException("Sandbox script exited with code " + result.getExitCode());
        }
        return result;
    }

    private SandboxRunResult runInSandbox(
            DockerSandboxApi api,
            Sandbox created,
            SandboxAccount account,
            FilePath workspace,
            TaskListener listener,
            SandboxRunSpec spec,
            String workDir,
            String image)
            throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();
        String sandboxId = created.id();
        SandboxEndpointApi endpoint = null;
        Process process = null;
        log(logger, "Created sandbox " + sandboxId + " on account '" + account.getId() + "', waiting for it to start");
        try {
            Sandbox ready = waitRunning(api, created);
            log(logger, "Sandbox " + sandboxId + " on account " + account.getId() + " ready");
            endpoint = backend.endpoint(api, ready);
            if (image != null) {
                preflightImage(endpoint, image);
            }
            execChecked(endpoint, "Preparing the work directory", "mkdir", "-p", workDir);
            if (spec.isUploadWorkspace()) {
                uploadWorkspace(endpoint, workspace, listener, spec, workDir);
            }
            Path scriptFile = Files.createTempFile("jenkins-sandbox-script", ".sh");
            try {
                Files.writeString(scriptFile, spec.getScript(), StandardCharsets.UTF_8);
                endpoint.putFile(workDir + "/" + SCRIPT_NAME, 493, scriptFile);
            } finally {
                Files.deleteIfExists(scriptFile);
            }
            process = endpoint.startProcess(new CreateProcessRequest(
                    List.of("bash", "-e", SCRIPT_NAME), parseEnv(spec.getEnv()), workDir, null, null));
            int exitCode = streamOutput(endpoint, process, logger, spec);
            log(logger, "Script finished with exit code " + exitCode);
            if (workspace != null && !spec.getArtifacts().isEmpty()) {
                fetchArtifacts(endpoint, workspace, listener, spec, workDir);
            }
            return new SandboxRunResult(exitCode, account.getId(), sandboxId);
        } catch (InterruptedException | InterruptedIOException e) {
            boolean wasFlagged = Thread.interrupted();
            try {
                log(logger, "Build interrupted; cleaning up sandbox " + sandboxId);
                if (endpoint != null && process != null) {
                    signalQuietly(endpoint, process, "kill");
                }
            } finally {
                if (wasFlagged) {
                    Thread.currentThread().interrupt();
                }
            }
            if (e instanceof InterruptedException) {
                throw (InterruptedException) e;
            }
            throw (InterruptedException) new InterruptedException(e.getMessage()).initCause(e);
        } finally {
            boolean wasFlagged = Thread.interrupted();
            try {
                if (spec.isKeepSandbox()) {
                    log(logger, "Keeping sandbox " + sandboxId + " (keepSandbox=true); it expires with its TTL");
                } else {
                    deleteQuietly(api, sandboxId, logger);
                }
            } finally {
                if (wasFlagged) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private Sandbox waitRunning(DockerSandboxApi api, Sandbox initial) throws IOException, InterruptedException {
        Timing t = timing;
        long deadline = System.nanoTime() + t.createTimeout.toNanos();
        Sandbox sb = initial;
        while (true) {
            String status = sb.status();
            if (Sandbox.RUNNING.equals(status) && sb.endpointUri() != null) {
                return sb;
            }
            if (Sandbox.FAILED.equals(status)) {
                String reason = sb.failure() != null && sb.failure().message() != null ? sb.failure().message() : "unknown";
                throw new AbortException("Sandbox " + sb.id() + " failed to start: " + mask(reason));
            }
            if ("stopped".equals(status) || "stopping".equals(status) || "deleting".equals(status)) {
                throw new AbortException("Sandbox " + sb.id() + " is " + status + " instead of running");
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AbortException("Timed out waiting for sandbox " + sb.id() + " to start (status '" + status + "')");
            }
            Thread.sleep(t.createPoll.toMillis());
            sb = api.getSandbox(sb.id());
        }
    }

    private void uploadWorkspace(
            SandboxEndpointApi endpoint, FilePath workspace, TaskListener listener, SandboxRunSpec spec, String workDir)
            throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();
        Path archive = Files.createTempFile("jenkins-sandbox-ws", ".tgz");
        try {
            long limit = spec.getMaxUploadMb() * 1024L * 1024L;
            try (OutputStream out = new LimitedOutputStream(new BufferedOutputStream(Files.newOutputStream(archive)), limit)) {
                workspace.archive(ArchiverFactory.TARGZ, out, new DirScanner.Glob(spec.getIncludes(), spec.getExcludes()));
            } catch (IOException e) {
                if (Files.size(archive) >= limit) {
                    throw new AbortException("Workspace archive exceeds the upload limit of " + spec.getMaxUploadMb()
                            + " MB; narrow 'includes'/'excludes' or raise maxUploadMb");
                }
                throw e;
            }
            log(logger, "Uploading workspace (" + Files.size(archive) / 1024 + " KiB)");
            endpoint.putFile(UPLOAD_PATH, 420, archive);
        } finally {
            Files.deleteIfExists(archive);
        }
        execChecked(
                endpoint,
                "Extracting the workspace",
                "bash",
                "-c",
                "mkdir -p " + quote(workDir) + " && tar -xzf " + UPLOAD_PATH + " -C "
                        + quote(workDir) + " && rm -f " + UPLOAD_PATH);
    }

    private int streamOutput(SandboxEndpointApi endpoint, Process process, PrintStream logger, SandboxRunSpec spec)
            throws IOException, InterruptedException {
        Timing t = timing;
        long started = System.nanoTime();
        long limitNanos = spec.getTimeoutMinutes() > 0 ? Duration.ofMinutes(spec.getTimeoutMinutes()).toNanos() : 0;
        long resumeFrom = 0;
        long pause = t.outputMinPoll.toMillis();
        String id = process.id();
        while (true) {
            ReadOutputResponse out = endpoint.readOutput(id, resumeFrom, 256 * 1024);
            boolean any = false;
            if (out.chunks() != null) {
                for (ProcessChunk chunk : out.chunks()) {
                    if (chunk.data() != null && chunk.data().length > 0) {
                        logger.write(chunk.data(), 0, chunk.data().length);
                        any = true;
                    }
                }
                logger.flush();
            }
            if (out.nextResumeFrom() != null) {
                resumeFrom = out.nextResumeFrom();
            }
            if (out.exited() != null) {
                return out.exited().exitCode();
            }
            if (limitNanos > 0 && System.nanoTime() - started > limitNanos) {
                log(logger, "Script exceeded the timeout of " + spec.getTimeoutMinutes() + " minute(s); terminating it");
                signalQuietly(endpoint, process, "term");
                Thread.sleep(Math.min(t.killGrace.toMillis(), 5000));
                signalQuietly(endpoint, process, "kill");
                throw new AbortException("Sandbox script timed out after " + spec.getTimeoutMinutes() + " minute(s)");
            }
            if (any) {
                pause = t.outputMinPoll.toMillis();
            } else {
                pause = Math.min(t.outputMaxPoll.toMillis(), Math.max(1, pause * 3 / 2));
            }
            Thread.sleep(pause);
        }
    }

    private void fetchArtifacts(SandboxEndpointApi endpoint, FilePath workspace, TaskListener listener, SandboxRunSpec spec, String workDir)
            throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();
        List<String> patterns = new ArrayList<>();
        for (String p : spec.getArtifacts().split(",")) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!ARTIFACT_PATTERN.matcher(trimmed).matches() || trimmed.startsWith("/") || trimmed.contains("..")) {
                throw new AbortException("Unsupported artifact pattern '" + trimmed
                        + "': use relative Ant-style globs made of letters, digits and _ . / * ? @ + = [ ] -");
            }
            patterns.add(trimmed);
        }
        if (patterns.isEmpty()) {
            return;
        }
        String command = "shopt -s globstar nullglob; cd " + quote(workDir) + " || exit 2; files=("
                + String.join(" ", patterns) + "); if [ ${#files[@]} -eq 0 ]; then exit 3; fi; "
                + "printf '%s\\n' \"${files[@]}\" | tar -czf " + ARTIFACT_PATH + " --no-recursion -T -";
        ExecResponse r = endpoint.exec(ExecRequest.of("bash", "-c", command));
        if (r.exitCode() == 3) {
            if (spec.isAllowEmptyArtifacts()) {
                log(logger, "WARNING: no files matched artifacts '" + spec.getArtifacts() + "'");
                return;
            }
            throw new AbortException("No files matched artifacts '" + spec.getArtifacts() + "'");
        }
        if (r.exitCode() != 0) {
            throw new AbortException("Collecting artifacts failed (exit " + r.exitCode() + "): " + mask(r.stderrText()));
        }
        Path download = Files.createTempFile("jenkins-sandbox-out", ".tgz");
        try {
            long bytes = endpoint.getFile(ARTIFACT_PATH, download);
            try (InputStream in = new BufferedInputStream(Files.newInputStream(download))) {
                workspace.untarFrom(in, FilePath.TarCompression.GZIP);
            }
            log(logger, "Copied artifacts back to the workspace (" + bytes / 1024 + " KiB)");
        } finally {
            Files.deleteIfExists(download);
        }
    }

    /** Image mode: the plugin needs bash (to run the script) and tar (to move the workspace). */
    private void preflightImage(SandboxEndpointApi endpoint, String image) throws IOException {
        ExecResponse r = endpoint.exec(ExecRequest.of("sh", "-c", PREFLIGHT_COMMAND));
        if (r.exitCode() != 0) {
            throw new AbortException(
                    mask("Image " + image + " must provide bash and tar (alpine: apk add bash tar)"));
        }
    }

    static List<String> imageStartArgv(String imageCommand) {
        String command = imageCommand == null || imageCommand.isBlank()
                ? SandboxRunSpec.DEFAULT_IMAGE_COMMAND
                : imageCommand;
        return List.of("sh", "-c", command);
    }

    /** Docker's sandbox template images, one per kit (for example {@code shell}). */
    static final String KIT_TEMPLATE_REPOSITORY = "docker/sandbox-templates:";

    /**
     * Image that launches a kit: a bare kit name such as {@code shell} maps to
     * {@code docker/sandbox-templates:shell}; a value that already looks like an image reference
     * (contains {@code /} or {@code :}) is used as is.
     */
    static String kitImageRef(String kit) {
        return kit.contains("/") || kit.contains(":") ? kit : KIT_TEMPLATE_REPOSITORY + kit;
    }

    private void execChecked(SandboxEndpointApi endpoint, String what, String... cmd) throws IOException {
        ExecResponse r = endpoint.exec(ExecRequest.of(cmd));
        if (r.exitCode() != 0) {
            throw new AbortException(what + " in the sandbox failed (exit " + r.exitCode() + "): "
                    + mask(r.stderrText().trim()));
        }
    }

    private void signalQuietly(SandboxEndpointApi endpoint, Process process, String signal) {
        try {
            endpoint.signal(process.id(), signal);
        } catch (IOException | RuntimeException e) {
            // best effort: the sandbox is deleted right after
        }
    }

    private void deleteQuietly(DockerSandboxApi api, String sandboxId, PrintStream logger) {
        Timing t = timing;
        IOException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                api.deleteSandbox(sandboxId, true);
                log(logger, "Deleted sandbox " + sandboxId);
                return;
            } catch (IOException e) {
                last = e;
                try {
                    Thread.sleep(t.deleteRetryPause.toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log(logger, "WARNING: could not delete sandbox " + sandboxId + " ("
                + (last == null ? "interrupted" : mask(last.getMessage())) + "); the orphan cleanup will retry");
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private static void validate(SandboxRunSpec spec, FilePath workspace) throws AbortException {
        if (spec.getScript().isBlank()) {
            throw new AbortException("sandboxRun: 'script' must not be empty");
        }
        if (workspace == null && (spec.isUploadWorkspace() || !spec.getArtifacts().isEmpty())) {
            throw new AbortException("sandboxRun needs a workspace to upload from / copy artifacts to; "
                    + "run it inside a node block or set uploadWorkspace=false without artifacts");
        }
        parseEnv(spec.getEnv());
        if (spec.getWorkDir() != null && !spec.getWorkDir().startsWith("/")) {
            throw new AbortException("sandboxRun: 'workDir' must be an absolute path");
        }
    }

    static Map<String, String> parseEnv(List<String> entries) throws AbortException {
        Map<String, String> env = new LinkedHashMap<>();
        for (String entry : entries) {
            int eq = entry.indexOf('=');
            if (eq <= 0 || !ENV_KEY.matcher(entry.substring(0, eq)).matches()) {
                throw new AbortException("Invalid env entry (expected NAME=value): " + entry.split("=", 2)[0]);
            }
            env.put(entry.substring(0, eq), entry.substring(eq + 1));
        }
        return env.isEmpty() ? null : env;
    }

    static String displayName(Run<?, ?> run) {
        String raw = "jk-" + run.getParent().getFullName().replace('/', '-') + "-" + run.getNumber();
        String clean = raw.replaceAll("[^A-Za-z0-9_-]", "-");
        return clean.length() > 64 ? clean.substring(clean.length() - 64) : clean;
    }

    /** Labels that tie a sandbox to this Jenkins and build, used by the orphan cleanup. */
    Map<String, String> labels(Run<?, ?> run, SandboxAccount account, boolean keep) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("jenkins-instance", config.getInstanceId());
        labels.put("jenkins-job", sanitizeLabel(run.getParent().getFullName()));
        labels.put("jenkins-build", String.valueOf(run.getNumber()));
        labels.put("jenkins-run", encodeRunId(run.getExternalizableId()));
        labels.put("jenkins-account", sanitizeLabel(account.getId()));
        if (keep) {
            labels.put(KEEP_LABEL, "true"); // sandboxes the user asked to keep are left to their TTL
        }
        return labels;
    }

    public static String encodeRunId(String externalizableId) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(externalizableId.getBytes(StandardCharsets.UTF_8));
    }

    /** Inverse of {@link #encodeRunId}; null if the value is not valid. */
    public static String decodeRunId(String label) {
        try {
            return new String(Base64.getUrlDecoder().decode(label), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String sanitizeLabel(String s) {
        String clean = s.replaceAll("[^A-Za-z0-9_.-]", "_");
        return clean.length() > 63 ? clean.substring(0, 63) : clean;
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Masks anything that looks like a Docker token before it reaches a log. */
    public static String mask(String text) {
        return text == null ? "" : SECRET_PATTERNS.matcher(text).replaceAll("****");
    }

    private static void log(PrintStream logger, String message) {
        logger.println("[docker-sandbox] " + mask(message).replace("\r", ""));
        logger.flush();
    }

    /** Fails with an IOException once more than {@code limit} bytes were written. */
    private static final class LimitedOutputStream extends FilterOutputStream {
        private final long limit;
        private long written;

        LimitedOutputStream(OutputStream out, long limit) {
            super(out);
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            count(1);
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            count(len);
            out.write(b, off, len);
        }

        private void count(int n) throws IOException {
            written += n;
            if (written > limit) {
                throw new IOException("upload limit exceeded");
            }
        }
    }
}
