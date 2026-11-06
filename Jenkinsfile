/* See the documentation for more options:
 * https://github.com/jenkins-infra/pipeline-library/
 * Linux only: the integration tests fake the sandbox with local bash.
 */
buildPlugin(
  useContainerAgent: true,
  configurations: [
    [platform: 'linux', jdk: 21],
    [platform: 'linux', jdk: 17],
])
