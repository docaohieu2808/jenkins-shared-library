package org.pipeline

class ProjectDetector implements Serializable {
    def steps

    ProjectDetector(steps) { this.steps = steps }

    Map detect() {
        def result = [
            language: 'unknown',
            framework: 'unknown',
            buildTool: 'unknown',
            hasDocker: false,
            hasCompose: false
        ]

        // --- Node.js ---
        if (steps.fileExists('package.json')) {
            result.language = 'node'
            def pkg = steps.readJSON(file: 'package.json')
            def deps = (pkg.dependencies ?: [:]) + (pkg.devDependencies ?: [:])

            if (deps.containsKey('next'))              result.framework = 'nextjs'
            else if (deps.containsKey('nuxt'))          result.framework = 'nuxt'
            else if (deps.containsKey('@nestjs/core'))  result.framework = 'nestjs'
            else if (deps.containsKey('express'))       result.framework = 'express'
            else if (deps.containsKey('react'))         result.framework = 'react'
            else if (deps.containsKey('vue'))           result.framework = 'vue'
            else if (deps.containsKey('@angular/core')) result.framework = 'angular'

            result.buildTool = steps.fileExists('yarn.lock') ? 'yarn' :
                               steps.fileExists('pnpm-lock.yaml') ? 'pnpm' :
                               steps.fileExists('bun.lockb') ? 'bun' : 'npm'
        }
        // --- Java ---
        else if (steps.fileExists('pom.xml')) {
            result.language = 'java'
            result.buildTool = 'maven'
            def pom = steps.readFile('pom.xml')
            if (pom.contains('spring-boot'))      result.framework = 'springboot'
            else if (pom.contains('quarkus'))     result.framework = 'quarkus'
            else if (pom.contains('micronaut'))   result.framework = 'micronaut'
        }
        else if (steps.fileExists('build.gradle') || steps.fileExists('build.gradle.kts')) {
            result.language = 'java'
            result.buildTool = 'gradle'
            def buildFile = steps.fileExists('build.gradle') ? 'build.gradle' : 'build.gradle.kts'
            def content = steps.readFile(buildFile)
            if (content.contains('spring-boot'))  result.framework = 'springboot'
            else if (content.contains('quarkus')) result.framework = 'quarkus'
        }
        // --- Python ---
        else if (steps.fileExists('pyproject.toml') || steps.fileExists('requirements.txt')) {
            result.language = 'python'
            result.buildTool = steps.fileExists('pyproject.toml') ? 'poetry' :
                               steps.fileExists('Pipfile') ? 'pipenv' : 'pip'
            if (steps.fileExists('manage.py'))         result.framework = 'django'
            else if (steps.fileExists('app/main.py'))  result.framework = 'fastapi'
            else if (steps.fileExists('wsgi.py'))      result.framework = 'flask'
        }
        // --- Go ---
        else if (steps.fileExists('go.mod')) {
            result.language = 'go'
            result.buildTool = 'go'
        }
        // --- .NET ---
        else if (steps.sh(script: 'ls *.csproj *.sln 2>/dev/null', returnStatus: true) == 0) {
            result.language = 'dotnet'
            result.buildTool = 'dotnet'
        }
        // --- PHP ---
        else if (steps.fileExists('composer.json')) {
            result.language = 'php'
            result.buildTool = 'composer'
            def composer = steps.readJSON(file: 'composer.json')
            def require = composer.require ?: [:]
            if (require.containsKey('laravel/framework'))  result.framework = 'laravel'
            else if (require.containsKey('symfony/symfony')) result.framework = 'symfony'
        }
        // --- Rust ---
        else if (steps.fileExists('Cargo.toml')) {
            result.language = 'rust'
            result.buildTool = 'cargo'
        }

        // Docker detection
        result.hasDocker = steps.fileExists('Dockerfile')
        result.hasCompose = steps.fileExists('docker-compose.yml') ||
                            steps.fileExists('docker-compose.yaml') ||
                            steps.fileExists('compose.yml')

        return result
    }
}
