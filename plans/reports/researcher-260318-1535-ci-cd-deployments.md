# CI/CD Deployment Flows Research Report
**Date:** 2026-03-18
**Status:** Complete
**Scope:** Jenkins deployment flows beyond basic methods (2024-2026)

---

## Executive Summary

Identified **12 additional production-ready deployment patterns** missing from your current list. Focuses on real-world indie/SMB usage across cloud platforms, serverless, PaaS, and infrastructure-as-code approaches.

Key finding: Modern deployments bifurcate between **container-heavy flows** (Helm/K8s) and **platform-abstraction flows** (serverless/PaaS), with **GitOps** increasingly standard for K8s environments.

---

## New Deployment Flows (High → Low Priority)

### 1. **helm-upgrade** — Helm Chart Deployment to Kubernetes
**Commonality:** HIGH (standard for any K8s-based org)

**Flow Steps:**
1. Build Docker image → push to registry
2. Build Helm chart (or use existing)
3. Run `helm repo update`
4. `helm upgrade --install <release> <chart> --namespace <ns> --values values.yaml`
5. Wait for rollout (automated via `helm` wait flags)

**Best for:** Orgs already running Kubernetes; teams using GitOps with ArgoCD/Flux for sync

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: alpine/helm:latest
  script:
    - helm repo add myrepo https://charts.example.com
    - helm repo update
    - helm upgrade --install myapp myrepo/myapp
      --namespace production
      --values values.yaml
      --wait
      --timeout 10m
  only:
    - main
```

---

### 2. **gitops-argocd** — GitOps with ArgoCD (Git-Driven Deployments)
**Commonality:** HIGH (GitOps is standard 2025+; ArgoCD has 60% market share)

**Flow Steps:**
1. Commit deployment YAML to Git repo (declarative state)
2. ArgoCD controller watches Git repo
3. Detects drift between Git and cluster
4. Automatically syncs (or waits for manual approval)
5. Jenkins triggers ArgoCD sync via API or webhook

**Best for:** Teams wanting "Git as source of truth"; reduced Jenkins coupling to infra; automated rollback via Git revert

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: curlimages/curl:latest
  script:
    # Option A: Let ArgoCD auto-sync on Git commit
    # (Jenkins just commits updated manifests to Git)
    - git clone https://github.com/org/argocd-manifests.git
    - cd argocd-manifests
    - kustomize edit set image app=myapp:$CI_COMMIT_SHA
    - git commit -am "Deploy: $CI_COMMIT_SHA"
    - git push
    # OR Option B: Trigger ArgoCD sync via API
    - curl -X POST https://argocd.example.com/api/v1/applications/myapp/sync
      -H "Authorization: Bearer $ARGOCD_TOKEN"
  only:
    - main
```

---

### 3. **gitops-flux** — GitOps with Flux CD (Git-Driven Deployments)
**Commonality:** MEDIUM-HIGH (CNCF graduated; AWS/Microsoft/GitLab backing post-Weaveworks)

**Flow Steps:**
1. Commit deployment manifests to Git
2. Flux controller watches Git + syncs cluster state
3. Supports automatic image updates via GitOps
4. Jenkins can trigger Flux reconciliation or commit manifests

**Best for:** Teams preferring Flux's lean design; multi-cluster GitOps; existing Weaveworks/CNCF ecosystem

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: bitnami/git:latest
  script:
    - git clone https://github.com/org/flux-manifests.git
    - cd flux-manifests/apps/myapp
    - kustomize edit set image app=myapp:$CI_COMMIT_SHA
    - git commit -am "Flux auto-update: $CI_COMMIT_SHA"
    - git push
    # Flux reconciles automatically, or trigger manually:
    - flux reconcile source git flux-system
  only:
    - main
```

---

### 4. **cloud-run** — Google Cloud Run (Serverless Containers)
**Commonality:** HIGH (Google's official FaaS; 2024 rebranding unifies Functions/Run)

**Flow Steps:**
1. Build Docker image → push to Google Artifact Registry
2. `gcloud run deploy myservice --image gcr.io/project/image:tag --region us-central1`
3. Automatic revision management + traffic splitting support
4. Optional: A/B test via traffic %

**Best for:** Stateless HTTP services; event-driven workloads; minimal ops overhead; pay-per-use

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: google/cloud-sdk:alpine
  before_script:
    - echo $GCP_SERVICE_ACCOUNT_JSON > /tmp/gcp.json
    - gcloud auth activate-service-account --key-file=/tmp/gcp.json
    - gcloud config set project $GCP_PROJECT
  script:
    - gcloud builds submit --tag gcr.io/$GCP_PROJECT/myapp:$CI_COMMIT_SHA
    - gcloud run deploy myapp
      --image gcr.io/$GCP_PROJECT/myapp:$CI_COMMIT_SHA
      --region us-central1
      --allow-unauthenticated
      --memory 512Mi
  only:
    - main
```

---

### 5. **aws-lambda-sam** — AWS Lambda via Serverless Application Model
**Commonality:** HIGH (standard AWS serverless pattern; Jenkins plugin deprecated Feb 2024 → use Docker)

**Flow Steps:**
1. Jenkins runs in Docker container with AWS SAM CLI
2. `sam build` → packages Lambda code + dependencies
3. `sam deploy --guided` → CloudFormation stack management
4. Automatic versioning + alias management
5. Optional: Gradual deployments (Linear/Canary)

**Best for:** Event-driven backends; API integrations; cost-optimization on unpredictable workloads

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: public.ecr.aws/sam/build-nodejs18.x:latest
  before_script:
    - aws configure set aws_access_key_id $AWS_ACCESS_KEY
    - aws configure set aws_secret_access_key $AWS_SECRET_KEY
    - aws configure set region us-east-1
  script:
    - sam build
    - sam deploy
      --no-confirm-changeset
      --no-fail-on-empty-changeset
      --stack-name myapp
      --s3-bucket myapp-artifacts
      --capabilities CAPABILITY_IAM
  only:
    - main
```

---

### 6. **azure-app-service** — Azure App Service (PaaS)
**Commonality:** MEDIUM (enterprise Azure shops; Jenkins plugins ended Feb 2024 → use Azure CLI)

**Flow Steps:**
1. Build Docker image → push to Azure Container Registry
2. `az webapp deployment container config`
3. `az webapp deployment container show-cd-url` (webhook for auto-deploy)
4. OR manually trigger: `az container run` or `az webapp restart`
5. Supports deployment slots for blue/green

**Best for:** Enterprises using Azure; .NET/Java apps; integrated Azure DevOps; Windows containers

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: mcr.microsoft.com/azure-cli:latest
  before_script:
    - az login --service-principal
      -u $AZURE_CLIENT_ID
      -p $AZURE_CLIENT_SECRET
      --tenant $AZURE_TENANT_ID
  script:
    - az acr build
      --registry myregistry
      --image myapp:$CI_COMMIT_SHA
      .
    - az webapp config container set
      --name myapp-web
      --resource-group mygroup
      --docker-custom-image-name myregistry.azurecr.io/myapp:$CI_COMMIT_SHA
      --docker-registry-server-url https://myregistry.azurecr.io
  only:
    - main
```

---

### 7. **vercel-cli** — Vercel Platform (Next.js/Frontend)
**Commonality:** HIGH (market leader for Next.js; $9.3B valuation 2025; >4M v0 users)

**Flow Steps:**
1. Run `vercel deploy --prod` with project token
2. Automatic preview deployments for PRs
3. Automatic production deployment on main branch
4. Environment variables injected from Vercel dashboard
5. Automatic SSL + CDN

**Best for:** React/Next.js projects; indie hackers; rapid iteration; European compliance (SOC2)

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: node:18-alpine
  script:
    - npm install -g vercel
    - vercel deploy --prod --token $VERCEL_TOKEN
  environment:
    name: production
    url: https://myapp.vercel.app
  only:
    - main
```

---

### 8. **netlify-cli** — Netlify Platform (JAMstack/Frontend)
**Commonality:** MEDIUM-HIGH (pioneer JAMstack; $64B PaaS market; Agent Runners 2025)

**Flow Steps:**
1. `netlify deploy --prod` with site ID + access token
2. Automatic preview deploys on PRs
3. Automatic production on main merge
4. Built-in form handling + serverless functions
5. Netlify Edge Functions for HTTP middleware

**Best for:** Static sites + Jamstack; Hugo/Jekyll blogs; serverless functions (Node); visual editing (Netlify Create)

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: node:18-alpine
  before_script:
    - npm install -g netlify-cli
  script:
    - npm run build
    - netlify deploy --prod
      --site $NETLIFY_SITE_ID
      --auth $NETLIFY_AUTH_TOKEN
      --dir=dist
  environment:
    name: production
    url: https://myapp.netlify.app
  only:
    - main
```

---

### 9. **railway-deploy** — Railway Platform (Full-Stack PaaS)
**Commonality:** MEDIUM-HIGH (recommended for production apps; container-native; $25/mo baseline)

**Flow Steps:**
1. Connect GitHub → Railway auto-deploys on push
2. OR use Railway CLI: `railway up`
3. Auto-scaling on CPU/memory thresholds
4. PostgreSQL/Redis provisioned on-platform
5. Supports: Node, Python, Ruby, Go, Rust, Java

**Best for:** Full-stack apps; teams wanting Heroku replacement; emerging favorite 2024-2025; better scaling than Render

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: railway/cli:latest
  script:
    - railway login --token $RAILWAY_TOKEN
    - railway link $RAILWAY_PROJECT_ID
    - railway up
  environment:
    name: production
    url: https://myapp.up.railway.app
  only:
    - main
```

---

### 10. **terraform-deploy** — Terraform Infrastructure as Code
**Commonality:** HIGH (standard IaC for multi-cloud; used by 65% of enterprises)

**Flow Steps:**
1. Validate Terraform files (`terraform validate`)
2. Plan changes (`terraform plan` → review)
3. Apply changes (`terraform apply` → creates/updates infra)
4. Store state in remote backend (S3/GCS/Terraform Cloud)
5. Optional: Use `terraform-lock` for state locking

**Best for:** Multi-cloud deployments; infra automation; treating infra as code; teams with dedicated DevOps

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: hashicorp/terraform:1.7
  before_script:
    - terraform init
      -backend-config="bucket=$TF_STATE_BUCKET"
      -backend-config="key=$CI_COMMIT_SHA.tfstate"
  script:
    - terraform validate
    - terraform plan -out=tfplan
    - terraform apply tfplan
  environment:
    name: production
  only:
    - main
```

---

### 11. **ansible-deploy** — Ansible Configuration Management
**Commonality:** MEDIUM (agentless; alternative to Terraform; idempotent; 2025 updates ongoing)

**Flow Steps:**
1. Jenkins triggers `ansible-playbook` with inventory (hosts)
2. Playbook handles config management + app deployment
3. Idempotent operations ensure consistency across runs
4. Can be layered: Terraform (infra) → Ansible (config)
5. No agent required on target servers

**Best for:** Multi-server deployments; mixed environments; existing Ansible infrastructure; Windows + Linux

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: alpine/ansible:latest
  before_script:
    - mkdir -p ~/.ssh
    - echo "$SSH_PRIVATE_KEY" > ~/.ssh/id_rsa
    - chmod 600 ~/.ssh/id_rsa
    - ssh-keyscan -H $DEPLOY_HOST >> ~/.ssh/known_hosts
  script:
    - ansible-playbook -i $DEPLOY_HOST, playbooks/deploy.yml
      -u deploy
      -e "app_version=$CI_COMMIT_SHA"
  only:
    - main
```

---

### 12. **sftp-deploy** — SFTP/FTP for Legacy Hosts
**Commonality:** MEDIUM (still prevalent on shared hosting; < $5/mo; older tech stacks)

**Flow Steps:**
1. Build app locally
2. Connect via SFTP (encrypted over SSH, unlike FTP)
3. Upload build artifacts to server
4. Run remote script to unzip/restart app
5. Optional: Maintain backup of previous version

**Best for:** Shared hosting (GoDaddy, Bluehost); legacy WordPress/PHP; minimal ops budget; non-container stacks

**Example `.ci.yml` config (using Publish Over SSH):**
```yaml
deploy:
  stage: deploy
  image: alpine:latest
  before_script:
    - apk add --no-cache openssh-client rsync
    - mkdir -p ~/.ssh
    - echo "$SSH_PRIVATE_KEY" > ~/.ssh/deploy_key
    - chmod 600 ~/.ssh/deploy_key
  script:
    - scp -r -i ~/.ssh/deploy_key
      -o StrictHostKeyChecking=no
      dist/ deploy@example.com:/var/www/myapp/
    - ssh -i ~/.ssh/deploy_key
      -o StrictHostKeyChecking=no
      deploy@example.com
      "cd /var/www/myapp && ./restart.sh"
  only:
    - main
```

Alternatively using Jenkins' **Publish Over SSH Plugin**:
```groovy
pipeline {
  stages {
    stage('Deploy') {
      steps {
        sshPublisher(
          publishers: [
            sshPublisherDesc(
              configName: 'prod-server',
              transfers: [sshTransfer(
                sourceFiles: 'dist/**',
                removePrefix: 'dist',
                remoteDirectory: '/var/www/myapp/',
                execCommand: 'cd /var/www/myapp && ./restart.sh'
              )]
            )
          ]
        )
      }
    }
  }
}
```

---

### 13. **fly-io-deploy** — Fly.io (Global Edge Deployment)
**Commonality:** MEDIUM (specialized for distributed/edge; strong for real-time apps)

**Flow Steps:**
1. Build image locally or via Fly.io builder
2. `flyctl deploy` → pushes to Fly registry
3. Automatic rolling restart across regions
4. Distributed by default (app runs in multiple regions)
5. Built-in DDoS protection + SSL

**Best for:** Global apps needing low-latency; WebSocket apps; chat/collaboration; real-time features

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: debian:bookworm-slim
  before_script:
    - apt-get update && apt-get install -y curl
    - curl -L https://fly.io/install.sh | sh
  script:
    - flyctl auth login --token $FLY_API_TOKEN
    - flyctl deploy --remote-only
  environment:
    name: production
    url: https://myapp.fly.dev
  only:
    - main
```

---

### 14. **pulumi-deploy** — Pulumi Infrastructure as Code
**Commonality:** MEDIUM (modern alternative to Terraform; Python/TS/Go; 2025 growth)

**Flow Steps:**
1. Write infra in Python/TypeScript (not HCL)
2. `pulumi up` → preview + apply changes
3. State stored in Pulumi Cloud or self-hosted
4. Native support for AWS/Azure/GCP/K8s
5. Full language support (testing, CI/CD integration)

**Best for:** Teams comfortable with programming languages; rapid iteration; multi-cloud; Kubernetes-first orgs

**Example `.ci.yml` config:**
```yaml
deploy:
  stage: deploy
  image: pulumi/pulumi:latest
  before_script:
    - pulumi login $PULUMI_BACKEND_URL
    - pulumi stack select production
  script:
    - pulumi up --yes
  environment:
    name: production
  secrets:
    PULUMI_ACCESS_TOKEN: $PULUMI_TOKEN
  only:
    - main
```

---

### 15. **mobile-fastlane** — Mobile App Deployment (iOS/Android)
**Commonality:** MEDIUM (critical for mobile teams; 2024+ still growing)

**Flow Steps:**
1. Build iOS/Android project
2. Sign app with certificates + provisioning profiles
3. Fastlane `build_app` + code signing
4. `upload_to_app_store` (iOS) or `upload_to_play_store` (Android)
5. Automatic release notes + screenshots

**Best for:** iOS/Android apps; indie mobile developers; enterprise mobile teams; cross-platform (React Native, Flutter)

**Example `.ci.yml` config (iOS):**
```yaml
deploy_ios:
  stage: deploy
  image: macos/ventura:latest
  before_script:
    - sudo gem install fastlane
    - fastlane supply init --json_key_data=$GCP_PLAY_KEY
  script:
    - cd ios
    - fastlane ios release
      app_identifier:"com.example.myapp"
      username:"$APPLE_ID"
      password:"$APPLE_PASSWORD"
  only:
    - main
```

---

## Comparison Matrix

| Flow | Best For | Commonality | Ops Overhead | Cost Model | Infrastructure |
|------|----------|-------------|--------------|-----------|-----------------|
| helm-upgrade | K8s shops | HIGH | Medium | Bring-your-own-K8s | On-prem/Cloud |
| gitops-argocd | Git-driven K8s | HIGH | Low | Bring-your-own-K8s | K8s |
| gitops-flux | Git-driven K8s | MED-HIGH | Low | Bring-your-own-K8s | K8s |
| cloud-run | Serverless stateless | HIGH | Very Low | Pay-per-request | Google Cloud |
| aws-lambda-sam | Serverless event-driven | HIGH | Very Low | Pay-per-execution | AWS |
| azure-app-service | Enterprise .NET/Java | MEDIUM | Low | Pricing tiers | Azure |
| vercel-cli | Next.js/React | HIGH | Very Low | Per-deployment | Vercel |
| netlify-cli | JAMstack/Static | MED-HIGH | Very Low | Bandwidth-based | Netlify |
| railway-deploy | Full-stack PaaS | MED-HIGH | Low | Per-compute-unit | Railway |
| terraform-deploy | Multi-cloud IaC | HIGH | Medium | Bring-your-own | AWS/Azure/GCP |
| ansible-deploy | Config management | MEDIUM | Medium | Bring-your-own | On-prem/Cloud |
| sftp-deploy | Legacy/Shared hosting | MEDIUM | Low | Hosting bill | Shared hosting |
| fly-io-deploy | Global edge apps | MEDIUM | Low | Per-GB bandwidth | Fly.io |
| pulumi-deploy | Modern IaC | MEDIUM | Medium | Bring-your-own | AWS/Azure/GCP |
| mobile-fastlane | iOS/Android apps | MEDIUM | High | Store fees | App Store/Play Store |

---

## Market Adoption Trends (2024-2026)

### ✅ Rising Trends
- **GitOps (ArgoCD/Flux):** 91% CNCF orgs adopted as of 2025; Jenkins coupling decoupling
- **Serverless:** AWS Lambda 2025 updates making "serverless new standard"; but cost surprises on scale
- **Vercel/Netlify:** Vercel $9.3B valuation (Oct 2025); Netlify AI Gateway (Dec 2025)
- **PaaS consolidation:** Railway/Render/Fly.io emerging as Heroku successors
- **Pulumi:** Growing alternative to Terraform; 2025 maturity

### ⚠️ Mature/Stable
- **Kubernetes + Helm:** No longer trendy but standard
- **Terraform:** Ubiquitous; HCL gatekeeping remains
- **Ansible:** Agentless advantage; but Terraform stealing IaC mindshare
- **Docker/containers:** Baseline expectation

### 📉 Declining (But Still Common)
- **Jenkins Plugins:** Many deprecated Feb 2024 (Azure, AWS SAM) → use Docker/CLI
- **FTP/SFTP:** Only on legacy/shared hosting; younger orgs skip entirely
- **Jenkins as CD tool:** Shifting toward GitOps (Argo/Flux); Jenkins relegated to CI role
- **Mobile apps:** Smaller teams gravitating toward web-first; native iOS/Android still prevalent in enterprise

---

## Practical Recommendations

### For Indie Hackers / Solo Developers
**Priority order:**
1. **vercel-cli** or **netlify-cli** — Static/Next.js projects (simplest)
2. **railway-deploy** or **fly-io-deploy** — Full-stack backends (containers without K8s overhead)
3. **aws-lambda-sam** — Event-driven workloads, cost optimization

### For Small Teams (3-20 people)
**Priority order:**
1. **gitops-argocd** — If already K8s; reduces Jenkins coupling
2. **terraform-deploy** — Multi-cloud consistency
3. **ansible-deploy** — Existing non-K8s infrastructure
4. **cloud-run** or **railway-deploy** — Hybrid: serverless + containers

### For Enterprise/SMB (20+ people)
**Priority order:**
1. **helm-upgrade** + **gitops-argocd/flux** — K8s standard
2. **terraform-deploy** + **ansible-deploy** — Multi-cloud + config parity
3. **azure-app-service** or **aws-lambda-sam** — Platform-specific
4. **mobile-fastlane** — If mobile products

---

## Unresolved Questions

1. **Cloud cost optimization:** How are teams budgeting for serverless at scale? (Lambda cold starts, RDS data transfer)
2. **Jenkins survival:** Is Jenkins staying as CI-only, or are teams moving entirely to GitHub Actions/GitLab CI?
3. **GitOps adoption blockers:** Why don't 100% of K8s orgs use GitOps? (answer: operational complexity, team maturity)
4. **Terraform vs Pulumi adoption:** Will Pulumi capture enterprise IaC market, or HCL staying dominant?
5. **Mobile CI/CD fragmentation:** Why no consolidated cross-platform mobile CD standard? (answer: iOS/Android fundamentally different)

---

## Sources

- [Beyond Jenkins CI/CD: Modern Deployment Strategies](https://www.plural.sh/blog/stop-using-jenkins-for-continuous-deployment-cd/)
- [Kubernetes Deployment Strategies: Jenkins to Modern CD](https://devtools.in/blog/kubernetes-deployment-strategies-shift-from-jenkins-to-modern-cd/)
- [Serverless 2025: Essential Patterns and Strategies](https://blog.madrigan.com/en/blog/202603131456/)
- [Vercel, Netlify, Railway, Render: Developer Platforms in 2026](https://algeriatech.news/developer-platforms-vercel-netlify-dx-2026/)
- [Top 10 Heroku Alternatives in 2026](https://www.qovery.com/blog/best-heroku-alternatives)
- [10 Helm Chart Examples for Kubernetes Deployments](https://www.devopstraininginstitute.com/blog/10-helm-chart-examples-for-kubernetes-deployments)
- [Deploying Helm Charts with Jenkins and Groovy: A Comprehensive Guide](https://blog.devops.dev/deploying-helm-charts-with-jenkins-and-groovy-a-comprehensive-guide-c2aa0f2bd424)
- [ArgoCD vs FluxCD vs Jenkins X - Battle of Declarative GitOps Tools](https://komodor.com/blog/argocd-vs-fluxcd-vs-jenkins-x-battle-of-declarative-gitops-tools/)
- [GitOps in 2025: From Old-School Updates to the Modern Way](https://www.cncf.io/blog/2025/06/09/gitops-in-2025-from-old-school-updates-to-the-modern-way/)
- [Terraform and Jenkins: IaC from Your Build](https://www.cloudbees.com/blog/terraform-and-jenkins-iac-from-your-build/)
- [Infrastructure as Code with Jenkins and Terraform](https://bgiriaicloud.medium.com/infrastructure-as-code-with-jenkins-and-terraform-7a1a63872fe4)
- [Using Jenkins for Terraform Management](https://www.env0.com/blog/using-jenkins-for-terraform-management/)
- [Building Android apps with Jenkins: release management](https://www.jenkins.io/blog/2023/05/02/android-and-jenkins-releases/)
- [React Native Developer Series: Deploy iOS and Android Pipelines with Fastlane and Jenkins](https://www.cloudbees.com/blog/react-native-developer-series-deploy-ios-and-android-pipelines-with-fastlane-and-jenkins/)
- [Publish Over FTP - Jenkins Plugin](https://plugins.jenkins.io/publish-over-ftp)
- [Publish Over SSH - Jenkins Plugin](https://plugins.jenkins.io/publish-over-ssh)
- [Tutorial - Deploy to Azure App Service with Jenkins and the Azure CLI](https://learn.microsoft.com/en-us/azure/developer/jenkins/deploy-to-azure-app-service-using-azure-cli)
- [Google Cloud Functions in 2025: What Teams Should Know](https://cloudchipr.com/blog/google-cloud-functions)
- [Serverless Preview Environments and GitOps with CloudBees Core and Google Cloud Run](https://www.cloudbees.com/blog/serverless-preview-environments-and-gitops-cloudbees-core-and-google-cloud-run/)
- [DigitalOcean | Jenkins plugin](https://plugins.jenkins.io/digitalocean-plugin/)
- [Ansible | Jenkins plugin](https://plugins.jenkins.io/ansible/)
- [Using Jenkins to deploy with AWS SAM](https://docs.aws.amazon.com/serverless-application-model/latest/developerguide/deploying-using-jenkins.html)
- [Building a Jenkins Pipeline with AWS SAM](https://aws.amazon.com/blogs/compute/building-a-jenkins-pipeline-with-aws-sam/)
- [I Tried Jenkins in 2025 with Pulumi: Here's How It Went](https://www.pulumi.com/blog/jenkins-pulumi-2025-experience/)
- [Most Effective Infrastructure as Code (IaC) Tools](https://www.pulumi.com/blog/infrastructure-as-code-tools/)

---

**Report Generated:** 2026-03-18 15:35 UTC
**Next Steps:** Implement flows matching your team's infrastructure; prioritize by team size + cloud platform
