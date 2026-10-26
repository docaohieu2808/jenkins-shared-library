// args.action: 'build' | 'push' | 'both' (default). The pipeline builds first, scans, then pushes,
// so an image that fails the scan is never published.
def call(Map config, Map args = [:]) {
    def action = args.action ?: 'both'
    def registry = config.deploy?.registry ?: env.DOCKER_REGISTRY ?: ''
    def imageName = config.deploy?.image ?: env.JOB_BASE_NAME.toLowerCase().replaceAll('[^a-z0-9-]', '-')
    def tag = config.deploy?.tag ?: "${env.BRANCH_NAME ?: 'build'}-${env.BUILD_NUMBER}".replaceAll('[^a-zA-Z0-9.-]', '-')
    def fullImage = registry ? "${registry}/${imageName}" : imageName

    if (action == 'build' || action == 'both') {
        echo "Building Docker image: ${fullImage}:${tag}"
        sh "docker build -t ${fullImage}:${tag} ."
        // Export for downstream stages
        env.BUILT_IMAGE = "${fullImage}:${tag}"
    }

    if ((action == 'push' || action == 'both') && registry) {
        sh "docker push ${fullImage}:${tag}"

        // Tag latest on main branch
        if (env.BRANCH_NAME == 'main') {
            sh """
                docker tag ${fullImage}:${tag} ${fullImage}:latest
                docker push ${fullImage}:latest
            """
        }
    }
}
