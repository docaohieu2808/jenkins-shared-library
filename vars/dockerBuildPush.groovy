def call(Map config) {
    def registry = config.deploy?.registry ?: env.DOCKER_REGISTRY ?: ''
    def imageName = config.deploy?.image ?: env.JOB_BASE_NAME.toLowerCase().replaceAll('[^a-z0-9-]', '-')
    def tag = config.deploy?.tag ?: "${env.BRANCH_NAME}-${env.BUILD_NUMBER}".replaceAll('[^a-zA-Z0-9.-]', '-')
    def fullImage = registry ? "${registry}/${imageName}" : imageName

    echo "Building Docker image: ${fullImage}:${tag}"

    sh "docker build -t ${fullImage}:${tag} ."

    if (registry) {
        sh "docker push ${fullImage}:${tag}"

        // Tag latest on main branch
        if (env.BRANCH_NAME == 'main') {
            sh """
                docker tag ${fullImage}:${tag} ${fullImage}:latest
                docker push ${fullImage}:latest
            """
        }
    }

    // Export for downstream stages
    env.BUILT_IMAGE = "${fullImage}:${tag}"
}
