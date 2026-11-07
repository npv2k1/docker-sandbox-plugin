# Troubleshooting

Messages written by the plugin to the build log start with `[docker-sandbox]`. For problems with the Docker side, the
*Test connection* button (account form or *Manage Jenkins > Docker Cloud Sandboxes*) is the fastest check.

> The Docker Cloud Sandboxes API is experimental. The core API flow is verified against the live service, but some
> messages below come from assumptions described in [Architecture](architecture.md#deviations-from-the-spec-and-unverified-assumptions).

## Authentication failed (HTTP 401/403)

**Symptoms.** *Test connection* or the build fails with `HTTP 401 ...`, `HTTP 403 ...`, `unauthenticated` or
`permissionDenied`; the build log says `Account 'X' cannot create a sandbox (...); trying another account`; the account
shows a cooldown and a last error on the management page.

**Cause.** The PAT is wrong, expired, revoked, belongs to another Docker ID, or lacks the **Allow Cloud Sandboxes access**
permission.

**Fix.** Create a new PAT with *Allow Cloud Sandboxes access*, update the password of the Jenkins credential, check that
the username is the Docker ID, and press *Test connection*. The change is picked up by new builds without a restart.
Also check that *Docker auth URL* and *Sandboxes API URL* still have their defaults (`https://hub.docker.com`,
`https://connect.docker.com/sandboxes`).

## Credentials not found or not configured

**Symptoms.** `Docker sandbox account 'X' has no credentials configured` or
`Credentials 'Y' of Docker sandbox account 'X' were not found (username/password credential expected)`.

**Cause.** The account has no credential selected, the credential was deleted, or it is not a *Username with password*
credential in the global scope.

**Fix.** Select (or recreate) a *Username with password* credential in the account. Credentials scoped to a folder are not
visible to the plugin. The build fails over to another account when one is available.

## Quota exhausted

**Symptoms.** `HTTP 429 resourceExhausted ...` in the build log, `Account 'X' cannot create a sandbox ...; trying another account`.

**Cause.** The Docker account reached its limit of concurrent sandboxes (10 by default) or another quota, possibly because
of sandboxes created outside Jenkins or kept with `keepSandbox`.

**Fix.** The account goes into cooldown and the build fails over to another account automatically. If all accounts are
exhausted the build fails with `Could not create a Docker sandbox on any account after N attempt(s) (last error: ...)`.
Add accounts, lower *Max concurrent sandboxes* to what Docker really allows, delete leftover sandboxes in the Docker
account, and see [cooldown and failover](configuration.md#capacity-cooldown-failover-and-waiting).

## Build is stuck at "Waiting for Docker sandbox capacity"

**Symptoms.** `Waiting for Docker sandbox capacity (all N accounts busy)`, later
`Timed out after N minute(s) waiting for Docker sandbox capacity on M account(s)`.

**Cause.** Every matching account is at *Max concurrent sandboxes* or cooling down. A `account` / `accountLabel` that is
too narrow reduces the candidates. Counters are in memory: a hung build still holds its slot.

**Fix.** Add accounts or raise *Max concurrent sandboxes* (not above the Docker limit), widen the label requirement,
raise *Wait for capacity (minutes)*, or abort the builds that hold the slots. The management page shows active / max and
cooldowns.

## No account matches

**Symptom.** `No enabled Docker sandbox account matches AccountSelector[account=..., labels=[...]]. Check Manage Jenkins > System > Docker Cloud Sandboxes.`

**Cause.** The pinned `account` does not exist or is disabled, no enabled account carries all requested labels, or no
account is configured, or all remaining ones already failed for this build.

**Fix.** Check ids and labels (case-insensitive, space-separated) in the configuration.

## Kit rejected by the service (fixed after 1.0.0)

**Symptom.** Plugin 1.0.0: `Creating sandbox on account 'X' failed: HTTP 400 ... kits[0].artifact.source ... required`.

**Cause.** 1.0.0 sent kits only by name (`{"artifact":{"ref":{"ref":"shell"}}}`). The live API requires the kit
artifact itself and rejects the request.

**Fix.** Upgrade to the next release: kits are now launched as their template image
(`imageRef: docker/sandbox-templates:<kit>`), verified against the live service. On 1.0.0, work around it by setting
*Default image* (or the step's `image`) to `docker/sandbox-templates:shell` or any image with `bash` and `tar`.

## Sandbox failed to start

**Symptoms.** `Sandbox X failed to start: <reason>`, `Sandbox X is stopped instead of running`, or
`Timed out waiting for sandbox X to start (status '...')` (limit: 5 minutes), `Sandbox X reported no endpoint URI`.

**Cause.** The service could not schedule the sandbox, the requested resources are not available, or the endpoint was
not published.

**Fix.** Retry, try a smaller `size`, or check the Docker service status. The plugin deletes the sandbox that failed to start.

## Sandbox image has no bash or tar

**Symptoms.** `Preparing the work directory in the sandbox failed (exit N): ...`, `Extracting the workspace in the sandbox failed (exit N): ...`,
`Collecting artifacts failed (exit N): ...`, or a script that cannot start.

**Cause.** The plugin runs everything through `bash` and `tar`: the script as `bash -e`, the workspace with
`tar -xzf`, artifacts with `bash` globbing and `tar -czf`.

**Fix.** Use a kit/image that includes `bash` and `tar` (the default `shell` kit is expected to). For custom images the
plugin checks this first and fails with `Image <ref> must provide bash and tar`, see
[Custom image problems](#custom-image-problems). Without an image, you can set `uploadWorkspace: false` and no
`artifacts` if you only need the script to run (bash is still needed for the script).

## Custom image problems

Raw-image sandboxes were verified against the live service with `ubuntu:24.04` (runs as root, has bash and tar).
Private registries and minimal images are not verified; reports are welcome.

**Image missing bash or tar.** `Image <ref> must provide bash and tar (alpine: apk add bash tar)`. The plugin probes
`command -v bash` and `command -v tar` right after the sandbox starts. Use an image that ships both, or derive your own
(`FROM alpine` + `RUN apk add --no-cache bash tar`). The sandbox is deleted and the account slot released.

**Image pull failure.** `Sandbox X failed to start: ...` or a 4xx when creating the sandbox, for an unknown tag or a
private registry. The plugin has no way to pass registry credentials (unverified whether the service supports private
registries); use a public image or a managed `images/<id>` created in your Docker account.

**Sandbox exits immediately or never becomes ready.** The plugin replaces the image command with `sh -c <imageCommand>`
(`sleep infinity` by default). The image needs `sh`; set `imageCommand` if the default keep-alive does not work for it.

**Permission denied in the work directory.** Image users are often not root. The default work directory in image mode is
`/tmp/workspace`, which is normally writable. If you set `workDir` yourself, choose a path the image user can create
(for example not `/home/agent/workspace`, `/opt` or `/`).

## Command not found in the script

**Cause.** The sandbox only contains what the kit/image provides.

**Fix.** Install what you need at the start of the script (for example with the package manager of the image), or pick a
kit that ships the tools.

## Upload size limit

**Symptom.** `Workspace archive exceeds the upload limit of N MB; narrow 'includes'/'excludes' or raise maxUploadMb`.

**Cause.** The compressed workspace is larger than `maxUploadMb` (default 200).

**Fix.** Exclude what the build does not need, for example `excludes: '.git/**,node_modules/**,target/**'`, or raise
`maxUploadMb`. Uploading many files is also slow; consider `uploadWorkspace: false` and cloning inside the sandbox.

## Artifacts

* `WARNING: no files matched artifacts '...'`: nothing matched. Patterns are relative to `workDir`. With
  `allowEmptyArtifacts: false` this fails the build with `No files matched artifacts '...'`.
* `Unsupported artifact pattern '...': use relative Ant-style globs made of letters, digits and _ . / * ? @ + = [ ] -`:
  absolute paths, `..`, spaces inside a pattern and characters such as `{}` are not allowed. Separate several patterns with commas.
* Dot files are not matched by `*` and `**`; list them explicitly.

## Label and name limits

Sandbox display names are `jk-<job>-<build>` with characters other than letters, digits, `_` and `-` replaced by `-`
and cut to 64 characters (the end is kept). Label values (`jenkins-job`, `jenkins-account`) replace other characters with
`_` and are cut to 63 characters. For very long folder/job names the label is therefore truncated; this is cosmetic, the
orphan cleanup relies on the encoded `jenkins-run` label.

## Invalid parameters

| Message | Cause |
| --- | --- |
| `sandboxRun: 'script' must not be empty` | Blank script |
| `Unknown sandbox size 'x'; use micro, small, medium, large or xl` | Wrong `size` |
| `Invalid env entry (expected NAME=value): ...` | `env` entry without `=` or with an invalid name |
| `sandboxRun: 'workDir' must be an absolute path` | `workDir` does not start with `/` |
| `sandboxRun needs a workspace ...` | Used outside `node { }`, or with artifacts and no workspace |

## Script failed or timed out

* `Sandbox script exited with code N`: the script returned non-zero. Use `returnStatus: true` to handle it yourself.
  Remember the script runs with `bash -e`.
* `Sandbox script timed out after N minute(s)`: the process got TERM, then KILL. Raise `timeoutMinutes` (`0` = none).
  The build fails even with `returnStatus: true`.
* The sandbox itself expires after `ttlMinutes` (default 60) and is then deleted by Docker; a long script needs a TTL
  longer than its timeout.

## Build failed after a controller restart

**Symptom.** A running `sandboxRun` fails after Jenkins restarted; the run fails. (The step's own message for this case is `sandboxRun cannot be resumed after a Jenkins restart`; Jenkins may report the failure with its own wording.)

**Cause.** The step is not resumable: the state of the run lives in memory.

**Fix.** Re-run the build. The orphaned sandbox is removed by the [orphan cleanup](configuration.md#orphan-cleanup)
(after it is older than 15 minutes and its build is over) or by its TTL. Use `/safeRestart` to let running builds finish
first. The per-account counters restart from zero.

## Leftover sandboxes

* `WARNING: could not delete sandbox X (...); the orphan cleanup will retry`: deletion failed three times. It is retried
  by the cleanup job if the cleanup is enabled.
* Sandboxes kept with `keepSandbox: true` carry the `jenkins-keep` label, are skipped by the cleanup and live until their TTL.
  Delete them manually in your Docker account if needed.

## Still stuck?

Collect the build console log (remove any secrets), the plugin version and the Jenkins version and
[open an issue](https://github.com/npv2k1/docker-sandbox-plugin/issues/new/choose). For security problems follow
[SECURITY.md](../SECURITY.md) instead.

## Cannot reach Docker behind a proxy

**Symptom.** *Test connection* or the build fails with a connect timeout / `ConnectException` to `hub.docker.com` or
`connect.docker.com`.

**Cause.** Jenkins needs a proxy to reach the internet.

**Fix.** Configure it in *Manage Jenkins > Plugins > Advanced settings > HTTP Proxy Configuration* (the plugin uses it,
including no-proxy hosts and proxy credentials), or start Jenkins with `-Dhttps.proxyHost=... -Dhttps.proxyPort=...`.
Allow `hub.docker.com`, `connect.docker.com` and `*.sbx.sandboxes-cloud.docker.com` (the per-sandbox endpoints).
