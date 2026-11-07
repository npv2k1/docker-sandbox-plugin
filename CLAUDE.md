# CLAUDE.md

## Git workflow (owner's rule)
- Commit and push directly to `main`. Do NOT create feature branches or pull requests.
- Before pushing: run `mvn -B -ntp verify` and make sure it passes.
- After pushing: GitHub Actions `CI` runs on `main`; if it goes red, fix it with a new commit on `main`.
- Release: Actions → Release → Run workflow (bump patch/minor/major), push tag `vX.Y.Z`, or push a commit to `main` with `[release]` / `[release:minor]` / `[release:major]` in its message (tag pushes and workflow dispatch are blocked from Claude sessions, so use the commit form there) (see docs/development.md "CI/CD").

## Project
- Jenkins plugin (Maven, Java 17), package `io.jenkins.plugins.dockersandbox`.
- Docker Sandboxes OpenAPI spec: `src/test/resources/openapi/sandboxes-api.yaml` (source of truth for API shapes).
- Documentation lives in `docs/` (installation, configuration, usage, architecture, troubleshooting, development); keep it in sync with code changes.
