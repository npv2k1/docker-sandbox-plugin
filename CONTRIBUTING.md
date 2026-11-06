# Contributing

Thank you for your interest in the Docker Cloud Sandboxes plugin for Jenkins. By participating you agree to follow the
[Code of Conduct](CODE_OF_CONDUCT.md). Contributions are licensed under the project's [MIT License](LICENSE.md).

## Reporting bugs

1. Search the [existing issues](https://github.com/npv2k1/docker-sandbox-plugin/issues) first.
2. Check [docs/troubleshooting.md](docs/troubleshooting.md).
3. Open a [bug report](https://github.com/npv2k1/docker-sandbox-plugin/issues/new/choose) and fill in the form: Jenkins version, plugin
   version, load-balancing strategy, number of accounts and the console log. **Remove all secrets** (tokens, credentials,
   internal URLs) from logs before pasting.

Do **not** report security vulnerabilities in public issues; follow [SECURITY.md](SECURITY.md).

## Proposing features

Open a *Feature request* issue describing the problem first, then the proposed solution and alternatives. For larger
changes please agree on the approach in the issue before you invest time in a pull request. Remember that the Docker
Cloud Sandboxes API is experimental, so features should follow the OpenAPI spec in
`src/test/resources/openapi/sandboxes-api.yaml`.

## Development setup

Requirements: JDK 17 or 21 and Maven. Details are in [docs/development.md](docs/development.md).

```bash
git clone https://github.com/<you>/docker-sandbox-plugin.git
cd docker-sandbox-plugin
mvn -B -ntp verify     # tests + SpotBugs
mvn hpi:run            # development Jenkins on http://localhost:8080/jenkins
```

## Workflow

* **Maintainers** commit directly to `main` (no branches, no pull requests) and make sure `mvn -B -ntp verify` passes
  before pushing.
* **External contributors** fork the repository, create a branch in the fork and open a pull request against `main`.
  Keep each pull request focused on one change.

## Commit messages

* Short imperative summary line (about 72 characters or fewer), for example `Add cooldown to the management page`.
* Optional body explaining *why*, wrapped at about 72 characters.
* Reference issues with `Fixes #123` when applicable.
* Never include secrets, tokens or personal data.

## Pull request checklist

* [ ] `mvn -B -ntp verify` passes locally (tests and SpotBugs).
* [ ] New behavior has tests; bug fixes have a regression test. Tests must not need the live Docker service: extend
  `FakeDockerCloud` when needed.
* [ ] Documentation is updated (README, `docs/`, inline `help-*.html` files and [CHANGELOG.md](CHANGELOG.md) under *Unreleased*).
* [ ] No secrets in code, tests or logs; any text that may contain API output is passed through `SandboxJobRunner.mask`.
* [ ] User-visible defaults in Java, Jelly forms and docs agree.

Continuous integration (workflow **CI**) runs the build on JDK 17 and 21 for every pull request.
