// Jenkinsfile — With inline overrides (no .ci.yml needed)
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
