def call(Map config, Map args = [:]) {
    def environment = args.env ?: 'staging'
    def target = config.deploy?.target
    def host = config.deploy?.hosts?."${environment}" ?: config.deploy?.host

    if (!target) {
        echo "No deploy.target configured - skipping deploy to ${environment}"
        return
    }

    echo "Deploying to ${environment} via ${target}..."

    switch (target) {
        case 'docker-compose':
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def appDir = config.deploy?.path ?: "/opt/${env.JOB_BASE_NAME}"
            requireSafe('deploy host', host, 'host')
            requireSafe('deploy.path', appDir, 'path')
            // known_hosts must be provisioned on the Jenkins agent first (see README).
            sh sshRun(host, """\
                set -e
                cd ${appDir}
                docker compose pull
                docker compose up -d --remove-orphans
            """.stripIndent())
            break

        case 'k8s':
            def namespace = config.deploy?.namespace ?: environment
            def deployment = config.deploy?.name ?: env.JOB_BASE_NAME
            sh """
                kubectl set image deployment/${deployment} \
                    app=${env.BUILT_IMAGE} \
                    -n ${namespace}
                kubectl rollout status deployment/${deployment} -n ${namespace} --timeout=300s
            """
            break

        case 'ssh':
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def command = config.deploy?.command ?: 'echo "No deploy command configured"'
            requireSafe('deploy host', host, 'host')
            // known_hosts must be provisioned on the Jenkins agent first (see README).
            sh sshRun(host, command)
            break

        case 'ssh-build':
            // SSH -> git pull -> build on server -> atomic swap -> restart service, with rollback.
            // Supports: shallow clone, env vars, prod deps, build verification, backup rotation.
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def appDir = config.deploy?.path ?: "/opt/${env.JOB_BASE_NAME}"
            def branch = config.deploy?.branch ?: (environment == 'production' ? 'main' : 'develop')
            def buildCmd = config.deploy?.buildCommand ?: 'npm install && npm run build'
            def service = config.deploy?.service ?: env.JOB_BASE_NAME
            def distDir = config.deploy?.distDir ?: 'dist'
            def backupCount = config.deploy?.backupCount ?: 2
            def gitDepth = config.deploy?.gitDepth ?: 0  // 0 = full clone, >0 = shallow
            def prodDepsCommand = config.deploy?.prodDepsCommand ?: ''  // e.g. 'npm install --omit=dev'
            def verifyCommand = config.deploy?.verifyCommand ?: ''  // e.g. 'test $(find dist -name "*.html" | wc -l) -ge 100'
            def envVars = config.deploy?.envVars ?: [:]  // e.g. [STRAPI_URL: 'https://...']

            requireSafe('deploy host', host, 'host')
            requireSafe('deploy.path', appDir, 'path')
            requireSafe('deploy.branch', branch, 'ref')
            requireSafe('deploy.service', service, 'name')
            requireSafe('deploy.distDir', distDir, 'name')
            requireSafe('deploy.backupCount', backupCount.toString(), 'number')
            requireSafe('deploy.gitDepth', gitDepth.toString(), 'number')

            // Values are single-quoted for the remote shell; a quote inside a value is escaped.
            def envExport = ''
            envVars.each { k, v ->
                requireSafe('deploy.envVars key', k.toString(), 'envname')
                envExport += "export ${k}=" + shellQuote(v.toString()) + "\n"
            }

            def gitPull = gitDepth.toString() != '0'
                ? "git fetch --depth=${gitDepth} origin ${branch} && git checkout FETCH_HEAD"
                : "git fetch origin && git checkout ${branch} && git pull origin ${branch}"

            // The script is sent on stdin through a quoted heredoc: the remote shell receives it byte
            // for byte, so `$(...)`, quotes and `$vars` below are evaluated on the server, not here.
            def remoteScript = """\
set -e
cd ${appDir}

# 1. Git pull
${gitPull}

# 2. Build with env vars. A previous deploy left ${distDir} as a symlink to the live build; the build
#    needs a real directory, so drop the link and put it back if anything fails before the swap.
had_link=0
if [ -L ${distDir} ]; then rm ${distDir}; had_link=1; fi
restore_live() {
    if [ "\$had_link" = 1 ]; then rm -rf ${distDir}; ln -sfn ${distDir}_current ${distDir}; fi
}
trap restore_live EXIT
${envExport}${buildCmd}

# 3. Verify build output
${verifyCommand ? "${verifyCommand} || { echo 'BUILD VERIFICATION FAILED'; exit 1; }" : ':'}

# 4. Install prod-only deps into dist if configured
${prodDepsCommand ?: ':'}

# 5. Backup rotation: keep the newest ${backupCount} backups once the new one is made
for old_backup in \$(ls -dt ${distDir}_backup_* 2>/dev/null | tail -n +${backupCount}); do
    rm -rf "\$old_backup"
done

# 6. Backup current
if [ -d ${distDir}_current ]; then
    mv ${distDir}_current ${distDir}_backup_\$(date +%Y%m%d_%H%M%S_%N)
fi

# 7. Atomic swap
mv ${distDir} ${distDir}_current
ln -sfn ${distDir}_current ${distDir}
trap - EXIT

# 8. Restart service, roll back on failure
if sudo systemctl restart ${service}; then
    echo "Deploy successful"
else
    echo "SERVICE RESTART FAILED - rolling back"
    latest_backup=\$(ls -dt ${distDir}_backup_* 2>/dev/null | head -1)
    if [ -n "\$latest_backup" ]; then
        rm -rf ${distDir}_current
        mv "\$latest_backup" ${distDir}_current
        ln -sfn ${distDir}_current ${distDir}
        sudo systemctl restart ${service}
        echo "Rollback complete"
    fi
    exit 1
fi
"""
            sh sshRun(host, remoteScript)
            break

        case 'ecs':
            sh """
                aws ecs update-service \
                    --cluster ${config.deploy?.cluster ?: environment} \
                    --service ${config.deploy?.service ?: env.JOB_BASE_NAME} \
                    --force-new-deployment
            """
            break

        case 's3-cloudfront':
            // Build output → S3 → invalidate CloudFront cache
            def bucket = config.deploy?.bucket ?: error("deploy.bucket not set in .ci.yml")
            def distDir = config.deploy?.distDir ?: 'dist'
            def distributionId = config.deploy?.distributionId ?: ''
            def region = config.deploy?.region ?: 'ap-southeast-1'
            sh "aws s3 sync ${distDir}/ s3://${bucket}/ --delete --region ${region}"
            if (distributionId) {
                sh "aws cloudfront create-invalidation --distribution-id ${distributionId} --paths '/*'"
            }
            break

        case 'rsync':
            // Rsync build output to server
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def distDir = config.deploy?.distDir ?: 'dist'
            def remotePath = config.deploy?.path ?: "/var/www/${env.JOB_BASE_NAME}"
            def extraArgs = config.deploy?.rsyncArgs ?: ''
            requireSafe('deploy host', host, 'host')
            requireSafe('deploy.path', remotePath, 'path')
            requireSafe('deploy.distDir', distDir, 'name')
            sh "rsync -avz --delete ${extraArgs} ${distDir}/ ${host}:${remotePath}/"
            // Optional: restart service after rsync
            if (config.deploy?.service) {
                requireSafe('deploy.service', config.deploy.service, 'name')
                sh sshRun(host, "sudo systemctl restart ${config.deploy.service}")
            }
            break

        case 'docker-swarm':
            // Docker stack deploy
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def stackName = config.deploy?.stack ?: env.JOB_BASE_NAME
            def composeFile = config.deploy?.composeFile ?: 'docker-compose.yml'
            requireSafe('deploy host', host, 'host')
            requireSafe('deploy.stack', stackName, 'name')
            requireSafe('deploy.composeFile', composeFile, 'path')
            // known_hosts must be provisioned on the Jenkins agent first (see README).
            sh sshRun(host, "docker stack deploy -c ${composeFile} ${stackName} --with-registry-auth")
            break

        // === PaaS / Serverless ===

        case 'vercel':
            // Deploy to Vercel via CLI
            def vercelArgs = config.deploy?.args ?: ''
            def vercelToken = config.deploy?.tokenCredential ?: 'vercel-token'
            withCredentials([string(credentialsId: vercelToken, variable: 'VERCEL_TOKEN')]) {
                def prodFlag = environment == 'production' ? '--prod' : ''
                sh "npx vercel deploy ${prodFlag} --token=\$VERCEL_TOKEN --yes ${vercelArgs}"
            }
            break

        case 'netlify':
            // Deploy to Netlify via CLI
            def distDir = config.deploy?.distDir ?: 'dist'
            def siteId = config.deploy?.siteId ?: error("deploy.siteId not set in .ci.yml")
            def netlifyToken = config.deploy?.tokenCredential ?: 'netlify-token'
            withCredentials([string(credentialsId: netlifyToken, variable: 'NETLIFY_AUTH_TOKEN')]) {
                def prodFlag = environment == 'production' ? '--prod' : ''
                sh "npx netlify-cli deploy --dir=${distDir} --site=${siteId} ${prodFlag}"
            }
            break

        case 'aws-lambda':
            // Deploy AWS Lambda via SAM CLI
            def stackName = config.deploy?.stack ?: "${env.JOB_BASE_NAME}-${environment}"
            def region = config.deploy?.region ?: 'ap-southeast-1'
            def template = config.deploy?.template ?: 'template.yaml'
            def s3Bucket = config.deploy?.s3Bucket ?: ''
            def params = s3Bucket ? "--s3-bucket ${s3Bucket}" : '--resolve-s3'
            sh """
                sam build -t ${template} &&
                sam deploy --stack-name ${stackName} \
                    --region ${region} ${params} \
                    --capabilities CAPABILITY_IAM \
                    --no-confirm-changeset --no-fail-on-empty-changeset
            """
            break

        case 'cloud-run':
            // Deploy to Google Cloud Run
            def project = config.deploy?.project ?: error("deploy.project not set in .ci.yml")
            def serviceName = config.deploy?.service ?: env.JOB_BASE_NAME
            def region = config.deploy?.region ?: 'asia-southeast1'
            def image = env.BUILT_IMAGE ?: error("No Docker image built")
            // Private by default; set deploy.allowUnauthenticated: true to make the service public.
            def accessFlag = config.deploy?.allowUnauthenticated == true ? '--allow-unauthenticated' : '--no-allow-unauthenticated'
            sh """
                gcloud run deploy ${serviceName} \
                    --image ${image} \
                    --project ${project} \
                    --region ${region} \
                    --platform managed \
                    ${accessFlag} \
                    --quiet
            """
            break

        case 'azure-app-service':
            // Deploy to Azure App Service
            def resourceGroup = config.deploy?.resourceGroup ?: error("deploy.resourceGroup not set")
            def appName = config.deploy?.appName ?: env.JOB_BASE_NAME
            def image = env.BUILT_IMAGE ?: ''
            if (image) {
                sh "az webapp config container set -g ${resourceGroup} -n ${appName} --docker-custom-image-name ${image}"
            } else {
                def distDir = config.deploy?.distDir ?: '.'
                sh "az webapp deploy -g ${resourceGroup} -n ${appName} --src-path ${distDir} --type zip"
            }
            break

        // === Kubernetes advanced ===

        case 'helm':
            // Helm chart upgrade
            def release = config.deploy?.release ?: env.JOB_BASE_NAME
            def chart = config.deploy?.chart ?: "./charts/${env.JOB_BASE_NAME}"
            def namespace = config.deploy?.namespace ?: environment
            def values = config.deploy?.values ?: "values-${environment}.yaml"
            def image = env.BUILT_IMAGE ?: ''
            def imageArgs = image ? "--set image.repository=${image.split(':')[0]} --set image.tag=${image.split(':').last()}" : ''
            sh """
                helm upgrade --install ${release} ${chart} \
                    -n ${namespace} --create-namespace \
                    -f ${values} ${imageArgs} \
                    --wait --timeout 300s
            """
            break

        case 'gitops':
            // GitOps: update image tag in manifest repo → ArgoCD/Flux auto-syncs
            def manifestRepo = config.deploy?.manifestRepo ?: error("deploy.manifestRepo not set")
            def manifestPath = config.deploy?.manifestPath ?: "k8s/${environment}"
            def image = env.BUILT_IMAGE ?: error("No Docker image built")
            def gitCredential = config.deploy?.gitCredential ?: 'git-deploy-key'
            withCredentials([usernamePassword(credentialsId: gitCredential, usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                sh """
                    rm -rf _manifest_repo &&
                    git clone https://\$GIT_USER:\$GIT_PASS@${manifestRepo} _manifest_repo &&
                    cd _manifest_repo &&
                    sed -i 's|image:.*|image: ${image}|g' ${manifestPath}/deployment.yaml &&
                    git add . &&
                    git commit -m 'deploy: ${env.JOB_BASE_NAME} ${image}' &&
                    git push origin main
                """
            }
            sh 'rm -rf _manifest_repo'
            break

        case 'terraform':
            // Terraform plan + apply
            def tfDir = config.deploy?.tfDir ?: 'terraform'
            def workspace = config.deploy?.workspace ?: environment
            def vars = config.deploy?.vars ?: ''
            def varArgs = vars ? "-var-file=${vars}" : ''
            sh """
                cd ${tfDir} &&
                terraform init -input=false &&
                terraform workspace select ${workspace} || terraform workspace new ${workspace} &&
                terraform plan -out=tfplan ${varArgs} -input=false &&
                terraform apply -auto-approve tfplan
            """
            break

        // === Modern PaaS ===

        case 'fly-io':
            // Deploy to Fly.io
            def flyApp = config.deploy?.app ?: env.JOB_BASE_NAME
            def flyToken = config.deploy?.tokenCredential ?: 'fly-token'
            withCredentials([string(credentialsId: flyToken, variable: 'FLY_API_TOKEN')]) {
                sh "flyctl deploy --app ${flyApp} --remote-only --yes"
            }
            break

        case 'railway':
            // Deploy to Railway
            def railwayToken = config.deploy?.tokenCredential ?: 'railway-token'
            def railwayService = config.deploy?.service ?: ''
            def serviceArg = railwayService ? "--service ${railwayService}" : ''
            withCredentials([string(credentialsId: railwayToken, variable: 'RAILWAY_TOKEN')]) {
                sh "railway up --detach ${serviceArg}"
            }
            break

        // === Config management ===

        case 'ansible':
            // Run Ansible playbook
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def playbook = config.deploy?.playbook ?: "deploy.yml"
            def inventory = config.deploy?.inventory ?: ''
            def extraVars = "env=${environment} app=${env.JOB_BASE_NAME}"
            if (env.BUILT_IMAGE) { extraVars += " image=${env.BUILT_IMAGE}" }
            def invArg = inventory ? "-i ${inventory}" : "-i '${host},'"
            sh "ansible-playbook ${playbook} ${invArg} --extra-vars '${extraVars}'"
            break

        // === Legacy ===

        case 'sftp':
            // Upload via SFTP
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def distDir = config.deploy?.distDir ?: 'dist'
            def remotePath = config.deploy?.path ?: "/var/www/${env.JOB_BASE_NAME}"
            def credential = config.deploy?.credential ?: 'sftp-credential'
            withCredentials([usernamePassword(credentialsId: credential, usernameVariable: 'SFTP_USER', passwordVariable: 'SFTP_PASS')]) {
                sh """
                    lftp -u \$SFTP_USER,\$SFTP_PASS sftp://${host} -e '
                        mirror -R --delete ${distDir}/ ${remotePath}/;
                        quit
                    '
                """
            }
            break

        // === Mobile ===

        case 'fastlane':
            // iOS/Android deployment via Fastlane
            def lane = config.deploy?.lane ?: environment
            def platform = config.deploy?.platform ?: 'ios'
            sh "bundle exec fastlane ${platform} ${lane}"
            break

        case 'script':
            // Run custom deploy script from project
            def script = config.deploy?.script ?: './deploy.sh'
            sh "chmod +x ${script} && ${script} ${environment}"
            break

        default:
            error "Unknown deploy target: ${target}. Available: docker-compose, k8s, ssh, ssh-build, ecs, s3-cloudfront, rsync, docker-swarm, vercel, netlify, aws-lambda, cloud-run, azure-app-service, helm, gitops, terraform, fly-io, railway, ansible, sftp, fastlane, script"
    }
}

// ---- helpers -------------------------------------------------------------------------------

// Values from .ci.yml that end up unquoted in a shell command must look like what they claim to be.
def requireSafe(String what, String value, String kind) {
    def patterns = [
        host   : '^[A-Za-z0-9_.@:\\[\\]-]+$',
        path   : '^[A-Za-z0-9_./~-]+$',
        ref    : '^[A-Za-z0-9_./-]+$',
        name   : '^[A-Za-z0-9_.@-]+$',
        number : '^[0-9]+$',
        envname: '^[A-Za-z_][A-Za-z0-9_]*$',
    ]
    if (!(value ==~ patterns[kind])) {
        error "${what} contains characters that are not allowed: '${value}'"
    }
}

// Single-quote a value for a POSIX shell: a ' inside becomes '\''
String shellQuote(String value) {
    return "'" + value.replace("'", "'\\''") + "'"
}

// Run a script on a host: it travels on stdin through a quoted heredoc, so no quoting layer
// between Jenkins and the remote shell can mangle it.
String sshRun(String host, String script) {
    return "ssh -o StrictHostKeyChecking=accept-new ${host} bash -s <<'REMOTE_SCRIPT'\n${script}\nREMOTE_SCRIPT"
}
