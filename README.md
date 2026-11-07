# Docker Cloud Sandboxes plugin for Jenkins

Run Jenkins build scripts in isolated [Docker Cloud Sandboxes](https://docs.docker.com/ai/sandboxes/cloud/) (cloud
microVMs), load-balanced across several Docker accounts.

[![CI](https://github.com/npv2k1/docker-sandbox-plugin/actions/workflows/ci.yml/badge.svg)](https://github.com/npv2k1/docker-sandbox-plugin/actions/workflows/ci.yml)
[![Latest release](https://img.shields.io/github/v/release/npv2k1/docker-sandbox-plugin?display_name=tag&sort=semver)](https://github.com/npv2k1/docker-sandbox-plugin/releases/latest)
[![License: MIT](https://img.shields.io/github/license/npv2k1/docker-sandbox-plugin)](LICENSE.md)

> **Status: Experimental.** The Docker Cloud Sandboxes API is experimental. The plugin's API flow (token exchange,
> create, exec, upload/download, process output, delete) has been verified against the live service; the Jenkins-side
> end-to-end run with a real account is still being validated. See
> [Architecture](docs/architecture.md#deviations-from-the-spec-and-unverified-assumptions) for details.

*Tiếng Việt: plugin chạy script build trong Docker Cloud Sandbox, hỗ trợ nhiều tài khoản Docker và tự cân bằng tải
giữa các tài khoản; xem phần [Hướng dẫn nhanh](#hướng-dẫn-nhanh-tiếng-việt) ở cuối.*

## Features

* `sandboxRun` Pipeline step and a *Run script in Docker Cloud Sandbox* Freestyle build step.
* Workspace is uploaded as a `tar.gz` archive (with include/exclude patterns and a size limit); the script runs with
  `bash -e` and its output is streamed live to the build log.
* Run in a **custom Docker image** (`image: 'maven:3.9-eclipse-temurin-21'`, or a managed `images/<id>`) instead of a
  kit.
* Artifacts (Ant-style globs) are copied back into the Jenkins workspace.
* **Multiple Docker accounts**, each with its own concurrency limit, weight and labels.
* Four load-balancing strategies: least loaded, round robin, weighted random, first available.
* Builds wait for free capacity, and fail over to another account when one is out of quota or rejects the
  credentials (with a per-account cooldown).
* The sandbox is deleted and the account slot released on every path (success, failure, timeout, abort); a periodic
  job removes orphaned sandboxes left behind by a controller crash.
* A *Manage Jenkins > Docker Cloud Sandboxes* page with live usage, cooldowns and last errors per account.
* Access tokens are never logged; messages that reach the build log are masked.

## How it works

```
lease account -> create sandbox -> upload workspace -> run script -> stream logs -> fetch artifacts -> delete
```

1. **Lease** a slot on an account chosen by the load-balancing strategy (waits if all are busy).
2. **Create** a sandbox (size, kit, TTL) on that account and wait until it is running.
3. **Upload** the workspace archive and extract it into the work directory.
4. **Run** the script with `bash -e`, **stream** its output into the build log.
5. **Fetch** the requested artifacts back into the workspace.
6. **Delete** the sandbox and release the slot, always.

Details and a sequence diagram: [docs/architecture.md](docs/architecture.md).

## Quick start

1. **Install** the latest `docker-sandbox-X.Y.Z.hpi` from [GitHub Releases](https://github.com/npv2k1/docker-sandbox-plugin/releases)
   (*Manage Jenkins > Plugins > Advanced settings > Deploy Plugin*, then restart). Requires Jenkins 2.541+ and
   Java 17. See [docs/installation.md](docs/installation.md).
2. **Create a Docker personal access token** with the **Allow Cloud Sandboxes access** permission (Docker Hub >
   Account settings > Personal access tokens). In Jenkins add a *Username with password* credential: username =
   Docker ID, password = the token.
3. **Add the account**: *Manage Jenkins > System > Docker Cloud Sandboxes > Add Docker account*, pick the credential
   and press *Test connection*. See [docs/configuration.md](docs/configuration.md).
4. **Run a first pipeline**:

   ```groovy
   node {
     def r = sandboxRun(script: 'echo hello from a sandbox && uname -a')
     echo "exit=${r.exitCode} account=${r.accountId} sandbox=${r.sandboxId}"
   }
   ```

## Usage examples

Build and collect artifacts, pinned to accounts that carry a label:

```groovy
node {
  checkout scm
  sandboxRun(
    script: 'npm ci && npm run build',
    accountLabel: 'large', size: 'medium',
    artifacts: 'dist/**',
    excludes: '.git/**,node_modules/**',
    env: ['NODE_ENV=production'])
  archiveArtifacts 'dist/**'
}
```

Do not fail the build on a non-zero exit code:

```groovy
node {
  def r = sandboxRun(script: './run-tests.sh', returnStatus: true)
  if (r.exitCode != 0) { unstable("tests failed with ${r.exitCode}") }
}
```

More examples (Maven with artifacts, pinning, parallel builds across accounts) and the full parameter reference are in
[docs/usage.md](docs/usage.md).

## Documentation

| Document | Contents |
| --- | --- |
| [docs/installation.md](docs/installation.md) | Requirements, install methods, dependencies, upgrade, uninstall |
| [docs/configuration.md](docs/configuration.md) | Docker PAT, credentials, global settings, account fields, load balancing, cleanup, JCasC |
| [docs/usage.md](docs/usage.md) | `sandboxRun` reference, result object, Freestyle step, pipeline examples |
| [docs/architecture.md](docs/architecture.md) | Components, API call sequence, security model, spec deviations |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Common errors with cause and fix |
| [docs/development.md](docs/development.md) | Build, test, project layout, CI/CD and releases |
| [CHANGELOG.md](CHANGELOG.md) | Release notes |

## Contributing, security, license

* Contributions are welcome: see [CONTRIBUTING.md](CONTRIBUTING.md) and the [Code of Conduct](CODE_OF_CONDUCT.md).
* Please report vulnerabilities privately as described in [SECURITY.md](SECURITY.md).
* Licensed under the MIT License, see [LICENSE.md](LICENSE.md).

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
