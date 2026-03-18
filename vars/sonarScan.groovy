def call(Map config) {
    def projectKey = config.sonar?.projectKey ?: env.JOB_BASE_NAME

    echo "Running SonarQube analysis for ${projectKey}..."

    withSonarQubeEnv(config.sonar?.server ?: 'sonarqube') {
        sh """
            sonar-scanner \
                -Dsonar.projectKey=${projectKey} \
                -Dsonar.sources=. \
                -Dsonar.host.url=\${SONAR_HOST_URL} \
                -Dsonar.login=\${SONAR_AUTH_TOKEN}
        """
    }
}
