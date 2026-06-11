# jenkins-shared-library

**Tier:** Jenkins advanced (L6) — capstone của Jenkins track trong portfolio CI/CD.

**Vị trí trong learning path:**
```
jenkins-config (L4 — IaC server) → jenkins-shared-library (L6 — shared library)
```

## Mục đích

Zero-config `autoPipeline()` cho Jenkins: auto-detect ngôn ngữ → load config theo 4 lớp (defaults → detected → `.ci.yml` → overrides) → build / test / scan / deploy / notify.

Hỗ trợ: Node.js, Java, Go, Python, PHP, Rust, .NET. Deploy targets: docker-compose, k8s, ssh-build, helm, gitops, vercel, netlify, fly.io, railway, ECS, Cloud Run, Azure App Service, Terraform, Ansible, rsync, sftp, fastlane, script.

## Cấu trúc

```
vars/               # Global vars — entry points gọi từ Jenkinsfile
  autoPipeline.groovy   # Zero-config orchestrator
  buildStage.groovy     # Build per-language
  testStage.groovy      # Test + lint per-language
  deployStage.groovy    # 20+ deploy targets
  notifyStage.groovy    # Slack / webhook
src/org/pipeline/   # Helper classes
  ProjectDetector.groovy    # Detect ngôn ngữ từ lockfile/manifest
  ConfigLoader.groovy       # deepMerge 4 config layers
resources/
  defaults.yml        # Default config values
examples/             # Jenkinsfile examples với .ci.yml
```

## Cách dùng

### 1. Đăng ký library trong Jenkins

**Jenkins UI:** Manage Jenkins → Configure System → Global Pipeline Libraries:
- Name: `shared-lib`
- Default version: `main`
- Source: Git → `https://git.docaohieu.com/<org>/jenkins-shared-library.git`

### 2. Jenkinsfile tối giản

```groovy
@Library('shared-lib') _
autoPipeline()
```

### 3. Với overrides

```groovy
@Library('shared-lib') _
autoPipeline(
    deploy: [target: 'docker-compose', hosts: [staging: 'app.example.com']],
    notifications: [slack: '#deployments']
)
```

### 4. Cấu hình qua `.ci.yml` (trong project repo)

```yaml
deploy:
  target: ssh-build
  hosts:
    staging: staging.example.com
    production: prod.example.com
  service: myapp
notifications:
  slack: "#ci-alerts"
```

## Demo points (interview)

- Config layering deepMerge (defaults → detected → `.ci.yml` → overrides) — tương đương concept trong Helm values layering
- ProjectDetector: detect ngôn ngữ từ lockfile/manifest mà không cần khai báo
- 20+ deploy targets trong một shared library — portability across infra stacks
- So sánh được với reusable-workflows (GitHub Actions composite) và gitlab-ci-templates: cùng concept, khác hệ

## Prerequisite: known_hosts

Trước khi chạy deploy qua SSH, provision known_hosts trên Jenkins agent:
```bash
ssh-keyscan -H <deploy-host> >> ~/.ssh/known_hosts
```
Hoặc dùng SSH Agent plugin với volume pre-seeded known_hosts.
