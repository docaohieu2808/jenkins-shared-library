# jenkins-shared-library

[![ci](https://github.com/docaohieu2808/jenkins-shared-library/actions/workflows/ci.yml/badge.svg)](https://github.com/docaohieu2808/jenkins-shared-library/actions/workflows/ci.yml)

Jenkins shared library (ladder level L6, the capstone of the Jenkins track). A Jenkinsfile with one line,
`autoPipeline()`, detects the project's language and runs build, test, lint, image build, security scan,
optional SonarQube, deploy and notifications. Configuration is layered: defaults, then what was detected, then a
`.ci.yml` in the project, then arguments in the Jenkinsfile.

```
jenkins-config (L4: Jenkins as code)  ->  jenkins-shared-library (L6: this repo)
```

## Level map

| Level | What | Where | Proven in CI by |
| :-- | :-- | :-- | :-- |
| L1 | Global steps (`vars/*.groovy`): build, test, scan, deploy, notify | `vars/` | unit tests per step |
| L2 | Helper classes: language detection, config layering | `src/org/pipeline/` | unit tests |
| L3 | The orchestrator: one declarative pipeline from those steps, with `when` rules | `vars/autoPipeline.groovy` | end-to-end run on a real Jenkins |
| L4 | Deploy targets with real remote scripts (ssh-build with backup and rollback) | `vars/deployStage.groovy` | the generated script runs in bash against a fake `ssh`/`sudo` |
| L6 | Tested as a library: JenkinsPipelineUnit + a live controller that must pass one project and fail another | `test/`, `ci/` | the `unit` and `e2e` jobs |

## Use it

1. Register the library in Jenkins. With [jenkins-config](https://github.com/docaohieu2808/jenkins-config) this is done
   by JCasC under the name `auto-pipeline`, default version `master`. By hand: Manage Jenkins, System,
   Global Trusted Pipeline Libraries, name `auto-pipeline`, default version `master`, Git source.
2. In the project, a Jenkinsfile:

```groovy
@Library('auto-pipeline') _
autoPipeline()
```

With overrides (see [`examples/`](examples)):

```groovy
@Library('auto-pipeline') _
autoPipeline(
    sonar: true,                       // or [projectKey: 'my-app', server: 'name-in-Jenkins']
    securityFailOn: 'CRITICAL',        // LOW | MEDIUM | HIGH | CRITICAL | NONE
    agentLabel: 'node',                // run on a jenkins-config Kubernetes agent
    deploy: [target: 'docker-compose', hosts: [staging: 'deployer@192.0.2.5'], path: '/opt/app']
)
```

or the same settings in `.ci.yml` at the project root (`deploy:`, `notifications:`, ...).

## Pipeline

```
Build -> Test (unit || lint) -> Docker Build* -> Security Scan -> Sonar* -> Docker Push* -> Deploy Staging** -> Deploy Production**
```

`*` only with a Dockerfile / `sonar` set. `**` only on branch `develop` / `main` of a multibranch job and only when a
`deploy.target` is configured. A project with no `.ci.yml` and no deploy settings builds, tests and scans, and never
fails at "Deploy". The image is built before it is scanned and pushed only after the scan passed.

Supported languages: Node.js (npm, yarn, pnpm, bun), Java (Maven, Gradle), Python (pip, pipenv, poetry, uv), Go,
.NET, PHP, Rust.

### Behaviour worth knowing

- Unit tests fail the build. Lint failures mark it UNSTABLE (visible, not fatal). There is no `|| true` anywhere.
- `securityFailOn` (default `HIGH`) fails the build on findings at or above that severity, for the dependency audit of
  the language and for the Trivy image scan. `NONE` reports findings as UNSTABLE instead.
- Trivy runs from the pinned image `aquasec/trivy:0.76.1` against the image the build stage produced.
- SonarQube uses `-Dsonar.token` (the old `sonar.login` is gone) and accepts `sonar: true` or a map.
- `agentLabel` (default: any agent) lets a project run on the labels jenkins-config defines (`node`, `java`,
  `python`, `go`, `docker`). An image build needs an agent with Docker (`docker`).
- Values from `.ci.yml` that reach a shell command unquoted (host, path, branch, service) are validated; remote scripts
  travel through a quoted heredoc on stdin, so quotes and `$(...)` are never reinterpreted on the way.
- Cloud Run deploys are private unless `deploy.allowUnauthenticated: true`.

### Deploy targets

`docker-compose`, `k8s`, `ssh`, `ssh-build`, `ecs`, `s3-cloudfront`, `rsync`, `docker-swarm`, `vercel`, `netlify`,
`aws-lambda`, `cloud-run`, `azure-app-service`, `helm`, `gitops`, `terraform`, `fly-io`, `railway`, `ansible`, `sftp`,
`fastlane`, `script`. `ssh-build` (pull, build on the server, verify, atomic symlink swap, backup rotation, rollback
when the service does not restart) is the one with the deepest test coverage. The others are thin wrappers around the
vendor CLI and are only unit-tested for the cases above: the CLI itself, credentials and the target account are not
exercised in CI.

Before deploying over SSH, put the host key on the agent: `ssh-keyscan -H <host> >> ~/.ssh/known_hosts` (or use the SSH
Agent plugin with a pre-seeded `known_hosts`). The scripts use `StrictHostKeyChecking=accept-new`.

## Layout

```
vars/autoPipeline.groovy     orchestrator (declarative pipeline)
vars/buildStage.groovy       build per language
vars/testStage.groovy        unit tests and lint
vars/dockerBuildPush.groovy  image build, then (separately) push
vars/securityScan.groovy     dependency audit + Trivy image scan
vars/sonarScan.groovy        SonarQube analysis
vars/deployStage.groovy      deploy targets
vars/notifyStage.groovy      Slack / webhook
src/org/pipeline/            ProjectDetector, ConfigLoader
examples/                    Jenkinsfiles and a .ci.yml
test/groovy/                 JenkinsPipelineUnit tests
ci/                          end-to-end harness: throwaway Jenkins, fixture projects
```

## Test it

```bash
gradle test            # Java 21; JenkinsPipelineUnit 1.31, Groovy 2.4 (the line Jenkins runs)
ci/start-jenkins.sh    # needs Docker; then: ci/run-scm-job.sh node-pass /var/jenkins_home/fixtures/node-pass SUCCESS
```

### What CI checks

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) runs on push, pull request, every Monday and on demand:

- `lint`: yamllint, hadolint, actionlint, shellcheck.
- `unit`: all tests above, including that every script in `vars/` compiles and that the real `ssh-build` command expands
  the backup name on the server, rotates backups, keeps `envVars` values byte for byte, aborts on a failed
  verification without breaking the live build, and rolls back when the service restart fails. Most other
  step tests only check the command string a stage generates (they use a fake `sh`); the stage-graph tests in
  `AutoPipelineOrderingTest` run the real declarative `autoPipeline()` and assert behaviour: build, test, image
  build, scan, push, deploy run in that order; a failing build, test or image scan stops every later stage (an
  unscanned image is never pushed); a deploy stage runs only on its branch with its own host and never without a
  `deploy.target`.
- `e2e`: a real Jenkins 2.580.1 (LTS) with this library registered runs `autoPipeline()` on Node fixtures.
  Passing tests must finish SUCCESS; a deliberately failing test must finish FAILURE. A plain Pipeline-from-SCM job
  has no `BRANCH_NAME`, so every fixture Jenkinsfile sets it the way a multibranch job would, which makes the
  `when { branch ... }` conditions really evaluate. The controller also runs a real sshd, and the `ssh` deploy
  target deploys to it: `node-deploy-staging` (branch `develop`) must run the staging deploy and skip production,
  `node-deploy-production` (branch `main`) the reverse, each proven by a marker file the remote command wrote,
  `node-deploy-fail` (remote command exits 3) must go red, and `node-pass` (branch `develop`, no deploy config) must
  skip the deploy stage because of the missing config.

Actions are pinned to full commit SHAs and Dependabot covers actions, Gradle and the test image.

## Honest limits

- The end-to-end run covers the Node path on the controller (`agent any`). Other languages are covered by the unit
  tests of their commands only; their toolchains are not installed in CI.
- Docker build, Trivy, Sonar and the cloud deploy targets need Docker, a SonarQube server or cloud credentials and are
  not run in CI.
- `.ci.yml` is trusted input. The validation above blocks the obvious shell injection, but a malicious `.ci.yml`
  (or `deploy.command`) can still run commands on the agent and the deploy host by design. Do not build untrusted
  forks with deploy credentials.
- `gitops` puts the Git credential in the clone URL, which can show up in process listings on the agent.
