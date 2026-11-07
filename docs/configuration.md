# Configuration

All plugin settings live in *Manage Jenkins > System > Docker Cloud Sandboxes* (administrators only). Live usage is
shown on *Manage Jenkins > Docker Cloud Sandboxes* ([management page](#management-page)).

## Docker personal access token

Every Docker account needs a personal access token (PAT):

1. Sign in to Docker Hub, open *Account settings > Personal access tokens* and generate a token.
2. Grant it the **Allow Cloud Sandboxes access** permission. Without it the token exchange or the sandbox calls are
   rejected with an authentication error (see [Troubleshooting](troubleshooting.md#authentication-failed-http-401403)).
3. Copy the token once; Docker does not show it again.

The plugin exchanges the PAT for a short-lived access token (`POST <Docker auth URL>/v2/auth/token`), caches it per
account and renews it before it expires or after an HTTP 401.

## Jenkins credentials

Create one **Username with password** credential per Docker account (*Manage Jenkins > Credentials*):

| Field | Value |
| --- | --- |
| Username | The Docker ID |
| Password | The personal access token |
| ID | Anything unique, for example `docker-acc-eu` |

Credentials are looked up in the global (Jenkins root) scope with the account's *Docker auth URL* as the URI
requirement. They are read at run time, so rotating the PAT in the credential takes effect for new builds without
touching the plugin settings.

## Global settings

*Manage Jenkins > System > Docker Cloud Sandboxes*. Labels and defaults below are the UI labels.

| UI label | Field | Default | Meaning |
| --- | --- | --- | --- |
| Accounts | `accounts` | none | The Docker accounts, see [account fields](#account-fields). IDs must be unique and non-empty, otherwise the form is rejected |
| Load-balancing strategy | `strategy` | Least loaded | See [strategies](#load-balancing-strategies) |
| Default kit | `defaultKit` | `shell` | Sandbox template used when a build sets neither `image` nor `kit`; `shell` starts `docker/sandbox-templates:shell` (verified against the live service: has bash, tar, gzip, git, curl, user `agent`, `/home/agent/workspace`) |
| Default image | `defaultImage` | empty | Docker image (`node:22` or managed `images/<id>`) used when a build sets neither `image` nor `kit`. When set it wins over *Default kit* and no kit is sent. Precedence: step `image` > step `kit` > *Default image* > *Default kit*. The image needs `bash` and `tar`; the keep-alive command is `sleep infinity` unless the build sets `imageCommand`. Verified against the live service with `ubuntu:24.04` (create, exec, file upload/download, process output, delete) |
| Default size | `defaultSize` | `small` | Size when a build does not set `size`: `micro`, `small`, `medium`, `large`, `xl` |
| Default sandbox TTL (minutes) | `defaultTtlMinutes` | `60` | Sandbox lifetime when a build does not set `ttlMinutes`. Minimum 1 |

Under **Advanced**:

| UI label | Field | Default | Meaning |
| --- | --- | --- | --- |
| Wait for capacity (minutes) | `leaseTimeoutMinutes` | `30` | How long a build waits for a free slot before failing. Minimum 1 |
| Account cooldown after quota/auth failure (minutes) | `cooldownMinutes` | `5` | How long a failing account is skipped. `0` disables the cooldown |
| Maximum failover attempts | `maxFailoverAttempts` | `3` | Number of accounts a build tries when creating the sandbox fails. Minimum 1 |
| Delete orphaned sandboxes of this Jenkins | `orphanCleanupEnabled` | enabled | Switch for the [orphan cleanup](#orphan-cleanup) |

Sizes:

| Size | CPUs | Memory |
| --- | --- | --- |
| `micro` | 1 | 2 GiB |
| `small` | 2 | 4 GiB |
| `medium` | 4 | 8 GiB |
| `large` | 8 | 16 GiB |
| `xl` | 16 | 32 GiB |

The plugin also keeps a generated, stable instance id (not editable) that it writes into the labels of every sandbox
so that the orphan cleanup only touches sandboxes of this Jenkins.

## Account fields

Press **Add Docker account** for each account.

| UI label | Field | Default | Meaning |
| --- | --- | --- | --- |
| Account ID | `id` | required | Unique id; letters, digits, `.`, `_`, `-`. Used for pinning (`account: 'acc-eu'`) and as key of the runtime state |
| Display name | `displayName` | the Account ID | Friendly name shown on the management page |
| Docker ID + access token | `credentialsId` | required | The *Username with password* credential from above |
| Max concurrent sandboxes | `maxConcurrent` | `10` | Jenkins never runs more than this many sandboxes on the account. Docker allows 10 per account by default |
| Weight | `weight` | `1` | Share for *Weighted random*, tie-breaker for *Least loaded* |
| Labels | `labels` | empty | Space-separated, case-insensitive, for example `eu large`. Builds can require labels with `accountLabel` |
| Enabled | `enabled` | enabled | Disabled accounts are never used for new sandboxes |
| Sandboxes API URL (Advanced) | `apiUrl` | `https://connect.docker.com/sandboxes` | Base URL of the management API. Change only for testing |
| Docker auth URL (Advanced) | `authUrl` | `https://hub.docker.com` | Base URL for the PAT exchange. Change only for testing |

**Test connection** exchanges the PAT and lists one page of sandboxes. A wrong PAT or a missing permission shows up
immediately. On success the message reads `Connected. N sandbox(es) visible on this account.`

## Load-balancing strategies

Only accounts that are enabled, match the build's `account` / `accountLabel`, are not cooling down and have a free
slot (`active < max`) are candidates.

| Strategy (UI label) | Behavior |
| --- | --- |
| Least loaded (default) | Lowest `active / max`. Ties go to the higher weight, then to the first in the list |
| Round robin | Cycles through the accounts in list order, skipping the ones without capacity |
| Weighted random | Random pick among the candidates, probability proportional to weight |
| First available (priority order) | The first candidate in the list; list order is the priority |

## Capacity, cooldown, failover and waiting

* **Waiting.** If every matching account is full or cooling down, the build logs
  `Waiting for Docker sandbox capacity (all N accounts busy)` and waits until a slot frees up or a cooldown ends. After
  *Wait for capacity* minutes it fails with `Timed out after N minute(s) waiting for Docker sandbox capacity ...`.
  Aborting the build interrupts the wait.
* **No match.** If no enabled account matches the pinned id or labels, the build fails immediately with
  `No enabled Docker sandbox account matches ...`.
* **Cooldown.** When creating a sandbox fails with *quota exhausted* (HTTP 429 or error code `resourceExhausted`) or
  an authentication error (HTTP 401/403, `unauthenticated`, `permissionDenied`), or when the account's credentials
  cannot be found, the account is put into cooldown for *Account cooldown* minutes and its failure counter and last
  error are updated.
* **Failover.** The build then retries on another matching account, up to *Maximum failover attempts* attempts in
  total. Accounts that already failed for this build are not tried again. When none is left the build fails with
  `No Docker sandbox account left to fail over to` or `Could not create a Docker sandbox on any account ...`.
* **Persistent sandboxes.** An existing persistent sandbox is leased on the account that holds it; the build waits for
  capacity there instead of failing over. Only a new one is created with failover. The per-name wait for another build
  uses the same *Wait for capacity* limit.
* **No failover after the sandbox exists.** A failing script, a timeout, an upload error and similar problems fail the
  build without trying another account. Other errors while creating the sandbox also fail the build directly.
* **Counters** (active sandboxes, totals, cooldowns) are kept in memory only and start from zero after a controller
  restart. They survive saving the configuration because they are keyed by account ID, so renaming an ID resets its
  counters.

## Orphan cleanup

Every sandbox created by the plugin carries these labels: `jenkins-instance`, `jenkins-job`, `jenkins-build`,
`jenkins-run`, `jenkins-account` (and `jenkins-keep` when `keepSandbox` is set; persistent sandboxes carry `jenkins-persistent=<name>` instead). If the controller crashes, sandboxes may
outlive their builds. A periodic job (*Docker sandbox orphan cleanup*, every 10 minutes) lists the sandboxes of every
configured account and deletes those that

* carry this Jenkins' `jenkins-instance` id,
* do **not** carry `jenkins-keep` or `jenkins-persistent` (persistent sandboxes are long-lived by design and are only
  removed by `afterRun: 'delete'`, `sandboxManage` or the management page),
* belong to a build that is no longer running (or whose job is gone), and
* were created more than 15 minutes ago.

Disable it with *Delete orphaned sandboxes of this Jenkins* (Advanced). Sandboxes always expire with their TTL anyway.

## Management page

*Manage Jenkins > Docker Cloud Sandboxes* (Administer permission required) shows the active strategy and, per account:
enabled flag, active / max sandboxes, labels, remaining cooldown, last error, total runs, total failures and the
result of the last connection test, with a button to test the connection again.

Below the table, **Persistent sandboxes** lists the long-lived sandboxes of this Jenkins on every enabled account (name,
status, created, account, sandbox id) with **Stop** and **Delete** buttons (POST, Administer permission). A sandbox that
a running build holds cannot be stopped or deleted from here. Stopped persistent sandboxes keep their storage and count
toward the account's stored-sandbox quota (default 50). See [Persistent sandboxes](usage.md#persistent-sandboxes).

## Configuration as Code

The global configuration has the symbol `dockerSandbox` and accounts have the symbol `dockerSandboxAccount`, and all
fields are bindable through `@DataBoundSetter`, so the Configuration as Code plugin should be able to configure
them. **This example is untested**: the plugin has no automated JCasC test and the `configuration-as-code` plugin is not a
dependency.

```yaml
unclassified:
  dockerSandbox:
    strategy: LEAST_LOADED          # LEAST_LOADED | ROUND_ROBIN | WEIGHTED_RANDOM | FIRST_AVAILABLE
    defaultKit: shell
    defaultImage: ""                # e.g. maven:3.9-eclipse-temurin-21 (wins over defaultKit when set)
    defaultSize: small
    defaultTtlMinutes: 60
    leaseTimeoutMinutes: 30
    cooldownMinutes: 5
    maxFailoverAttempts: 3
    orphanCleanupEnabled: true
    accounts:
      - id: acc-eu
        displayName: Docker EU
        credentialsId: docker-acc-eu
        maxConcurrent: 10
        weight: 2
        labels: "eu large"
        enabled: true
      - id: acc-us
        credentialsId: docker-acc-us
```

The Jenkins credentials (`docker-acc-eu`, `docker-acc-us`) must be defined too, for example under `credentials:` with
a `usernamePassword` entry.
