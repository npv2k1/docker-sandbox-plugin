# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Custom Docker images: `image` and `imageCommand` options on `sandboxRun` and the Freestyle step, and a global
  *Default image*. Precedence is step image, step kit, global image, global kit. `images/<id>` values are sent as
  `image`, others as `imageRef`, with `startupExecution.rawImage` keeping the sandbox alive. The work directory defaults
  to `/tmp/workspace` in image mode, the image needs `bash` and `tar` (checked after start), and the management page
  shows the default image.

### Fixed

- Sandbox creation failed on the live service with HTTP 400 (`kits[0].artifact.source` required) because kits were
  sent by name only. Kits are now launched as their template image (`docker/sandbox-templates:<kit>`, for example
  `shell`). The whole flow (create, exec, upload, process output, artifacts download, delete) was verified against the
  live Docker Cloud Sandboxes service, both with the `shell` template and a raw `ubuntu:24.04` image.
- HTTP calls ignored proxies: Jenkins behind an HTTP proxy could not reach Docker. The plugin now uses the proxy
  configured in Jenkins (*Manage Jenkins > Plugins > Advanced > HTTP Proxy*, incl. no-proxy hosts and authentication),
  falling back to the JVM proxy settings (`-Dhttps.proxyHost`).

## [1.0.0] - 2026-11-07

First release. Experimental: the Docker Cloud Sandboxes API is experimental and the plugin has not yet been verified
against the live service.

### Added

- `sandboxRun` Pipeline step and *Run script in Docker Cloud Sandbox* Freestyle build step: the workspace is uploaded,
  the script runs with `bash -e` in the sandbox, its output is streamed to the build log and artifacts are copied back.
- Step result with `exitCode`, `accountId` and `sandboxId`; `returnStatus` option (Freestyle marks the build unstable).
- Options for account pinning and labels, kit, size (`micro` to `xl`), sandbox TTL, workspace includes/excludes and
  upload limit, artifacts, environment variables, timeout, work directory and `keepSandbox`.
- Multi-account pool with a per-account concurrency limit, weight, labels and enable switch.
- Load-balancing strategies: least loaded, round robin, weighted random, first available.
- Waiting for free capacity, account cooldown after quota or authentication failures and failover to other accounts.
- Docker Sandboxes API client with PAT token exchange and caching, bounded retries honoring `Retry-After`, etag-based
  deletion and short-lived endpoint credentials.
- Global configuration under *Manage Jenkins > System > Docker Cloud Sandboxes* with defaults, per-account
  *Test connection* and inline help; configuration symbols `dockerSandbox` and `dockerSandboxAccount`.
- *Manage Jenkins > Docker Cloud Sandboxes* page with live usage, cooldowns, last errors and connection tests.
- Periodic orphan cleanup of sandboxes left behind by crashed controllers.
- Masking of Docker tokens in everything written to the build log.
- Tests against a stateful WireMock fake of the Docker APIs.
- GitHub Actions CI (JDK 17 and 21), release workflow producing a GitHub Release with the `.hpi`, optional deployment to
  a Jenkins instance, Dependabot with auto-merge.
- Documentation, contributing guide, code of conduct, security policy, issue and pull request templates.
