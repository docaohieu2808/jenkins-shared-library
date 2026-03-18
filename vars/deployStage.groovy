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

        case 'ecs':
            sh """
                aws ecs update-service \
                    --cluster ${config.deploy?.cluster ?: environment} \
                    --service ${config.deploy?.service ?: env.JOB_BASE_NAME} \
                    --force-new-deployment
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
