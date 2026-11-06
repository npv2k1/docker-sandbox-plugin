# Docker Cloud Sandboxes plugin for Jenkins

Run Jenkins build scripts in isolated [Docker Cloud Sandboxes](https://docs.docker.com/ai/sandboxes/cloud/)
(cloud microVMs): the workspace is uploaded, the script runs in the sandbox with its output streamed to the
build log, artifacts are copied back and the sandbox is deleted. Several Docker accounts can be configured and
builds are load-balanced across them (each Docker account has its own quota, 10 concurrent sandboxes by default).

*Tiếng Việt: plugin chạy script build trong Docker Cloud Sandbox, hỗ trợ nhiều tài khoản Docker và tự cân bằng
tải giữa các tài khoản; xem phần "Hướng dẫn nhanh" ở cuối.*

> The Docker Sandboxes API is experimental. See [Notes](#notes) for what was verified and what was not.

## Requirements

* Jenkins 2.541 or newer, Java 17+
* One or more Docker accounts with a **personal access token (PAT)** that has the
  **Allow Cloud Sandboxes access** permission (Docker Hub > Account settings > Personal access tokens)

## Setup

1. **Credentials**: add one *Username with password* credential per Docker account. Username = Docker ID,
   password = the PAT.
2. **Accounts**: *Manage Jenkins > System > Docker Cloud Sandboxes > Add Docker account*. Per account:

   | Field | Meaning |
   | --- | --- |
   | Account ID | Unique id used for pinning (`account: 'acc-eu'`) |
   | Docker ID + access token | the credential from step 1 |
   | Max concurrent sandboxes | Jenkins never runs more than this many on the account (default 10) |
   | Weight | share for *weighted random*, tie-breaker for *least loaded* |
   | Labels | space separated, e.g. `eu large`; builds can require labels |
   | Enabled | disabled accounts are skipped |

   *Test connection* exchanges the PAT and lists sandboxes, so a wrong PAT or a missing scope shows up right away.
3. Pick the **load-balancing strategy** and defaults (kit, size, TTL, wait/cooldown/failover limits).

The page *Manage Jenkins > Docker Cloud Sandboxes* shows active/max sandboxes, cooldowns, last errors and totals per
account, with a per-account connection test.

## Load balancing

| Strategy | Behavior |
| --- | --- |
| Least loaded (default) | lowest `active / max`; ties go to the higher weight, then list order |
| Round robin | cycles through the accounts, skipping full ones |
| Weighted random | random, proportional to weight |
| First available | list order = priority |

* Only enabled accounts that match the build's `account` / `accountLabel` and are not cooling down are candidates.
* When every candidate is busy the build waits (`Waiting for Docker sandbox capacity ...`) until a slot frees up
  or the configured wait time elapses. Aborting the build interrupts the wait.
* If creating a sandbox fails with *quota exhausted* or an authentication error, the account is put in cooldown
  (default 5 min) and the build fails over to another account (up to *maximum failover attempts*). Failures after
  the sandbox exists (for example a failing script) never fail over.
* Usage counters are in memory and start from zero after a restart; leftovers are handled by the orphan cleanup.

## Pipeline

```groovy
node {
  def r = sandboxRun(
    script: 'npm ci && npm run build',
    accountLabel: 'large',          // or account: 'acc-1'; omit both to let the strategy choose
    kit: 'shell', size: 'medium', ttlMinutes: 60,
    uploadWorkspace: true, includes: '**/*', excludes: '.git/**,node_modules/**',
    artifacts: 'dist/**',
    env: ['NODE_ENV=production'],
    timeoutMinutes: 30, keepSandbox: false, returnStatus: false)
  echo "exit=${r.exitCode} account=${r.accountId} sandbox=${r.sandboxId}"
}
```

| Parameter | Default | Description |
| --- | --- | --- |
| `script` | required | run with `bash -e` in the work directory |
| `account` / `accountLabel` | any | pin to an account / require all labels |
| `kit` | global default (`shell`) | kit applied to the sandbox; empty = none |
| `size` | global default (`small`) | `micro` 1 CPU/2 GiB, `small` 2/4, `medium` 4/8, `large` 8/16, `xl` 16/32 |
| `ttlMinutes` | global default (60) | sandbox lifetime; the sandbox is deleted at expiry |
| `uploadWorkspace`, `includes`, `excludes`, `maxUploadMb` | `true`, `**/*`, none, 200 | workspace archive uploaded before the script |
| `artifacts` | none | comma separated globs (relative to the work dir) copied back to the workspace; missing files only warn unless `allowEmptyArtifacts: false` |
| `env` | none | `NAME=value` entries for the script process (not stored on the sandbox) |
| `timeoutMinutes` | 60 | terminates the script; `0` disables |
| `keepSandbox` | `false` | skip the delete (TTL still applies); such sandboxes are never touched by the orphan cleanup |
| `returnStatus` | `false` | do not fail on a non-zero exit code |
| `workDir` | `/home/agent/workspace` | absolute path inside the sandbox |

The step needs a workspace, so use it inside `node { }` (or `agent` in declarative pipelines). The result exposes
`exitCode`, `accountId` and `sandboxId` and works in the Groovy sandbox.

## Freestyle

Add the build step **Run script in Docker Cloud Sandbox**. Same options; *Do not fail on non-zero exit* marks the
build unstable instead of failing it.

## Safety

* The sandbox is deleted and the account slot released on every path: success, script failure, timeout, build abort,
  errors while starting.
* Orphaned sandboxes (controller crash) carry labels `jenkins-instance`, `jenkins-job`, `jenkins-build`,
  `jenkins-run`, `jenkins-account`; a job runs every 10 minutes and deletes this instance's sandboxes whose build is
  over and which are older than 15 minutes.
* PATs and access tokens are never logged; messages that reach the build log are masked.

## Limits

* The archive uploaded to the sandbox is limited by `maxUploadMb`; large workspaces should use `includes`/`excludes`.
* Credentials for the sandbox endpoint last at most 5 minutes and are renewed automatically.
* `sandboxRun` cannot resume after a controller restart (the run fails and the orphan cleanup removes the sandbox).
* The sandbox image needs `bash` and `tar`.

## Notes

Where the plan and the downloaded OpenAPI spec (`src/test/resources/openapi/sandboxes-api.yaml`) differ, the spec
was followed:

* `DELETE /v1/sandboxes/{id}` requires an `If-Match` etag (428 without, 412 when stale). The client reads the
  current etag first and retries once on 412.
* Signals are `POST /v1/processes/{id}/signal` with body `{"signal":"term"}` (`term`, `kill`, `int`, ...).
* Error codes are camelCase in the spec (`resourceExhausted`); they are matched case-insensitively and ignoring
  underscores, so `RESOURCE_EXHAUSTED` works too.
* The sandbox lifetime is requested with `features.timeouts` (`timeout: "3600s"`, `onTimeout: "delete"`).
* Kits are `{"artifact": {"ref": {"ref": "shell", "kind": ""}}}`. The spec lists an `inline` field as required for
  `KitArtifactInput`; the plugin does not send one. **Unverified** against the live service; set the default kit to
  empty to create sandboxes without a kit if the service rejects it.
* Build `env` is passed to the script process only; the create-time `environment` field is "non-secret metadata"
  and is not used.
* Endpoint credentials use `ttl: "300s"`; the permissions are `sandboxesExec`, `sandboxesFilesRead`,
  `sandboxesFilesWrite`.
* `networkPolicies` from the plan are not sent.
* The spec has no description of the Docker token exchange. The plugin uses `POST <authUrl>/v2/auth/token`
  (`{"identifier","secret"}` -> `access_token`) as documented for Docker Hub; **unverified** against the live
  Cloud Sandboxes service.
* The account pool is owned by the global configuration instead of being a separate `@Extension` (state is keyed by
  account id so it survives configuration saves).
* Integration tests use a stateful fake of the Docker APIs implemented as a WireMock response transformer; it runs
  scripts with local bash, so the real upload/stream/artifact path is exercised.

## Development

```
mvn -B -ntp verify
```

## CI/CD

| Workflow | Trigger | What it does |
|---|---|---|
| `.github/workflows/ci.yml` | push to `main`, every PR | `mvn verify` (tests + SpotBugs) on JDK 17 and 21; uploads the `.hpi` (JDK 21) and test reports on failure |
| `.github/workflows/release.yml` | tag `vX.Y.Z` or manual run with a version | builds with that version, creates a GitHub Release with `docker-sandbox-X.Y.Z.hpi` + `.sha256`, then runs the `deploy` job |
| `.github/dependabot.yml` | weekly | dependency PRs for Maven and GitHub Actions |

Release:

```
git tag v1.0.0 && git push origin v1.0.0
```

Tags with a suffix (`v1.1.0-rc.1`) are published as pre-releases.

Deploy to your own Jenkins (optional): add repository secrets `JENKINS_URL`, `JENKINS_USER`,
`JENKINS_TOKEN` (an API token of an admin user). The `deploy` job uploads the `.hpi` to
`/pluginManager/uploadPlugin` and requests `/safeRestart`. Without these secrets the job is skipped.
To require approval before each deploy, add required reviewers to the `production` environment
(Settings → Environments).

Recommended: protect `main` (Settings → Branches) and require the two `Build & test` checks.

---

## Hướng dẫn nhanh (Tiếng Việt)

1. Tạo PAT trên Docker Hub với quyền **Allow Cloud Sandboxes access**.
2. Trong Jenkins thêm credential kiểu *Username with password* (Docker ID + PAT) cho mỗi tài khoản.
3. *Manage Jenkins > System > Docker Cloud Sandboxes*: thêm từng tài khoản (ID, credential, số sandbox đồng thời
   tối đa, trọng số, nhãn), chọn chiến lược cân bằng tải (least loaded / round robin / weighted random / first
   available) rồi bấm *Test connection*.
4. Dùng trong Pipeline: `sandboxRun(script: '...', accountLabel: 'large', artifacts: 'dist/**')` trong `node { }`,
   hoặc thêm build step *Run script in Docker Cloud Sandbox* cho Freestyle.
5. Khi hết hạn mức một tài khoản (quota), job tự chuyển sang tài khoản khác; khi tất cả đều bận job sẽ chờ.
   Sandbox luôn được xóa và slot luôn được trả lại, kể cả khi build lỗi hoặc bị hủy.
