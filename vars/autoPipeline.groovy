import org.pipeline.ProjectDetector
import org.pipeline.ConfigLoader

def call(Map overrides = [:]) {
    // 1. Auto-detect project type (needs node context for fileExists)
    def detected
    def config
    node {
        checkout scm
        def detector = new ProjectDetector(this)
        detected = detector.detect()

        // 2. Load config: defaults → detected → .ci.yml → overrides
        def loader = new ConfigLoader(this)
        config = loader.load(detected, overrides)
    }

    echo "=== Auto Pipeline ==="
    echo "Language: ${config.language} | Framework: ${config.framework} | Build: ${config.buildTool}"
    echo "Docker: ${config.docker} | Deploy: ${config.deploy?.target} | Sonar: ${config.sonar}"
    echo "====================="

    // 3. Run pipeline
    pipeline {
        agent any

        options {
            timeout(time: config.timeout as int, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '10'))
            timestamps()
        }

        stages {
            stage('Build') {
                steps { buildStage(config) }
            }

            stage('Test') {
                parallel {
                    stage('Unit Test') {
                        steps { testStage(config, [type: 'unit']) }
                    }
                    stage('Lint') {
                        when { expression { config.lint != false } }
                        steps { testStage(config, [type: 'lint']) }
                    }
                }
            }

            stage('Security Scan') {
                when { expression { config.security == true } }
                steps { securityScan(config) }
            }

            stage('Sonar') {
                when { expression { config.sonar == true } }
                steps { sonarScan(config) }
            }

            stage('Docker Build & Push') {
                when { expression { config.docker == true } }
                steps { dockerBuildPush(config) }
            }

            stage('Deploy Staging') {
                when { branch 'develop' }
                steps { deployStage(config, [env: 'staging']) }
            }

            stage('Deploy Production') {
                when { branch 'main' }
                steps {
                    script {
                        if (config.deploy?.autoApprove != true) {
                            input message: 'Deploy to production?', ok: 'Deploy'
                        }
                    }
                    deployStage(config, [env: 'production'])
                }
            }
        }

        post {
            success { notifyStage(config, [status: 'SUCCESS']) }
            failure { notifyStage(config, [status: 'FAILURE']) }
            always  { cleanWs() }
        }
    }
}
