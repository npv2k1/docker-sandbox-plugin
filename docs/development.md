# Development

## Requirements

* JDK 17 or 21 (CI builds with both) and Maven
* Linux or macOS for the full test suite: the integration tests fake the sandbox by running scripts with local `bash`

## Build and test

```bash
mvn -B -ntp verify      # compile, tests and SpotBugs; produces target/docker-sandbox.hpi
mvn hpi:run             # start a development Jenkins at http://localhost:8080/jenkins
```

`mvn hpi:run` loads the plugin into a throw-away Jenkins in `work/`. Configure an account pointing to a mock
(*Advanced > Sandboxes API URL / Docker auth URL*) or to the real service.

To run a single test class: `mvn -B -ntp test -Dtest=SandboxRunTest`.

The version is `${revision}${changelist}` (`1.0.0-SNAPSHOT` locally). The release workflow overrides it with
`-Drevision=X.Y.Z -Dchangelist=`.

## Project layout

```
pom.xml                         parent: org.jenkins-ci.plugins:plugin, Jenkins baseline 2.541
Jenkinsfile                     buildPlugin() for ci.jenkins.io style builds (JDK 17 and 21, Linux)
src/main/java/io/jenkins/plugins/dockersandbox/
  api/                          HTTP clients, token provider, error mapping; api/model/ JSON records
  account/                      accounts, pool, selector, strategies, API factory
  SandboxJobRunner.java         the run (shared by step and builder)
  SandboxRunStep.java           Pipeline step
  SandboxBuilder.java           Freestyle build step
  DockerSandboxGlobalConfiguration.java, OrphanSandboxCleanup.java, DockerSandboxManagementLink.java
src/main/resources/             Jelly forms and inline help (one help-<field>.html per field)
src/test/java/...               unit and JenkinsRule tests, FakeDockerCloud
src/test/resources/openapi/     sandboxes-api.yaml, the API source of truth
.github/                        workflows, Dependabot, issue and PR templates, CODEOWNERS
docs/                           this documentation
```

## Test strategy

* **No live Docker service is used.** `FakeDockerCloud` is a stateful fake of the Docker auth, management and sandbox
  endpoint APIs, implemented as a WireMock response transformer. It really runs the uploaded scripts with local `bash`
  in a per-sandbox directory, so upload, streaming, exit codes, artifacts and deletion are exercised end to end. It
  can simulate quotas, exhausted accounts and failures per Docker ID.
* **Unit tests** cover the HTTP clients against WireMock (`HttpDockerSandboxApiTest`, `HttpSandboxEndpointApiTest`,
  `TokenProviderTest`, with a `MutableClock`), the account pool (`AccountPoolTest`: strategies, cooldowns, waiting,
  50-thread concurrency limit, failover selection).
* **Integration tests** use `JenkinsRule` (`SandboxRunTest`: Pipeline and Freestyle runs, failover, waiting, abort,
  `returnStatus`; `OrphanSandboxCleanupTest`; `DockerSandboxManagementLinkTest`).
* `SandboxJobRunner.setTiming(...)` shortens polling intervals in tests.
* Because the real service is not covered, changes to API shapes must be checked against
  `src/test/resources/openapi/sandboxes-api.yaml`.

## Code style

* Java 17, formatting as in the existing sources (4 spaces, no wildcard imports); follow the Jenkins plugin conventions
  of the parent POM.
* `mvn verify` runs SpotBugs; fix the findings instead of suppressing them where possible.
* Every user-visible field has a Jelly entry and an inline `help-<field>.html`; keep them in sync with the code defaults.
* Never log a token: route any text that may contain API output through `SandboxJobRunner.mask(...)`.
* Keep README/docs accurate when behavior or defaults change (see [CONTRIBUTING.md](../CONTRIBUTING.md)).

## CI/CD

| Workflow | Trigger | What it does |
| --- | --- | --- |
| `.github/workflows/ci.yml` (**CI**) | push to `main`, every pull request, manual | `mvn -B -ntp verify` (tests and SpotBugs) on JDK 17 and 21 (jobs `Build & test (JDK 17/21)`); uploads the `.hpi` (JDK 21) and, on failure, the surefire reports. PR runs are cancelled when superseded; runs on `main` never are |
| `.github/workflows/release.yml` (**Release**) | manual run, push of a tag `vX.Y.Z`, or a push to `main` whose commit subject contains `[release]` | Resolves the version, builds with it, publishes a GitHub Release with `docker-sandbox-X.Y.Z.hpi` and `.sha256`, then runs the `deploy` job |
| `.github/dependabot.yml` | weekly | Dependency PRs for Maven and GitHub Actions |
| `.github/workflows/dependabot-automerge.yml` | `CI` finished successfully for a Dependabot PR | Merges that PR (only the exact commit CI tested) and deletes its branch. It runs from `main`'s copy and never checks out PR code. PRs that change `.github/workflows/*` (GitHub Actions bumps) need secret `AUTOMERGE_TOKEN` (fine-grained PAT: Contents, Pull requests, Workflows read/write) in both Actions and Dependabot secrets; without it they are left open with a warning |

### Releasing

From a commit: push a commit to `main` whose **first line** contains `[release]` (patch), `[release:minor]` or
`[release:major]` (markers in the commit body are ignored), for example `git commit --allow-empty -m "Release [release]" && git push origin main`. The next
version is computed from the latest tag exactly like the manual run. Other pushes to `main` do not release.

Preferred: *Actions > Release > Run workflow* on `main` and choose **bump** `patch`, `minor` or `major`. The next version is
computed from the latest `vX.Y.Z` tag (the first release is `1.0.0`) and the tag is created on the current `main` commit.
To force a version, fill **version** (for example `2.0.0` or `2.0.0-rc.1`); it must be semver and the tag must not exist.
The manual run is refused on any branch other than `main`.

Alternative: push a tag yourself.

```bash
git tag v1.0.0 && git push origin v1.0.0
```

Versions with a suffix (`1.1.0-rc.1`) are published as pre-releases. Before a release, move the `Unreleased` entries of
[CHANGELOG.md](../CHANGELOG.md) under the new version.

### Deploying to your own Jenkins (optional)

The `deploy` job runs after `release` in the `production` environment. It uploads the `.hpi` to
`/pluginManager/uploadPlugin` and requests `/safeRestart`. It needs the repository secrets:

| Secret | Content |
| --- | --- |
| `JENKINS_URL` | Base URL of your Jenkins |
| `JENKINS_USER` | An administrator's user name |
| `JENKINS_TOKEN` | That user's API token |

If any is missing the job logs a notice and skips the deployment. To require approval before each deployment, add
required reviewers to the `production` environment (*Settings > Environments*).

### Recommended repository settings

Protect `main` (*Settings > Branches*) and require the two `Build & test` checks. Maintainers commit to `main` directly;
external contributions arrive as pull requests from forks.
