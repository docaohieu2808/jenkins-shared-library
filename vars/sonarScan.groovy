// config.sonar is either true (defaults) or a map: [projectKey: 'my-app', server: 'name-in-Manage-Jenkins']
def call(Map config) {
    def sonar = config.sonar instanceof Map ? config.sonar : [:]
    def projectKey = sonar.projectKey ?: env.JOB_BASE_NAME
    def server = sonar.server ?: 'sonarqube'

    echo "Running SonarQube analysis for ${projectKey}..."

    withSonarQubeEnv(server) {
        // sonar.login was removed in favour of sonar.token; the token comes from the plugin's environment.
        sh """
            sonar-scanner \\
                -Dsonar.projectKey='${projectKey}' \\
                -Dsonar.sources=. \\
                -Dsonar.host.url="\$SONAR_HOST_URL" \\
                -Dsonar.token="\$SONAR_AUTH_TOKEN"
        """
    }
}
