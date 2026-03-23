# Jenkins Shared Library — Auto Pipeline

A Jenkins shared library that auto-detects project language/framework and runs a full CI/CD pipeline with zero config. Supports **22 deploy targets** out of the box.

## How it works

```
Jenkinsfile → autoPipeline() → ProjectDetector → ConfigLoader → pipeline stages
                                     ↓
                              detect language from files
                              (package.json / pom.xml / go.mod / etc.)
                                     ↓
                              merge: defaults → detected → .ci.yml → Jenkinsfile overrides
```

Config priority (lowest → highest): `resources/defaults.yml` → auto-detected values → `.ci.yml` in project root → inline `autoPipeline()` params.

---

## Levels

| Level | What you get |
|-------|-------------|
| **L1** | Drop 2-line Jenkinsfile. Auto-detect language, build, test, lint, security scan. |
| **L2** | Add `.ci.yml` in project root to configure deploy target without touching Jenkinsfile. |
| **L3** | Pass inline overrides to `autoPipeline()` — sonar, slack, custom deploy commands. |
| **L4** | Override individual `vars/` steps (`buildStage`, `deployStage`, etc.) from your own Jenkinsfile. |
| **MAX** | Extend `src/org/pipeline/ProjectDetector.groovy` or `ConfigLoader.groovy` to customize detection logic. |

---

## Prerequisites

- Jenkins LTS with the following plugins: **Pipeline**, **Git**, **Credentials Binding**, **Workspace Cleanup**
- Library registered in Jenkins under the name `auto-pipeline` (see [Installation](#installation))
- For Docker builds: Docker CLI accessible on the agent
- For Sonar: SonarQube plugin + server configured in Jenkins
- For Slack: Slack Notification plugin + credential `slack-token`

---

## Supported languages

Auto-detected from project files:

| Language | Detected by | Frameworks detected |
|----------|------------|---------------------|
| Node.js | `package.json` | Next.js, Nuxt, NestJS, Express, React, Vue, Angular |
| Java | `pom.xml` / `build.gradle` | Spring Boot, Quarkus, Micronaut |
| Python | `pyproject.toml` / `requirements.txt` | Django, FastAPI, Flask |
| Go | `go.mod` | — |
| .NET | `*.csproj` / `*.sln` | — |
| PHP | `composer.json` | Laravel, Symfony |
| Rust | `Cargo.toml` | — |

---

## Installation

### 1. Register the library in Jenkins (via JCasC or UI)

**Via `casc.yaml` (recommended):**

```yaml
unclassified:
  globalLibraries:
    libraries:
      - name: "auto-pipeline"
        defaultVersion: "master"
        implicit: false
        retriever:
          modernSCM:
            scm:
              gitSource:
                remote: "https://your-git-host/jenkins-shared-library.git"
                credentialsId: "git-creds"
```

**Via UI:** Manage Jenkins → System → Global Pipeline Libraries → Add

### 2. Add credentials

Create the credentials your pipelines will need:

```
# Docker registry
Credentials → Add → Username/Password → ID: "registry-creds"

# SSH deploy key
Credentials → Add → SSH Username with private key → ID: "deploy-key"

# Slack (optional)
Credentials → Add → Secret text → ID: "slack-token"
```

---

## Quick Start

### Zero config (L1)

Drop this into your project's `Jenkinsfile`:

```groovy
@Library('auto-pipeline') _
autoPipeline()
```

That's it. Language is auto-detected, build/test/lint run automatically.

### With `.ci.yml` (L2)

Add a `.ci.yml` to your project root:

```yaml
deploy:
  target: ssh-build
  path: /opt/myapp
  branch: main
  buildCommand: npm install && npm run build
  distDir: dist
  service: myapp
  hosts:
    staging: deploy@10.0.0.5
    production: deploy@10.0.0.10

notifications:
  slack: '#my-team'
```

### With inline overrides (L3)

```groovy
@Library('auto-pipeline') _
autoPipeline(
    sonar: true,
    security: false,
    deploy: [
        target: 'ssh',
        hosts: [
            staging: 'deployer@10.0.0.5',
            production: 'deployer@10.0.0.10'
        ],
        command: 'cd /opt/myapp && git pull && docker compose up -d'
    ],
    notifications: [
        slack: '#my-team'
    ]
)
```

---

## Deploy Targets (22 total)

Set `deploy.target` in `.ci.yml` or pass as override:

| Target | Description |
|--------|-------------|
| `docker-compose` | SSH → docker compose pull & up (default) |
| `ssh` | SSH → run arbitrary command |
| `ssh-build` | SSH → git pull → build → atomic swap → restart service (with rollback) |
| `k8s` | `kubectl set image` on existing deployment |
| `helm` | `helm upgrade --install` |
| `gitops` | Update image tag in manifest repo (ArgoCD/Flux) |
| `ecs` | AWS ECS force new deployment |
| `s3-cloudfront` | Sync to S3 + CloudFront invalidation |
| `rsync` | rsync build output to server |
| `docker-swarm` | `docker stack deploy` |
| `vercel` | Vercel CLI deploy |
| `netlify` | Netlify CLI deploy |
| `aws-lambda` | AWS SAM build + deploy |
| `cloud-run` | Google Cloud Run deploy |
| `azure-app-service` | Azure App Service deploy |
| `fly-io` | Fly.io deploy |
| `railway` | Railway deploy |
| `terraform` | Terraform plan + apply |
| `ansible` | Ansible playbook |
| `sftp` | SFTP upload via lftp |
| `fastlane` | iOS/Android Fastlane lane |
| `script` | Run `./deploy.sh <env>` from project root |

---

## Configuration Reference

All keys available in `.ci.yml` or as `autoPipeline()` params:

| Key | Default | Description |
|-----|---------|-------------|
| `timeout` | `30` | Pipeline timeout in minutes |
| `lint` | `true` | Run lint stage |
| `sonar` | `false` | Run SonarQube scan |
| `security` | `true` | Run security scan (Trivy/Grype) |
| `docker` | auto | Build Docker image (auto: true if Dockerfile exists) |
| `deploy.target` | `docker-compose` | Deploy target (see table above) |
| `deploy.hosts.staging` | — | SSH host for staging |
| `deploy.hosts.production` | — | SSH host for production |
| `deploy.path` | `/opt/<job-name>` | Remote app directory |
| `deploy.branch` | `main` / `develop` | Git branch to pull on server |
| `deploy.buildCommand` | language-specific | Build command for `ssh-build` |
| `deploy.distDir` | `dist` | Build output directory |
| `deploy.service` | `<job-name>` | Systemd service name |
| `deploy.backupCount` | `2` | Number of backups to keep (`ssh-build`) |
| `deploy.autoApprove` | `false` | Skip manual approval for production |
| `notifications.slack` | `#ci-builds` | Slack channel |

---

## Repository Structure

```
jenkins-shared-library/
├── vars/
│   ├── autoPipeline.groovy      # Main entry point
│   ├── buildStage.groovy        # Build step
│   ├── testStage.groovy         # Test + lint step
│   ├── deployStage.groovy       # 22 deploy targets
│   ├── dockerBuildPush.groovy   # Docker build + push
│   ├── securityScan.groovy      # Security scanning
│   ├── sonarScan.groovy         # SonarQube analysis
│   └── notifyStage.groovy       # Slack/email notifications
├── src/org/pipeline/
│   ├── ProjectDetector.groovy   # Language/framework detection
│   └── ConfigLoader.groovy      # 4-layer config merge
├── resources/
│   └── defaults.yml             # Library defaults
└── examples/
    ├── zero-config-jenkinsfile.groovy
    ├── custom-override-jenkinsfile.groovy
    └── example-client.ci.yml
```
