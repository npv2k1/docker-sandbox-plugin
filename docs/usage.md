# Usage

The plugin provides a Pipeline step (`sandboxRun`) and a Freestyle build step. Both take the same options and run the
same code: the workspace is uploaded to a fresh sandbox, the script runs with `bash -e` in the work directory (default
`/home/agent/workspace` with a kit, `/tmp/workspace` with a custom image), its output is streamed to the build log, artifacts are copied back and the sandbox is deleted.

Log lines written by the plugin itself start with `[docker-sandbox]`.

## `sandboxRun` reference

`sandboxRun` needs a workspace, so call it inside `node { }` (or in a stage with an `agent` in declarative pipelines).
Only `script` is required; every other parameter is optional.

| Parameter | Type | Default | Description |
| --- | --- | --- | --- |
| `script` | `String` | required | Shell script, run as `bash -e .jenkins-sandbox.sh` in `workDir`. Must not be blank |
| `account` | `String` | `''` | Pin the build to this account id. Empty = let the load balancer choose |
| `accountLabel` | `String` | `''` | Space-separated labels; the account must carry **all** of them (case-insensitive) |
| `kit` | `String` | `''` = global default kit (`shell`) | Sandbox template to start from. A bare name such as `shell` is launched as the image `docker/sandbox-templates:<kit>` (with the template's own start command); a value containing `/` or `:` is used as the image reference as is. An empty value means "use the global default" |
| `image` | `String` | `''` | Run in a custom Docker image instead of a kit: an OCI reference (`node:22`, `maven:3.9-eclipse-temurin-21`) or a managed image `images/<id>`. Overrides `kit` and the global defaults, and no kit is sent. The image must provide `bash` and `tar`. See [custom images](#custom-docker-images) |
| `imageCommand` | `String` | `''` = `sleep infinity` | Keep-alive command of the image, run as `sh -c <value>`. Only used with an image |
| `size` | `String` | `''` = global default size (`small`) | `micro`, `small`, `medium`, `large` or `xl` (case-insensitive); anything else fails the build |
| `ttlMinutes` | `int` | `0` = global default (60) | Sandbox lifetime in minutes; the sandbox is deleted at expiry. Negative values count as `0` |
| `uploadWorkspace` | `boolean` | `true` | Upload the workspace to the sandbox before the script |
| `includes` | `String` | `**/*` | Ant-style include patterns of the uploaded files (blank = `**/*`) |
| `excludes` | `String` | `''` | Ant-style exclude patterns, for example `.git/**,node_modules/**` |
| `maxUploadMb` | `int` | `200` | Maximum size of the compressed workspace archive in MB (minimum 1); larger archives fail the build |
| `workDir` | `String` | `''` = `/home/agent/workspace` (kit) or `/tmp/workspace` (image) | Absolute path inside the sandbox where the workspace is extracted and the script runs. An explicit value is used in both modes |
| `artifacts` | `String` | `''` | Comma-separated relative Ant-style globs (relative to `workDir`) copied back into the Jenkins workspace after the script ends |
| `allowEmptyArtifacts` | `boolean` | `true` | If no file matches `artifacts`: `true` logs a warning, `false` fails the build |
| `env` | `List<String>` | `[]` | `NAME=value` entries set for the script process only (not stored on the sandbox) |
| `timeoutMinutes` | `int` | `60` | Terminates the script after this many minutes (TERM, then KILL) and fails the build. `0` disables the limit |
| `keepSandbox` | `boolean` | `false` | Do not delete the sandbox at the end; it expires with its TTL. Such sandboxes are never touched by the orphan cleanup |
| `returnStatus` | `boolean` | `false` | Return the exit code instead of failing the build on a non-zero exit |

### Custom Docker images

With `image` (or a global *Default image*) the sandbox is created from that image instead of a kit.

* Precedence: step `image` > step `kit` > global *Default image* > global *Default kit*.
* Difference to `kit`: an `image` gets the keep-alive start command (`imageCommand`, default `sleep infinity`) and the
  `bash`/`tar` preflight check; a `kit` template image keeps its own start command.
* Values starting with `images/` are managed images (sent as `image`); anything else is an OCI reference (sent as
  `imageRef`). The two are never sent together.
* The plugin sends `startupExecution.rawImage.start.argv = ["sh", "-c", imageCommand]` (`sleep infinity` when
  `imageCommand` is blank) so the sandbox stays alive; an image such as `node:22` would otherwise exit at once.
* After the sandbox is running the plugin checks that the image has `bash` and `tar`
  (`sh -c 'command -v bash >/dev/null && command -v tar >/dev/null'`). If not, the build fails with
  `Image <ref> must provide bash and tar (alpine: apk add bash tar)`; the sandbox is still deleted.
* The sandbox gets the label `jenkins-image` (the sanitized image reference).
* `workDir` defaults to `/tmp/workspace` in image mode, which non-root image users can write to.

**Not verified against the live service.** Sandboxes from raw images (including whether the process and file APIs work
in them, and pulls from private registries, which the plugin has no way to authenticate) are covered by automated tests
against a fake only. See [Troubleshooting](troubleshooting.md#custom-image-problems).

Notes on the patterns:

* `includes` / `excludes` are passed to Jenkins' directory scanner as comma-separated Ant patterns.
* `artifacts` patterns may contain only letters, digits and `_ . / * ? @ + = [ ] -`, must be relative and must not
  contain `..`; otherwise the build fails with `Unsupported artifact pattern`. They are expanded by bash (with
  `globstar` and `nullglob`) inside the sandbox, so `dist/**` matches the files below `dist/`. Dot files are not
  matched by `*` / `**` unless the pattern names them explicitly.
* `env` names must match `[A-Za-z_][A-Za-z0-9_]*`; values are everything after the first `=`.

### Return value

`sandboxRun` returns an object, usable in the Groovy sandbox:

| Property | Type | Description |
| --- | --- | --- |
| `exitCode` | `int` | Exit code of the script (always `0` unless `returnStatus: true`, because otherwise the step fails first) |
| `accountId` | `String` | Account id the sandbox was created on |
| `sandboxId` | `String` | Id of the sandbox (useful with `keepSandbox: true`) |

### What fails the build

* A non-zero exit code, unless `returnStatus: true` (message `Sandbox script exited with code N`).
* A timeout (`Sandbox script timed out after N minute(s)`), even with `returnStatus: true`.
* No usable account, capacity wait timeout, sandbox creation or start failures, upload/limit errors, invalid
  parameters. See [Troubleshooting](troubleshooting.md).
* A Jenkins restart while the step runs; the sandbox is cleaned up by the [orphan cleanup](configuration.md#orphan-cleanup).

## Freestyle build step

*Add build step > Run script in Docker Cloud Sandbox* (symbol `sandboxBuild`). The form has the same options:

| UI label | Equivalent parameter | Default |
| --- | --- | --- |
| Script | `script` | required |
| Account | `account` | empty |
| Account labels | `accountLabel` | empty |
| Size | `size` | `(global default)` |
| Upload workspace | `uploadWorkspace` | checked |
| Artifacts to copy back | `artifacts` | empty |
| Timeout (minutes, 0 = none) | `timeoutMinutes` | `60` |

Under **Advanced**:

| UI label | Equivalent parameter | Default |
| --- | --- | --- |
| Docker image | `image` | empty (use kit / global defaults) |
| Image keep-alive command | `imageCommand` | empty (`sleep infinity`) |
| Kit | `kit` | empty (global default) |
| Sandbox TTL (minutes, 0 = default) | `ttlMinutes` | `0` |
| Workspace includes / excludes | `includes` / `excludes` | `**/*` / empty |
| Environment variables (NAME=value per line) | `env` | empty (one entry per line, blank lines ignored) |
| Keep sandbox after the build | `keepSandbox` | unchecked |
| Do not fail on non-zero exit (mark unstable) | `returnStatus` | unchecked; a non-zero exit marks the build **UNSTABLE** |
| Allow empty artifacts | `allowEmptyArtifacts` | checked |
| Work directory in the sandbox | `workDir` | empty (`/home/agent/workspace` with a kit, `/tmp/workspace` with an image) |
| Max workspace upload (MB) | `maxUploadMb` | `200` |

## Examples

### Full round trip: local files → sandbox → edit and test → back to Jenkins

[`examples/round-trip/Jenkinsfile`](../examples/round-trip/Jenkinsfile) is a complete, runnable pipeline (the test
suite executes it unchanged, see `ExamplePipelineTest`):

1. creates `app/greet.sh`, `app/config.properties` and `notes/ignored.txt` in the Jenkins workspace,
2. uploads only `app/**` to the sandbox (`includes` / `excludes`),
3. edits both files inside the sandbox (`sed`) and adds a line,
4. runs a test against the edited files and writes `report/test-report.txt`; a failing test fails the build,
5. copies `app/**` and `report/**` back (`artifacts`, overwriting the local originals), checks the content in Jenkins
   with `readFile` and archives them.

Copy it into a Pipeline job (*Pipeline script*) or point a job at it with *Pipeline script from SCM*.

### AI coding agent edits your code in a sandbox (Claude Code, Codex, Gemini, ...)

[`examples/ai-agent/Jenkinsfile`](../examples/ai-agent/Jenkinsfile) is a parameterized pipeline: the code is uploaded
to a sandbox started from the agent's template, the agent works on the `PROMPT` in headless mode, `TEST_COMMAND` runs
afterwards, and the changed files (`PULL_BACK`) plus `agent.patch`, `agent.log` and `test.log` are copied back and
archived.

| `AGENT` | Kit (template image) | Headless command | Key variable |
| --- | --- | --- | --- |
| `dry-run` (default) | `shell` | fixed `sed` edit, no AI | none |
| `claude-code` | `docker/sandbox-templates:claude-code` | `claude -p "$AGENT_PROMPT" --dangerously-skip-permissions` | `ANTHROPIC_API_KEY` |
| `codex` | `docker/sandbox-templates:codex` | `codex exec --dangerously-bypass-approvals-and-sandbox --skip-git-repo-check "$AGENT_PROMPT"` | `OPENAI_API_KEY` |
| `gemini` | `docker/sandbox-templates:gemini` | `gemini -p "$AGENT_PROMPT" --yolo` | `GEMINI_API_KEY` |
| `opencode` | `docker/sandbox-templates:opencode` | `opencode run "$AGENT_PROMPT"` | `ANTHROPIC_API_KEY` |
| `copilot` | `docker/sandbox-templates:copilot` | `copilot -p "$AGENT_PROMPT" --allow-all-tools` | `GITHUB_TOKEN` |
| `cursor-agent` | `docker/sandbox-templates:cursor-agent` | `cursor-agent -p "$AGENT_PROMPT" --force` | `CURSOR_API_KEY` |

* The "skip permissions / yolo" flags are acceptable here because the agent runs inside a disposable sandbox that is
  deleted after the build; the workspace on the Jenkins agent is only changed by the files you list in `PULL_BACK`.
* API key: either store it in Docker's cloud secret store (`sbx --cloud secret set anthropic`; the key never enters
  the sandbox) or pass a Jenkins *Secret text* credential via `API_KEY_CREDENTIALS_ID` (needs Credentials Binding).
* Other published templates (`droid`, `kiro`, `devin`, `docker-agent`, ...) work the same way: add an entry to the
  `AGENTS` map with `kit` and the agent's headless command. There is no published template for Antigravity (`agy`);
  use a custom `image` with that CLI installed instead.
* Verified: the templates exist on Docker Hub and the `dry-run` flow (upload, edit, test, patch, pull back) runs in the
  test suite. The agents' headless commands and secret-store injection into API-created sandboxes have **not** been run
  against the live service.


### Node build with artifacts

```groovy
pipeline {
  agent any
  stages {
    stage('Build') {
      steps {
        sandboxRun(
          script: '''
            node --version
            npm ci
            npm run build
          ''',
          size: 'medium',
          excludes: '.git/**,node_modules/**',
          artifacts: 'dist/**',
          env: ['NODE_ENV=production', 'CI=true'])
        archiveArtifacts artifacts: 'dist/**', fingerprint: true
      }
    }
  }
}
```

The sandbox image must provide Node.js; the default `shell` kit does not promise it. Use a Node image instead
(see [Node build in a custom image](#node-build-in-a-custom-image)) or see
[Troubleshooting](troubleshooting.md#command-not-found-in-the-script).

### Maven build in a custom image, with artifacts

```groovy
node {
  checkout scm
  sandboxRun(
    image: 'maven:3.9-eclipse-temurin-21',
    script: 'mvn -B -ntp verify',
    size: 'large',
    excludes: '.git/**,target/**',
    artifacts: 'target/*.jar, target/surefire-reports/*.xml',
    allowEmptyArtifacts: false)
  junit 'target/surefire-reports/*.xml'
  archiveArtifacts artifacts: 'target/*.jar'
}
```

The Maven images are Debian based and include `bash` and `tar`. The script runs in `/tmp/workspace`.

### Node build in a custom image

```groovy
node {
  checkout scm
  sandboxRun(
    image: 'node:22',
    script: 'npm ci && npm run build',
    excludes: '.git/**,node_modules/**',
    artifacts: 'dist/**')
}
```

### Managed image

```groovy
node {
  sandboxRun(image: 'images/my-toolchain', script: 'make test', uploadWorkspace: true)
}
```

`images/<id>` refers to an image resource of your Docker account (for example created with `sbx --cloud template save`).

### Maven build with test reports and the jar

```groovy
node {
  checkout scm
  def r = sandboxRun(
    script: 'mvn -B -ntp verify',
    size: 'large',
    excludes: '.git/**,target/**',
    artifacts: 'target/*.jar, target/surefire-reports/*.xml',
    allowEmptyArtifacts: false,
    timeoutMinutes: 45,
    returnStatus: true)
  junit allowEmptyResults: true, testResults: 'target/surefire-reports/*.xml'
  archiveArtifacts artifacts: 'target/*.jar', allowEmptyArchive: true
  if (r.exitCode != 0) {
    error "Maven failed with exit code ${r.exitCode}"
  }
}
```

Artifacts are copied back even when the script fails, so test reports are available for `junit`. Spaces around the commas
in `artifacts` are trimmed.

### Pin an account or require labels

```groovy
node {
  // exactly this account
  sandboxRun(script: './deploy-check.sh', account: 'acc-eu')

  // any enabled account that carries both labels
  sandboxRun(script: './heavy-build.sh', accountLabel: 'eu large', size: 'xl')
}
```

### Use `returnStatus` and the result

```groovy
node {
  def r = sandboxRun(script: 'make lint', returnStatus: true)
  echo "exit=${r.exitCode} account=${r.accountId} sandbox=${r.sandboxId}"
  if (r.exitCode != 0) {
    unstable('lint reported problems')
  }
}
```

### Keep a sandbox for debugging

```groovy
node {
  def r = sandboxRun(script: 'make test', keepSandbox: true, ttlMinutes: 30, returnStatus: true)
  echo "Inspect sandbox ${r.sandboxId} on account ${r.accountId}; it expires after 30 minutes"
}
```

### Parallel builds across accounts

Each parallel branch needs its own `node` so that it has a workspace. The load balancer spreads the branches over
the configured accounts and waits when all are busy.

```groovy
def targets = ['linux-amd64', 'linux-arm64', 'docs']
def branches = [:]
for (t in targets) {
  def target = t   // capture for the closure
  branches[target] = {
    node {
      checkout scm
      sandboxRun(
        script: "make build TARGET=${target}",
        excludes: '.git/**',
        artifacts: "out/${target}/**")
      archiveArtifacts artifacts: "out/${target}/**"
    }
  }
}
parallel branches
```

### Without uploading the workspace

```groovy
node {
  sandboxRun(script: 'curl -fsS https://example.com/health', uploadWorkspace: false)
}
```

## Behavior to be aware of

* The script file `.jenkins-sandbox.sh` is written into the work directory after the workspace has been extracted.
* Output is polled from the sandbox and written to the build log while the script runs.
* The plugin never sends your Jenkins environment to the sandbox. Pass what the script needs through `env`; values are
  not stored in the sandbox metadata.
* Anything you put in `env` or `script` is visible to the sandboxed code. To pass a Jenkins secret, bind it with
  `withCredentials` and forward it explicitly, for example `env: ["TOKEN=${TOKEN}"]`. Jenkins' own secret masking
  applies only to output that passes through Jenkins' masking, so avoid echoing secrets in the script.
