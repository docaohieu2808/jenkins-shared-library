package org.pipeline

class ConfigLoader implements Serializable {
    def steps

    ConfigLoader(steps) { this.steps = steps }

    /**
     * Load config with priority: defaults → detected → .ci.yml → overrides
     */
    Map load(Map detected, Map overrides = [:]) {
        // Layer 1: library defaults
        def defaults = [
            timeout: 30,
            lint: true,
            sonar: false,
            security: true,
            docker: null,  // auto-detect from Dockerfile
            deploy: [target: 'docker-compose'],
            notifications: [slack: '#ci-builds'],
        ]

        // Layer 2: merge detected info
        def config = deepMerge(defaults, detected)

        // Layer 3: project .ci.yml
        if (steps.fileExists('.ci.yml')) {
            def projectConfig = steps.readYaml(file: '.ci.yml') ?: [:]
            config = deepMerge(config, projectConfig)
        }

        // Layer 4: Jenkinsfile overrides (highest priority)
        config = deepMerge(config, overrides)

        // Auto-resolve: docker build only if Dockerfile exists
        if (config.docker == null) {
            config.docker = config.hasDocker
        }

        return config
    }

    private Map deepMerge(Map base, Map override) {
        def result = [:]
        result.putAll(base)
        override.each { k, v ->
            if (v instanceof Map && result[k] instanceof Map) {
                result[k] = deepMerge(result[k] as Map, v as Map)
            } else if (v != null) {
                result[k] = v
            }
        }
        return result
    }
}
