# CLAUDE.md

## Git workflow (owner's rule)
- Commit and push directly to `main`. Do NOT create feature branches or pull requests.
- Before pushing: run `mvn -B -ntp verify` and make sure it passes.
- After pushing: GitHub Actions `CI` runs on `main`; if it goes red, fix it with a new commit on `main`.
- Release: Actions → Release → Run workflow (bump patch/minor/major), or push tag `vX.Y.Z` (see README "CI/CD").

## Project
- Jenkins plugin (Maven, Java 17), package `io.jenkins.plugins.dockersandbox`.
- Docker Sandboxes OpenAPI spec: `src/test/resources/openapi/sandboxes-api.yaml` (source of truth for API shapes).
