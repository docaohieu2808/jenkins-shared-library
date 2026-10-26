// Runs after "Docker Build" so the freshly built image can be scanned (env.BUILT_IMAGE).
// config.securityFailOn: LOW | MEDIUM | HIGH | CRITICAL fail the build on findings at or above that
// severity; NONE reports findings as UNSTABLE instead of failing.
def call(Map config) {
    def severities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
    def failOn = (config.securityFailOn ?: 'HIGH').toString().toUpperCase()
    if (failOn != 'NONE' && !severities.contains(failOn)) {
        error "securityFailOn must be one of LOW, MEDIUM, HIGH, CRITICAL, NONE (got '${failOn}')"
    }

    echo "Running security scan for ${config.language} (fail on: ${failOn})..."

    if (failOn == 'NONE') {
        catchError(buildResult: 'UNSTABLE', stageResult: 'UNSTABLE') {
            scan(config, 'HIGH')
        }
    } else {
        scan(config, failOn)
    }
}

def scan(Map config, String failOn) {
    def severities = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
    def atOrAbove = severities.subList(severities.indexOf(failOn), severities.size()).join(',')
    def npmLevel = [LOW: 'low', MEDIUM: 'moderate', HIGH: 'high', CRITICAL: 'critical'][failOn]
    def cvss = [LOW: '1', MEDIUM: '4', HIGH: '7', CRITICAL: '9'][failOn]

    // Container image scan (needs the image the build stage produced).
    if (config.docker && env.BUILT_IMAGE) {
        sh "docker run --rm -v /var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.76.1 image --no-progress --severity ${atOrAbove} --exit-code 1 ${env.BUILT_IMAGE}"
    }

    // Dependency scan per language
    switch (config.language) {
        case 'node':
            sh "npm audit --audit-level=${npmLevel}"
            break
        case 'python':
            sh 'pip-audit'
            break
        case 'java':
            sh "mvn org.owasp:dependency-check-maven:check -B -DfailBuildOnCVSS=${cvss}"
            break
        case 'php':
            sh 'composer audit'
            break
        case 'go':
            sh 'govulncheck ./...'
            break
        case 'rust':
            sh 'cargo audit'
            break
        default:
            echo "No security scanner configured for ${config.language}"
    }
}
