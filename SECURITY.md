# Security Policy

## Supported versions

| Version | Supported |
| --- | --- |
| 1.x | Yes |
| < 1.0 | No |

Only the latest 1.x release receives security fixes.

## Reporting a vulnerability

**Please do not open a public issue for security problems.** Report them privately through GitHub Security Advisories:

<https://github.com/npv2k1/docker-sandbox-plugin/security/advisories/new>

Include the plugin and Jenkins versions, a description of the impact, steps to reproduce and, if you have one, a
suggested fix. Remove real tokens from anything you attach.

## Scope

In scope:

* Handling of Docker credentials: personal access tokens, exchanged access tokens and sandbox endpoint credentials
  (storage, transmission, exposure to users without permission).
* **Token leakage** into build logs, the management page, error messages, sandbox labels or metadata.
* **Sandbox cleanup**: sandboxes that are not deleted when they should be, or cleanup that deletes sandboxes it does not
  own (for example of another Jenkins instance).
* Missing permission checks on the plugin's configuration, connection tests and management page.
* Bypasses of the Script Security sandbox through the `sandboxRun` result.

Out of scope:

* Vulnerabilities of Docker Cloud Sandboxes itself or of Docker Hub (report them to Docker).
* Vulnerabilities of Jenkins core or of other plugins (see the
  [Jenkins security policy](https://www.jenkins.io/security/)).
* Code that a job author deliberately runs inside a sandbox; the script and its `env` are visible to that code by design.
* Findings that require administrator access to the Jenkins controller.

## Response expectations

This is a volunteer-maintained project. We aim to acknowledge reports within about 7 days, to give an assessment within
about 14 days, and to publish a fix and an advisory as soon as it is ready. We will credit reporters unless they prefer
to stay anonymous. Please allow time for a fix before disclosing the issue publicly.
