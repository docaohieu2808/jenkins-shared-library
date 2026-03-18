def call(Map config, Map args = [:]) {
    def environment = args.env ?: 'staging'
    def target = config.deploy?.target ?: 'docker-compose'
    def host = config.deploy?.hosts?."${environment}" ?: config.deploy?.host

    echo "Deploying to ${environment} via ${target}..."

    switch (target) {
        case 'docker-compose':
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def appDir = config.deploy?.path ?: "/opt/${env.JOB_BASE_NAME}"
            sh """
                ssh -o StrictHostKeyChecking=no ${host} '
                    cd ${appDir} &&
                    docker compose pull &&
                    docker compose up -d --remove-orphans
                '
            """
            break

        case 'k8s':
            def namespace = config.deploy?.namespace ?: environment
            def deployment = config.deploy?.name ?: env.JOB_BASE_NAME
            sh """
                kubectl set image deployment/${deployment} \
                    app=${env.BUILT_IMAGE} \
                    -n ${namespace} --record
                kubectl rollout status deployment/${deployment} -n ${namespace} --timeout=300s
            """
            break

        case 'ssh':
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def command = config.deploy?.command ?: 'echo "No deploy command configured"'
            sh "ssh -o StrictHostKeyChecking=no ${host} '${command}'"
            break

        case 'ssh-build':
            // SSH → git pull → build on server → atomic swap → restart service
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def appDir = config.deploy?.path ?: "/opt/${env.JOB_BASE_NAME}"
            def branch = config.deploy?.branch ?: (environment == 'production' ? 'main' : 'develop')
            def buildCmd = config.deploy?.buildCommand ?: 'npm install && npm run build'
            def service = config.deploy?.service ?: env.JOB_BASE_NAME
            def distDir = config.deploy?.distDir ?: 'dist'
            sh """
                ssh -o StrictHostKeyChecking=no ${host} '
                    cd ${appDir} &&
                    git fetch origin &&
                    git checkout ${branch} &&
                    git pull origin ${branch} &&
                    ${buildCmd} &&
                    if [ -d ${distDir}_old ]; then rm -rf ${distDir}_old; fi &&
                    if [ -d ${distDir}_current ]; then mv ${distDir}_current ${distDir}_old; fi &&
                    mv ${distDir} ${distDir}_current &&
                    ln -sfn ${distDir}_current ${distDir} &&
                    sudo systemctl restart ${service}
                '
            """
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
            sh "rsync -avz --delete ${extraArgs} ${distDir}/ ${host}:${remotePath}/"
            // Optional: restart service after rsync
            if (config.deploy?.service) {
                sh "ssh -o StrictHostKeyChecking=no ${host} 'sudo systemctl restart ${config.deploy.service}'"
            }
            break

        case 'docker-swarm':
            // Docker stack deploy
            if (!host) { error "deploy.hosts.${environment} not set in .ci.yml" }
            def stackName = config.deploy?.stack ?: env.JOB_BASE_NAME
            def composeFile = config.deploy?.composeFile ?: 'docker-compose.yml'
            sh """
                ssh -o StrictHostKeyChecking=no ${host} '
                    docker stack deploy -c ${composeFile} ${stackName} --with-registry-auth
                '
            """
            break

        case 'script':
            // Run custom deploy script from project
            def script = config.deploy?.script ?: './deploy.sh'
            sh "chmod +x ${script} && ${script} ${environment}"
            break

        default:
            error "Unknown deploy target: ${target}"
    }
}
