# Installation

## Requirements

| Item | Requirement |
| --- | --- |
| Jenkins | 2.541 or newer (the plugin baseline, `jenkins.baseline` in `pom.xml`) |
| Java | 17 or newer (CI builds with 17 and 21) |
| Docker | One or more Docker accounts with a personal access token that has **Allow Cloud Sandboxes access** (see [configuration](configuration.md#docker-personal-access-token)) |
| Network | The Jenkins controller must reach `https://hub.docker.com` (token exchange) and `https://connect.docker.com/sandboxes` (sandbox API) plus the per-sandbox endpoint URLs the API returns |
| Operating system | The plugin runs on any OS Jenkins supports; the test suite is Linux-only because it fakes the sandbox with local `bash` |

The sandbox image must contain `bash` and `tar` (see [Troubleshooting](troubleshooting.md#sandbox-image-has-no-bash-or-tar)).

### Plugin dependencies

Jenkins installs these automatically when you install from an update center. When you upload the `.hpi` by hand,
make sure they are present first:

* [Credentials](https://plugins.jenkins.io/credentials/)
* [Structs](https://plugins.jenkins.io/structs/)
* [Script Security](https://plugins.jenkins.io/script-security/)
* [Pipeline: Step API](https://plugins.jenkins.io/workflow-step-api/)
* [Jackson 2 API](https://plugins.jenkins.io/jackson2-api/)

To use the `sandboxRun` step in Pipeline jobs you also need the Pipeline plugins (for example the *Pipeline* suite
`workflow-aggregator`). Freestyle jobs need no additional plugin.

## Get the plugin

Download `docker-sandbox-X.Y.Z.hpi` (and the optional `.hpi.sha256` checksum) from the
[GitHub Releases](https://github.com/npv2k1/docker-sandbox-plugin/releases) page. Verify the checksum:

```bash
sha256sum -c docker-sandbox-X.Y.Z.hpi.sha256
```

You can also build it yourself, see [development](development.md#build-and-test); the result is `target/docker-sandbox.hpi`.

## Install through the Jenkins UI

1. *Manage Jenkins > Plugins > Advanced settings*.
2. Under **Deploy Plugin**, choose the `.hpi` file and press **Deploy**.
3. Restart Jenkins (tick *Restart Jenkins when installation is complete and no jobs are running* if offered).

## Install with curl

Use an admin user and its API token:

```bash
JENKINS_URL=https://jenkins.example.com
curl -fsS -u "$JENKINS_USER:$JENKINS_TOKEN" \
  -F "file=@docker-sandbox-X.Y.Z.hpi" "$JENKINS_URL/pluginManager/uploadPlugin"
curl -fsS -X POST -u "$JENKINS_USER:$JENKINS_TOKEN" "$JENKINS_URL/safeRestart"
```

`/safeRestart` waits for running builds to finish. This is exactly what the optional `deploy` job of the release
workflow does, see [development](development.md#cicd).

## Install into a Docker image

Copy the file into the plugins directory of the Jenkins home:

```bash
docker cp docker-sandbox-X.Y.Z.hpi jenkins:/var/jenkins_home/plugins/docker-sandbox.hpi
docker restart jenkins
```

To bake it into a custom image based on the official `jenkins/jenkins` image, put the file into the reference
directory the image copies into `/var/jenkins_home/plugins` on start:

```dockerfile
FROM jenkins/jenkins:lts-jdk17
COPY docker-sandbox-X.Y.Z.hpi /usr/share/jenkins/ref/plugins/docker-sandbox.hpi
```

The name of the installed file determines the plugin id; keep it as `docker-sandbox` (the plugin's `artifactId`).

## Verify

After the restart, *Manage Jenkins > Plugins > Installed plugins* lists **Docker Cloud Sandboxes Plugin**, and
*Manage Jenkins* shows a **Docker Cloud Sandboxes** entry in the *Status Information* section. Continue with the
[configuration](configuration.md).

## Upgrade

Upload the new `.hpi` the same way as the first install (UI or curl) and restart. Plugin settings are kept in
`$JENKINS_HOME/io.jenkins.plugins.dockersandbox.DockerSandboxGlobalConfiguration.xml`, so accounts and defaults survive.
Builds that are running during a restart fail, see [Troubleshooting](troubleshooting.md#build-failed-after-a-controller-restart);
leftover sandboxes are removed by the [orphan cleanup](configuration.md#orphan-cleanup). Per-account usage counters
are in memory and start from zero after a restart.

## Uninstall

1. Make sure no job uses `sandboxRun` or the *Run script in Docker Cloud Sandbox* build step; jobs that still
   reference them will fail or lose those steps after the plugin is gone.
2. Optionally remove the accounts and the Jenkins credentials created for them.
3. *Manage Jenkins > Plugins > Installed plugins*, uninstall **Docker Cloud Sandboxes Plugin**, restart.
4. Delete the leftover configuration file `io.jenkins.plugins.dockersandbox.DockerSandboxGlobalConfiguration.xml`
   from `$JENKINS_HOME` if you do not plan to reinstall.
5. Sandboxes that were kept with `keepSandbox` expire with their TTL; you can also delete them in your Docker account.
   Revoke the personal access tokens in Docker Hub if they are no longer needed.
