def call(Map config) {
    echo "Running security scan for ${config.language}..."

    // Docker image scan
    if (config.docker && env.BUILT_IMAGE) {
        sh "docker run --rm aquasec/trivy image --severity HIGH,CRITICAL --exit-code 0 ${env.BUILT_IMAGE} || true"
    }

    // Dependency scan per language
    switch (config.language) {
        case 'node':
            sh 'npm audit --audit-level=high || true'
            break
        case 'python':
            sh 'pip-audit || safety check || true'
            break
        case 'java':
            sh 'mvn org.owasp:dependency-check-maven:check -B || true'
            break
        case 'php':
            sh 'composer audit || true'
            break
        case 'go':
            sh 'govulncheck ./... || true'
            break
        case 'rust':
            sh 'cargo audit || true'
            break
        default:
            echo "No security scanner configured for ${config.language}"
    }
}
