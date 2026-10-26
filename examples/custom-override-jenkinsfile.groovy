// Jenkinsfile - with inline overrides (no .ci.yml needed)
@Library('auto-pipeline') _
autoPipeline(
    sonar: true,                  // or [projectKey: 'my-app', server: 'sonarqube-name']
    securityFailOn: 'CRITICAL',   // LOW | MEDIUM | HIGH | CRITICAL | NONE (NONE = report as UNSTABLE)
    agentLabel: 'node',           // run on the jenkins-config "node" Kubernetes agent
    deploy: [
        target: 'ssh',
        hosts: [
            staging: 'deployer@192.0.2.5',
            production: 'deployer@192.0.2.10'
        ],
        command: 'cd /opt/myapp && git pull && docker compose up -d'
    ],
    notifications: [
        slack: '#my-team'
    ]
)
